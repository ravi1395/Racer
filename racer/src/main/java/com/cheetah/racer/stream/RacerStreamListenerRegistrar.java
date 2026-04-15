package com.cheetah.racer.stream;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerStreamListener;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.exception.RacerCircuitOpenException;
import com.cheetah.racer.exception.RacerConfigurationException;
import com.cheetah.racer.listener.AbstractRacerRegistrar;
import com.cheetah.racer.listener.InterceptorContext;
import com.cheetah.racer.listener.RacerDeadLetterHandler;
import com.cheetah.racer.listener.RacerMessageInterceptor;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.util.RacerChannelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * {@link BeanPostProcessor} that discovers methods annotated with
 * {@link RacerStreamListener}
 * and registers them as consumer-group readers for Redis Streams at application
 * startup.
 *
 * <h3>Processing pipeline (per stream entry)</h3>
 * <ol>
 * <li>Read up to {@code batchSize} entries per poll via
 * {@code XREADGROUP}.</li>
 * <li>Parse the {@code data} field of each entry into a {@link RacerMessage}
 * envelope.</li>
 * <li>Validate against schema if {@link RacerSchemaRegistry} is available.</li>
 * <li>Resolve method argument by declared parameter type (same rules as {@link
 * com.cheetah.racer.listener.RacerListenerRegistrar}).</li>
 * <li>Invoke the annotated method on {@code boundedElastic} scheduler.</li>
 * <li>On success: ACK the entry, increment processed counter.</li>
 * <li>On failure: ACK the entry (prevent infinite redelivery) + enqueue to
 * DLQ.</li>
 * </ol>
 *
 * @see RacerStreamListener
 */
@Slf4j
public class RacerStreamListenerRegistrar extends AbstractRacerRegistrar {

    private static final String DEFAULT_DATA_FIELD = "data";
    private static final int GROUP_CREATION_RETRIES = 5;

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private final RacerMetricsPort racerMetrics;
    @Nullable
    private final RacerSchemaRegistry racerSchemaRegistry;

    // ── Phase 3 optional extensions ─────────────────────────────────────────

    /** Injected when {@code racer.dedup.enabled=true}. */
    @Nullable
    private volatile RacerDedupService dedupService;
    @Nullable
    private ObjectProvider<RacerDedupService> dedupServiceProvider;

    /** Injected when {@code racer.circuit-breaker.enabled=true}. */
    @Nullable
    private volatile RacerCircuitBreakerRegistry circuitBreakerRegistry;
    @Nullable
    private ObjectProvider<RacerCircuitBreakerRegistry> circuitBreakerRegistryProvider;

    /**
     * When non-zero, overrides the annotation-defined poll interval for all stream
     * listeners.
     * Set by {@link com.cheetah.racer.backpressure.RacerBackPressureMonitor}.
     * Value 0 means “use the annotation value”.
     */
    private final AtomicLong backPressurePollOverrideMs = new AtomicLong(0);

    /**
     * (streamKey, group) pairs that have been registered, used by
     * {@link RacerConsumerLagMonitor}.
     */
    private final Map<String, String> trackedStreamGroups = new ConcurrentHashMap<>();

    /**
     * Stores registration metadata for every stream listener so
     * {@link com.cheetah.racer.test.RacerTestHarness} can inject synthetic messages
     * directly into the processing pipeline without Redis Streams.
     */
    private final Map<String, StreamListenerRegistration> streamListenerRegistrations = new ConcurrentHashMap<>();

    /**
     * Metadata captured at stream listener registration time.
     *
     * @param bean         the Spring bean that owns the handler method
     * @param method       the {@code @RacerStreamListener}-annotated method
     * @param streamKey    the resolved Redis stream key
     * @param group        the consumer group name
     * @param dedupEnabled whether deduplication is active for this listener
     */
    public record StreamListenerRegistration(Object bean, Method method, String streamKey,
            String group, boolean dedupEnabled) {
    }

    /**
     * When set, newly registered streams are automatically tracked for consumer
     * lag.
     */
    @Nullable
    private volatile RacerConsumerLagMonitor consumerLagMonitor;

    /**
     * Ordered list of message interceptors applied before every handler invocation.
     */
    private volatile List<RacerMessageInterceptor> interceptors = Collections.emptyList();

    /**
     * Injects the ordered list of {@link RacerMessageInterceptor} beans.
     * Called by {@link com.cheetah.racer.config.RacerAutoConfiguration} after the
     * application context has collected all interceptor beans.
     */
    public void setInterceptors(List<RacerMessageInterceptor> interceptors) {
        this.interceptors = interceptors != null ? interceptors : Collections.emptyList();
    }

    public void setDedupService(RacerDedupService dedupService) {
        this.dedupService = dedupService;
    }

    public void setDedupServiceProvider(ObjectProvider<RacerDedupService> provider) {
        this.dedupServiceProvider = provider;
    }

    private RacerDedupService getDedupService() {
        if (dedupService == null && dedupServiceProvider != null) {
            dedupService = dedupServiceProvider.getIfAvailable();
        }
        return dedupService;
    }

    public void setCircuitBreakerRegistry(RacerCircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public void setCircuitBreakerRegistryProvider(ObjectProvider<RacerCircuitBreakerRegistry> provider) {
        this.circuitBreakerRegistryProvider = provider;
    }

    private RacerCircuitBreakerRegistry getCircuitBreakerRegistry() {
        if (circuitBreakerRegistry == null && circuitBreakerRegistryProvider != null) {
            circuitBreakerRegistry = circuitBreakerRegistryProvider.getIfAvailable();
        }
        return circuitBreakerRegistry;
    }

    /**
     * Called by {@link com.cheetah.racer.backpressure.RacerBackPressureMonitor}.
     * Pass {@code 0} to revert to the annotation-defined interval.
     */
    public void setBackPressurePollIntervalMs(long ms) {
        backPressurePollOverrideMs.set(ms);
    }

    /** Returns registered (streamKey → group) pairs for consumer-lag tracking. */
    public Map<String, String> getTrackedStreamGroups() {
        return Collections.unmodifiableMap(trackedStreamGroups);
    }

    /**
     * Injects the consumer lag monitor so that streams registered after the monitor
     * bean is created are automatically tracked.
     */
    public void setConsumerLagMonitor(@Nullable RacerConsumerLagMonitor consumerLagMonitor) {
        this.consumerLagMonitor = consumerLagMonitor;
    }

    public RacerStreamListenerRegistrar(
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            RacerProperties racerProperties,
            @Nullable RacerMetricsPort racerMetrics,
            @Nullable RacerSchemaRegistry racerSchemaRegistry,
            @Nullable RacerDeadLetterHandler deadLetterHandler) {
        super(racerProperties, deadLetterHandler);
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.racerMetrics = racerMetrics != null ? racerMetrics : NoOpRacerMetrics.INSTANCE;
        this.racerSchemaRegistry = racerSchemaRegistry;
    }

    @Override
    protected String logPrefix() {
        return "RACER-STREAM-LISTENER";
    }

    // ── BeanPostProcessor ────────────────────────────────────────────────────

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        for (Method method : targetClass.getDeclaredMethods()) {
            RacerStreamListener ann = method.getAnnotation(RacerStreamListener.class);
            if (ann != null) {
                registerStreamListener(bean, method, ann, beanName);
            }
        }
        return bean;
    }

    // ── SmartLifecycle (delegated to AbstractRacerRegistrar) ────────────────

    // ── Registration ─────────────────────────────────────────────────────────

    private void registerStreamListener(Object bean, Method method, RacerStreamListener ann, String beanName) {
        String rawStreamKey = resolve(ann.streamKey());
        String streamKeyRef = resolve(ann.streamKeyRef());

        // In strict mode, verify that a non-blank streamKeyRef maps to a configured
        // alias.
        // This catches typos like @RacerStreamListener(streamKeyRef="ordres") at
        // startup.
        if (!streamKeyRef.isBlank()
                && racerProperties.isStrictChannelValidation()
                && !racerProperties.getChannels().containsKey(streamKeyRef)) {
            throw new RacerConfigurationException(
                    "@RacerStreamListener(streamKeyRef=\"" + streamKeyRef + "\") on "
                            + beanName + "." + method.getName() + "() — alias not found in racer.channels.*. "
                            + "Defined aliases: " + racerProperties.getChannels().keySet() + ".");
        }

        String streamKey = RacerChannelResolver.resolveStreamKey(
                rawStreamKey, streamKeyRef, racerProperties, logPrefix());

        if (streamKey.isEmpty()) {
            log.warn(
                    "[RACER-STREAM-LISTENER] {}.{}() has @RacerStreamListener but no streamKey/streamKeyRef — skipped.",
                    beanName, method.getName());
            return;
        }

        String listenerId = resolveListenerId(
                ann.id().isEmpty() ? "" : resolve(ann.id()), beanName, method);
        String group = ann.group();
        int concurrencyN = ann.mode() == ConcurrencyMode.SEQUENTIAL ? 1 : Math.max(1, ann.concurrency());
        int batchSize = ann.batchSize();
        Duration pollInterval = Duration.ofMillis(ann.pollIntervalMs());

        method.setAccessible(true);
        registerListenerStats(listenerId);

        // Register for consumer-lag tracking (3.4)
        trackedStreamGroups.put(streamKey, group);
        RacerConsumerLagMonitor lagMonitor = this.consumerLagMonitor;
        if (lagMonitor != null) {
            lagMonitor.trackStream(streamKey, group);
        }

        final boolean dedupEnabled = ann.dedup();

        // Store registration so RacerTestHarness can inject messages directly into
        // the processing pipeline without going through Redis Streams.
        streamListenerRegistrations.put(listenerId,
                new StreamListenerRegistration(bean, method, streamKey, group, dedupEnabled));

        log.info(
                "[RACER-STREAM-LISTENER] Registering {}.{}() <- stream '{}' group='{}' mode={} concurrency={} dedup={}",
                beanName, method.getName(), streamKey, group, ann.mode(), concurrencyN, dedupEnabled);

        final String finalStreamKey = streamKey;
        final String finalListenerId = listenerId;

        ensureGroup(streamKey, group)
                .retryWhen(Retry.backoff(GROUP_CREATION_RETRIES, Duration.ofSeconds(2))
                        .doBeforeRetry(rs -> log.warn(
                                "[RACER-STREAM-LISTENER] Retrying group creation on '{}' (attempt {})",
                                finalStreamKey, rs.totalRetries() + 1)))
                .doOnSuccess(v -> {
                    log.info("[RACER-STREAM-LISTENER] Group '{}' ready on '{}'", group, finalStreamKey);
                    for (int i = 0; i < concurrencyN; i++) {
                        String consumerName = listenerId + "-" + i;
                        Disposable d = buildPollLoop(bean, method, finalStreamKey, group,
                                consumerName, batchSize, pollInterval, finalListenerId, dedupEnabled)
                                .subscribe(
                                        n -> {
                                        },
                                        ex -> log.error("[RACER-STREAM-LISTENER] Consumer '{}' errored: {}",
                                                consumerName, ex.getMessage()));
                        subscriptions.add(d);
                        log.info("[RACER-STREAM-LISTENER] Consumer '{}' started on '{}'", consumerName, finalStreamKey);
                    }
                })
                .doOnError(e -> log.error("[RACER-STREAM-LISTENER] Failed to init group '{}' on '{}': {}",
                        group, finalStreamKey, e.getMessage()))
                .subscribe();
    }

    // ── Polling loop ─────────────────────────────────────────────────────────

    private Flux<Void> buildPollLoop(Object bean, Method method,
            String streamKey, String group, String consumer,
            int batchSize, Duration pollInterval, String listenerId,
            boolean dedupEnabled) {
        return Flux.defer(() -> pollOnce(bean, method, streamKey, group, consumer, batchSize, listenerId, dedupEnabled))
                .repeatWhen(completed -> completed.flatMap(v -> {
                    long overrideMs = backPressurePollOverrideMs.get();
                    if (overrideMs > 0) {
                        // Back-pressure is active; apply the configured back-off delay
                        return Mono.delay(Duration.ofMillis(overrideMs));
                    }
                    long blockMs = racerProperties.getConsumer().getBlockMillis();
                    if (blockMs > 0) {
                        // Blocking reads already handle idle wait on the Redis side;
                        // repeat immediately to avoid double-waiting
                        return Mono.just(v);
                    }
                    // Non-blocking mode: sleep between polls to avoid busy-spin
                    return Mono.delay(pollInterval);
                }))
                .onErrorContinue((ex, o) -> log.error("[RACER-STREAM-LISTENER] Poll error on '{}': {}", streamKey,
                        ex.getMessage()));
    }

    @SuppressWarnings({ "unchecked", "null" })
    private Flux<Void> pollOnce(Object bean, Method method,
            String streamKey, String group, String consumer,
            int batchSize, String listenerId, boolean dedupEnabled) {
        // Build read options: count controls batch size; block() enables Redis-side
        // blocking
        // so the consumer wakes up instantly when new messages arrive instead of
        // busy-polling.
        long blockMs = racerProperties.getConsumer().getBlockMillis();
        StreamReadOptions readOptions = blockMs > 0
                ? StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(blockMs))
                : StreamReadOptions.empty().count(batchSize);
        StreamOffset<String> offset = StreamOffset.create(streamKey, ReadOffset.lastConsumed());
        Consumer redisConsumer = Consumer.from(group, consumer);

        return redisTemplate
                .opsForStream()
                .read(redisConsumer, readOptions, offset)
                .onErrorResume(ex -> {
                    log.debug("[RACER-STREAM-LISTENER] XREADGROUP on '{}' returned empty or error: {}", streamKey,
                            ex.getMessage());
                    return Flux.empty();
                })
                .flatMap(record -> {
                    if (isStopping()) {
                        return RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, record.getId());
                    }
                    incrementInFlight();
                    return processRecord(bean, method, streamKey, group, record, listenerId, dedupEnabled)
                            .doFinally(s -> decrementInFlight());
                });
    }

    private Mono<Void> processRecord(Object bean, Method method,
            String streamKey, String group,
            MapRecord<String, Object, Object> record, String listenerId,
            boolean dedupEnabled) {
        RecordId recordId = record.getId();
        Map<Object, Object> fields = record.getValue();

        // The data field carries the serialized RacerMessage envelope.
        // Parsed first so the message is available for DLQ routing in the CB gate below.
        Object raw = fields.get(DEFAULT_DATA_FIELD);
        if (raw == null) {
            log.warn("[RACER-STREAM-LISTENER] Record {} on '{}' missing '{}' field — skipped", recordId, streamKey,
                    DEFAULT_DATA_FIELD);
            return RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId);
        }

        String envelopeJson = raw.toString();
        RacerMessage message;
        try {
            message = objectMapper.readValue(envelopeJson, RacerMessage.class);
        } catch (Exception e) {
            log.error("[RACER-STREAM-LISTENER] '{}' — failed to deserialize entry {}: {}", listenerId, recordId,
                    e.getMessage());
            incrementFailed(listenerId);
            return RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId);
        }

        // 0a. Circuit breaker gate — checked after parsing so the rejected message can
        //     be forwarded to the DLQ, providing a recovery path and failure visibility
        //     consistent with the Pub/Sub pipeline behaviour.
        RacerCircuitBreaker cb = getCircuitBreakerRegistry() != null
                ? getCircuitBreakerRegistry().getOrCreate(listenerId)
                : null;
        if (cb != null && !cb.isCallPermitted()) {
            log.debug("[RACER-STREAM-LISTENER] '{}' circuit {} — skipping record {}",
                    listenerId, cb.getState(), recordId);
            incrementFailed(listenerId);
            racerMetrics.recordFailed(streamKey, "circuit-open");
            return enqueueDeadLetter(message, new RacerCircuitOpenException(listenerId))
                    .then(RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId));
        }

        // 0b. Deduplication check (only when annotation flag is set)
        if (dedupEnabled && getDedupService() != null) {
            return getDedupService().checkAndMarkProcessed(message.getId(), listenerId)
                    .flatMap(shouldProcess -> {
                        if (!shouldProcess) {
                            log.debug("[RACER-STREAM-LISTENER] '{}' duplicate id={} — skipping record {}",
                                    listenerId, message.getId(), recordId);
                            return RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId);
                        }
                        return processChecked(bean, method, streamKey, group, record, listenerId, message, cb);
                    });
        }

        return processChecked(bean, method, streamKey, group, record, listenerId, message, cb);
    }

    private Mono<Void> processChecked(Object bean, Method method,
            String streamKey, String group,
            MapRecord<String, Object, Object> record, String listenerId,
            RacerMessage message, @Nullable RacerCircuitBreaker cb) {
        RecordId recordId = record.getId();

        // Schema validation
        if (racerSchemaRegistry != null) {
            try {
                racerSchemaRegistry.validateForConsume(streamKey, message.getPayload());
            } catch (Exception e) {
                log.warn("[RACER-STREAM-LISTENER] '{}' schema validation failed for {}: {}", listenerId, recordId,
                        e.getMessage());
                if (cb != null)
                    cb.onFailure();
                return enqueueDeadLetter(message, e)
                        .then(RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId))
                        .doOnTerminate(() -> incrementFailed(listenerId));
            }
        }

        // Resolve argument — most failures here are JSON deserialization errors caused
        // by bad
        // producer payloads; log at WARN (not ERROR) and include the target type +
        // payload
        // preview so the problem is immediately diagnosable without reading raw Redis
        // entries.
        Object arg;
        try {
            arg = resolveArgument(method, message);
        } catch (Exception e) {
            String paramTypeName = method.getParameterCount() > 0
                    ? method.getParameterTypes()[0].getSimpleName()
                    : "unknown";
            String payload = message.getPayload();
            String preview = payload != null
                    ? payload.substring(0, Math.min(200, payload.length()))
                    : "<null>";
            log.warn("[RACER-STREAM-LISTENER] '{}' — failed to deserialize payload to {} for entry {}: {}. "
                    + "Message will be forwarded to DLQ. Payload preview: {}",
                    listenerId, paramTypeName, recordId, e.getMessage(), preview);
            if (cb != null)
                cb.onFailure();
            return enqueueDeadLetter(message, e)
                    .then(RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId))
                    .doOnTerminate(() -> incrementFailed(listenerId));
        }

        final Object resolvedArg = arg;
        final boolean isNoArg = method.getParameterCount() == 0;

        // Interceptor chain → invoke handler
        return buildInterceptorChain(message, listenerId, streamKey, method)
                .flatMap(intercepted -> {
                    // Re-resolve argument if interceptor mutated the message
                    final Object finalArg;
                    if (intercepted != message && !isNoArg) {
                        try {
                            finalArg = resolveArgument(method, intercepted);
                        } catch (Exception e) {
                            log.error(
                                    "[RACER-STREAM-LISTENER] '{}' — cannot resolve argument after intercept for {}: {}",
                                    listenerId, recordId, e.getMessage());
                            if (cb != null)
                                cb.onFailure();
                            return enqueueDeadLetter(intercepted, e)
                                    .then(RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId))
                                    .doOnTerminate(() -> incrementFailed(listenerId));
                        }
                    } else {
                        finalArg = resolvedArg;
                    }

                    Mono<Void> invocation = Mono
                            .fromCallable(() -> isNoArg
                                    ? method.invoke(bean)
                                    : method.invoke(bean, finalArg))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(result -> {
                                if (result instanceof Mono<?> mono)
                                    return mono.then();
                                return Mono.<Void>empty();
                            });

                    return invocation
                            .then(RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId))
                            .doOnSuccess(v -> {
                                incrementProcessed(listenerId);
                                if (cb != null)
                                    cb.onSuccess();
                                racerMetrics.recordConsumed(streamKey, listenerId);
                                log.debug("[RACER-STREAM-LISTENER] '{}' processed entry {}", listenerId, recordId);
                            })
                            .onErrorResume(ex -> {
                                incrementFailed(listenerId);
                                Throwable root = ex.getCause();
                                Throwable cause = root != null ? root : ex;
                                if (cb != null)
                                    cb.onFailure();
                                log.error("[RACER-STREAM-LISTENER] '{}' failed entry {}: {}", listenerId, recordId,
                                        cause.getMessage(), cause);
                                return enqueueDeadLetter(intercepted, cause)
                                        .then(RacerStreamUtils.ackRecord(redisTemplate, streamKey, group, recordId));
                            });
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Builds the ordered interceptor chain around a single message. */
    private Mono<RacerMessage> buildInterceptorChain(RacerMessage message,
            String listenerId, String streamKey, Method method) {
        if (interceptors.isEmpty())
            return Mono.just(message);
        InterceptorContext ctx = new InterceptorContext(listenerId, streamKey, method);
        Mono<RacerMessage> chain = Mono.just(message);
        for (RacerMessageInterceptor interceptor : interceptors) {
            chain = chain.flatMap(msg -> interceptor.intercept(msg, ctx));
        }
        return chain;
    }

    private Mono<Void> ensureGroup(String streamKey, String group) {
        return RacerStreamUtils.ensureGroup(redisTemplate, streamKey, group);
    }

    private Object resolveArgument(Method method, RacerMessage message) throws Exception {
        if (method.getParameterCount() == 0)
            return null;
        Class<?> paramType = method.getParameterTypes()[0];
        if (RacerMessage.class.isAssignableFrom(paramType))
            return message;
        if (String.class.equals(paramType))
            return message.getPayload();
        return objectMapper.readValue(message.getPayload(), paramType);
    }

    private String resolve(String value) {
        if (value == null || value.isEmpty())
            return value == null ? "" : value;
        try {
            return environment.resolvePlaceholders(value);
        } catch (Exception e) {
            return value;
        }
    }

    // ── Test harness API ──────────────────────────────────────────────────────

    /**
     * Returns an unmodifiable view of all registered stream listener registrations,
     * keyed by listener ID. Used by
     * {@link com.cheetah.racer.test.RacerTestHarness}.
     */
    public Map<String, StreamListenerRegistration> getStreamListenerRegistrations() {
        return Map.copyOf(streamListenerRegistrations);
    }

    /**
     * Directly feeds {@code message} into the business-logic pipeline of the stream
     * listener identified by {@code listenerId}, bypassing Redis Streams entirely.
     *
     * <p>
     * The message goes through: circuit-breaker gate → deduplication → schema
     * validation → argument resolution → interceptors → handler invocation → DLQ on
     * error.
     * The Redis XACK step is intentionally skipped — there is no real stream
     * record.
     *
     * @param listenerId the listener ID as configured via
     *                   {@code @RacerStreamListener(id="...")}
     *                   or the auto-generated {@code "<beanName>.<methodName>"}
     *                   fallback
     * @param message    the synthetic message to process
     * @return a {@link Mono} that completes when the pipeline finishes
     * @throws IllegalArgumentException if no stream listener is registered with the
     *                                  given ID
     */
    public Mono<Void> processMessage(String listenerId, RacerMessage message) {
        StreamListenerRegistration reg = streamListenerRegistrations.get(listenerId);
        if (reg == null) {
            return Mono.error(new IllegalArgumentException(
                    "[RACER-STREAM-LISTENER] No stream listener registered with id '" + listenerId
                            + "'. Registered ids: " + streamListenerRegistrations.keySet()));
        }
        return processMessageDirectly(reg.bean(), reg.method(), reg.streamKey(),
                listenerId, message, reg.dedupEnabled());
    }

    /**
     * Directly feeds {@code message} into the stream identified by
     * {@code streamKey} and
     * {@code group}, bypassing Redis Streams.
     *
     * <p>
     * Looks up the first listener registered for the given stream key and group
     * combination.
     *
     * @param streamKey the stream key (e.g. {@code orders:stream})
     * @param group     the consumer group name
     * @param message   the synthetic message to process
     * @return a {@link Mono} that completes when the pipeline finishes
     * @throws IllegalArgumentException if no stream listener is registered for the
     *                                  given stream/group
     */
    public Mono<Void> processMessage(String streamKey, String group, RacerMessage message) {
        // Find the first registration matching the given streamKey and group
        return streamListenerRegistrations.values().stream()
                .filter(r -> r.streamKey().equals(streamKey) && r.group().equals(group))
                .findFirst()
                .map(reg -> processMessageDirectly(reg.bean(), reg.method(), reg.streamKey(),
                        reg.streamKey() + "." + reg.group(), message, reg.dedupEnabled()))
                .orElseGet(() -> Mono.error(new IllegalArgumentException(
                        "[RACER-STREAM-LISTENER] No stream listener registered for stream='"
                                + streamKey + "' group='" + group + "'")));
    }

    /**
     * Executes the business-logic processing pipeline for a stream message without
     * performing a Redis XACK (since there is no real stream record in the test
     * context).
     *
     * <p>
     * Mirrors the logic of {@link #processChecked} but omits all ACK calls so it
     * can be invoked from the test harness without a live Redis connection.
     */
    private Mono<Void> processMessageDirectly(Object bean, Method method, String streamKey,
            String listenerId, RacerMessage message,
            boolean dedupEnabled) {
        incrementInFlight();
        return processNoAck(bean, method, streamKey, listenerId, message, dedupEnabled)
                .doFinally(s -> decrementInFlight());
    }

    /**
     * Performs the full processing pipeline steps (CB gate, dedup, schema,
     * interceptors,
     * handler invocation, DLQ) without any Redis XACK operations.
     */
    private Mono<Void> processNoAck(Object bean, Method method, String streamKey,
            String listenerId, RacerMessage message,
            boolean dedupEnabled) {
        // Circuit breaker gate
        RacerCircuitBreaker cb = getCircuitBreakerRegistry() != null
                ? getCircuitBreakerRegistry().getOrCreate(listenerId)
                : null;
        if (cb != null && !cb.isCallPermitted()) {
            log.debug("[RACER-STREAM-LISTENER] '{}' circuit {} — skipping test message",
                    listenerId, cb.getState());
            return Mono.empty();
        }

        // Dedup check (mirrors the dedup path in processRecord)
        if (dedupEnabled && getDedupService() != null) {
            return getDedupService().checkAndMarkProcessed(message.getId(), listenerId)
                    .flatMap(shouldProcess -> {
                        if (!shouldProcess) {
                            log.debug("[RACER-STREAM-LISTENER] '{}' duplicate id={} — skipped in test",
                                    listenerId, message.getId());
                            return Mono.<Void>empty();
                        }
                        return invokeNoAck(bean, method, streamKey, listenerId, message, cb);
                    });
        }
        return invokeNoAck(bean, method, streamKey, listenerId, message, cb);
    }

    /**
     * Performs schema validation, interceptors, argument resolution, and handler
     * invocation without any Redis XACK calls.
     */
    private Mono<Void> invokeNoAck(Object bean, Method method, String streamKey,
            String listenerId, RacerMessage message,
            @Nullable RacerCircuitBreaker cb) {
        // Schema validation
        if (racerSchemaRegistry != null) {
            try {
                racerSchemaRegistry.validateForConsume(streamKey, message.getPayload());
            } catch (Exception e) {
                log.warn("[RACER-STREAM-LISTENER] '{}' schema validation failed in test: {}", listenerId,
                        e.getMessage());
                if (cb != null)
                    cb.onFailure();
                // .then() converts Mono<?> from enqueueDeadLetter to Mono<Void>
                return enqueueDeadLetter(message, e)
                        .doOnTerminate(() -> incrementFailed(listenerId))
                        .then();
            }
        }

        // Resolve argument
        Object arg;
        try {
            arg = resolveArgument(method, message);
        } catch (Exception e) {
            log.warn("[RACER-STREAM-LISTENER] '{}' argument resolution failed in test: {}", listenerId, e.getMessage());
            if (cb != null)
                cb.onFailure();
            // .then() converts Mono<?> from enqueueDeadLetter to Mono<Void>
            return enqueueDeadLetter(message, e)
                    .doOnTerminate(() -> incrementFailed(listenerId))
                    .then();
        }

        final Object resolvedArg = arg;
        final boolean isNoArg = method.getParameterCount() == 0;

        // Interceptor chain → invoke handler
        return buildInterceptorChain(message, listenerId, streamKey, method)
                .flatMap(intercepted -> {
                    final Object finalArg;
                    if (intercepted != message && !isNoArg) {
                        try {
                            finalArg = resolveArgument(method, intercepted);
                        } catch (Exception e) {
                            if (cb != null)
                                cb.onFailure();
                            // .then() converts Mono<?> to Mono<Void> inside flatMap
                            return enqueueDeadLetter(intercepted, e)
                                    .doOnTerminate(() -> incrementFailed(listenerId))
                                    .then();
                        }
                    } else {
                        finalArg = resolvedArg;
                    }

                    Mono<Void> invocation = Mono
                            .fromCallable(() -> isNoArg ? method.invoke(bean) : method.invoke(bean, finalArg))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(result -> {
                                if (result instanceof Mono<?> mono)
                                    return mono.then();
                                return Mono.<Void>empty();
                            });

                    return invocation
                            .doOnSuccess(v -> {
                                incrementProcessed(listenerId);
                                if (cb != null)
                                    cb.onSuccess();
                                racerMetrics.recordConsumed(streamKey, listenerId);
                            })
                            .onErrorResume(ex -> {
                                incrementFailed(listenerId);
                                Throwable root = ex.getCause();
                                Throwable cause = root != null ? root : ex;
                                if (cb != null)
                                    cb.onFailure();
                                return enqueueDeadLetter(intercepted, cause).then();
                            });
                });
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    @Override
    public long getProcessedCount(String listenerId) {
        return super.getProcessedCount(listenerId);
    }

    @Override
    public long getFailedCount(String listenerId) {
        return super.getFailedCount(listenerId);
    }
}
