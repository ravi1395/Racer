package com.cheetah.racer.listener;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.annotation.RacerRoute;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.exception.RacerConfigurationException;
import com.cheetah.racer.listener.pipeline.BackPressureStage;
import com.cheetah.racer.listener.pipeline.CircuitBreakerStage;
import com.cheetah.racer.listener.pipeline.DedupStage;
import com.cheetah.racer.listener.pipeline.DispatchPipeline;
import com.cheetah.racer.listener.pipeline.InterceptorStage;
import com.cheetah.racer.listener.pipeline.InvocationStage;
import com.cheetah.racer.listener.pipeline.RacerListenerContext;
import com.cheetah.racer.listener.pipeline.RoutingStage;
import com.cheetah.racer.listener.pipeline.SchemaValidationStage;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.router.CompiledRouteRule;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.stream.RacerStreamUtils;
import com.cheetah.racer.util.RacerChannelResolver;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * {@link BeanPostProcessor} that discovers methods annotated with
 * {@link RacerListener}
 * and auto-subscribes them to their configured Redis Pub/Sub channels at
 * startup.
 *
 * <h3>Processing pipeline (per message)</h3>
 * <ol>
 * <li>Deserialize the raw Redis body into a {@link RacerMessage}.</li>
 * <li>Apply content-based routing via {@link RacerRouterService} (if present) —
 * if a
 * routing rule matches the message is re-published and local dispatch is
 * skipped.</li>
 * <li>Validate the payload against the registered schema via
 * {@link RacerSchemaRegistry}
 * (if schema is enabled) — validation failures are sent to the DLQ.</li>
 * <li>Resolve the method argument from the declared parameter type:
 * <ul>
 * <li>{@link RacerMessage} — passes the full envelope.</li>
 * <li>{@link String} — passes the raw payload string.</li>
 * <li>Any other type {@code T} — deserializes the payload JSON into
 * {@code T}.</li>
 * </ul>
 * </li>
 * <li>Invoke the method on {@code Schedulers.boundedElastic()} (blocking-safe).
 * Both {@code void} and {@code Mono<?>} return types are supported.</li>
 * <li>On success — increment processed counter + record Micrometer metrics (if
 * present).</li>
 * <li>On failure — increment failed counter + enqueue to DLQ (if
 * {@link RacerDeadLetterHandler}
 * is present) + log the error.</li>
 * </ol>
 *
 * <p>
 * Concurrency is controlled per-listener via {@link RacerListener#mode()} and
 * {@link RacerListener#concurrency()}:
 * <ul>
 * <li>{@link ConcurrencyMode#SEQUENTIAL} — effective concurrency is always
 * 1.</li>
 * <li>{@link ConcurrencyMode#CONCURRENT} — effective concurrency is
 * {@code max(1, concurrency)}.</li>
 * </ul>
 *
 * <p>
 * All active subscriptions are disposed cleanly on application shutdown via
 * {@link org.springframework.context.SmartLifecycle}.
 *
 * @see RacerListener
 * @see ConcurrencyMode
 * @see RacerDeadLetterHandler
 */
@Slf4j
public class RacerListenerRegistrar extends AbstractRacerRegistrar {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final Scheduler listenerScheduler;

    /**
     * Used when {@code racer.channels.<alias>.durable=true} to set up XREADGROUP
     * consumers.
     * {@code null} when constructed via the legacy test constructor.
     */
    @Nullable
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    private final RacerMetricsPort racerMetrics;

    @Nullable
    private final RacerSchemaRegistry racerSchemaRegistry;

    @Nullable
    private final RacerRouterService racerRouterService;

    /** Adaptive tuners for AUTO-mode listeners — shut down on application stop. */
    private final Map<String, AdaptiveConcurrencyTuner> tuners = new ConcurrentHashMap<>();

    /**
     * Per-listener schedulers — isolates thread pools so a slow listener cannot
     * starve others.
     */
    private final Map<String, Scheduler> perListenerSchedulers = new ConcurrentHashMap<>();

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

    /** Set by {@link com.cheetah.racer.backpressure.RacerBackPressureMonitor}. */
    private final java.util.concurrent.atomic.AtomicBoolean backPressureActive = new java.util.concurrent.atomic.AtomicBoolean(
            false);

    /**
     * Pre-compiled per-listener routing rules (from method-level
     * {@code @RacerRoute}).
     */
    private final Map<String, List<CompiledRouteRule>> perListenerRules = new ConcurrentHashMap<>();

    /**
     * Immutable {@link DispatchPipeline} instances keyed by listener ID.
     * Built once at registration time; used on every message dispatch.
     */
    private final Map<String, DispatchPipeline> dispatchPipelines = new ConcurrentHashMap<>();

    /**
     * Stores the bean, method, channel and dedup flag for every registered
     * listener.
     * Used by {@link com.cheetah.racer.test.RacerTestHarness} to inject synthetic
     * messages directly into the processing pipeline without Redis.
     */
    private final Map<String, ListenerRegistration> listenerRegistrations = new ConcurrentHashMap<>();

    /**
     * Metadata captured at listener registration time so the processing pipeline
     * can
     * be invoked directly (e.g. from the test harness) without going through Redis.
     *
     * @param bean                the Spring bean that owns the handler method
     * @param method              the {@code @RacerListener}-annotated method
     * @param channel             the resolved Redis channel name
     * @param dedupEnabled        whether deduplication is active for this listener
     * @param payloadReader       pre-compiled {@link ObjectReader} for the primary
     *                            parameter type;
     *                            {@code null} when the primary param is
     *                            {@link RacerMessage} or {@link String}
     * @param isRacerMessageParam {@code true} when the primary parameter type is
     *                            {@link RacerMessage}
     * @param isStringParam       {@code true} when the primary parameter type is
     *                            {@link String}
     */
    public record ListenerRegistration(
            Object bean,
            Method method,
            String channel,
            boolean dedupEnabled,
            @Nullable ObjectReader payloadReader,
            boolean isRacerMessageParam,
            boolean isStringParam) {
    }

    /**
     * Per-listener dedup field names set via {@link RacerListener#dedupKey()}.
     * When present the named field is extracted from the payload JSON and used as
     * the
     * Redis dedup key instead of the auto-generated envelope UUID.
     */
    private final Map<String, String> perListenerDedupKeys = new ConcurrentHashMap<>();

    /**
     * Ordered list of message interceptors applied before every handler invocation.
     */
    private volatile List<RacerMessageInterceptor> interceptors = Collections.emptyList();

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
     * Injects the ordered list of {@link RacerMessageInterceptor} beans.
     * Called by {@link com.cheetah.racer.config.RacerAutoConfiguration} after the
     * application context has collected all interceptor beans.
     */
    public void setInterceptors(List<RacerMessageInterceptor> interceptors) {
        this.interceptors = interceptors != null ? interceptors : Collections.emptyList();
    }

    /**
     * Called by {@link com.cheetah.racer.backpressure.RacerBackPressureMonitor}
     * when the
     * thread-pool queue fill ratio crosses the configured threshold.
     * While {@code active=true} all incoming Pub/Sub messages are silently dropped.
     */
    public void setBackPressureActive(boolean active) {
        boolean previous = backPressureActive.getAndSet(active);
        if (active != previous) {
            log.info("[RACER-LISTENER] Back-pressure {}",
                    active ? "ACTIVATED — dropping pub/sub messages" : "RELIEVED — resuming pub/sub dispatch");
        }
    }

    /**
     * Constructor used by tests and legacy code — falls back to
     * {@code boundedElastic()}.
     */
    public RacerListenerRegistrar(
            ReactiveRedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            RacerProperties racerProperties,
            @Nullable RacerMetricsPort racerMetrics,
            @Nullable RacerSchemaRegistry racerSchemaRegistry,
            @Nullable RacerRouterService racerRouterService,
            @Nullable RacerDeadLetterHandler deadLetterHandler) {
        this(listenerContainer, objectMapper, racerProperties, Schedulers.boundedElastic(),
                null, racerMetrics, racerSchemaRegistry, racerRouterService, deadLetterHandler);
    }

    /** Production constructor — uses the dedicated Racer listener thread pool. */
    public RacerListenerRegistrar(
            ReactiveRedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            RacerProperties racerProperties,
            Scheduler listenerScheduler,
            @Nullable ReactiveRedisTemplate<String, String> redisTemplate,
            @Nullable RacerMetricsPort racerMetrics,
            @Nullable RacerSchemaRegistry racerSchemaRegistry,
            @Nullable RacerRouterService racerRouterService,
            @Nullable RacerDeadLetterHandler deadLetterHandler) {
        super(racerProperties, deadLetterHandler);
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
        this.listenerScheduler = listenerScheduler;
        this.redisTemplate = redisTemplate;
        this.racerMetrics = racerMetrics != null ? racerMetrics : NoOpRacerMetrics.INSTANCE;
        this.racerSchemaRegistry = racerSchemaRegistry;
        this.racerRouterService = racerRouterService;
    }

    @Override
    protected String logPrefix() {
        return "RACER-LISTENER";
    }

    @Override
    protected void additionalDisposeCleanup() {
        tuners.values().forEach(AdaptiveConcurrencyTuner::shutdown);
        perListenerSchedulers.values().forEach(Scheduler::dispose);
        perListenerSchedulers.clear();
    }

    /**
     * Returns {@code true} if the listener method's return type indicates a fully
     * non-blocking (reactive) handler — i.e. it returns {@code Mono} or a
     * supertype.
     *
     * <p>
     * A reactive handler never parks a thread waiting for I/O, so it can safely
     * run on the CPU-optimised parallel scheduler without starving other listeners.
     * Void and all other return types are treated as potentially blocking.
     *
     * @param method the annotated listener method
     * @return {@code true} for {@code Mono<?>} return types, {@code false}
     *         otherwise
     */
    private boolean isReactiveListener(Method method) {
        return Mono.class.isAssignableFrom(method.getReturnType());
    }

    /**
     * Returns (creating on first access) the appropriate scheduler for the given
     * listener,
     * chosen based on the handler method's return type:
     *
     * <ul>
     * <li><b>{@code Mono<?>}</b> — uses {@link Schedulers#parallel()}
     * (CPU-optimised,
     * work-stealing). Non-blocking handlers do not need blocking semantics and the
     * parallel scheduler has lower overhead and fewer context switches.</li>
     * <li><b>{@code void} or any other type</b> — uses a dedicated
     * {@link Schedulers#newBoundedElastic} pool (blocking-safe). Thread count is
     * capped at the listener's effective concurrency so a slow handler cannot
     * monopolise the global pool.</li>
     * </ul>
     *
     * <p>
     * Note: annotate listener methods with {@code Mono<Void>} return type to opt
     * into
     * the more efficient parallel scheduler; use {@code void} for blocking
     * handlers.
     *
     * @param listenerId  unique listener identifier used as the thread-name prefix
     * @param method      the annotated listener method — determines scheduler
     *                    choice
     * @param concurrency effective concurrency (only used for the bounded-elastic
     *                    path)
     * @return the scheduler to use for this listener's invocations
     */
    private Scheduler schedulerForListener(String listenerId, Method method, int concurrency) {
        if (isReactiveListener(method)) {
            // Non-blocking (Mono<?>) handler — the shared parallel scheduler is sufficient
            // and avoids the per-listener bounded-elastic thread-pool allocation overhead.
            return Schedulers.parallel();
        }
        // Blocking (void or other) handler — dedicated bounded-elastic pool so slow
        // handlers
        // cannot starve reactive operators on the shared parallel scheduler.
        return perListenerSchedulers.computeIfAbsent(listenerId, id -> Schedulers.newBoundedElastic(
                Math.max(1, concurrency),
                Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
                "racer-" + id,
                60,
                true));
    }

    // ── BeanPostProcessor ────────────────────────────────────────────────────

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        for (Method method : targetClass.getDeclaredMethods()) {
            RacerListener ann = method.getAnnotation(RacerListener.class);
            if (ann != null) {
                registerListener(bean, method, ann, beanName);
            }
        }
        return bean;
    }

    // ── SmartLifecycle (delegated to AbstractRacerRegistrar) ─────────────────

    // ── Registration ─────────────────────────────────────────────────────────

    private void registerListener(Object bean, Method method, RacerListener ann, String beanName) {
        // --- resolve channel name ---
        String channel = resolve(ann.channel());
        String channelRef = resolve(ann.channelRef());

        // In strict mode, verify that a non-blank channelRef actually maps to a
        // configured alias.
        // This catches typos like @RacerListener(channelRef="ordres") at startup.
        if (!channelRef.isBlank()
                && racerProperties.isStrictChannelValidation()
                && !racerProperties.getChannels().containsKey(channelRef)) {
            throw new RacerConfigurationException(
                    "@RacerListener(channelRef=\"" + channelRef + "\") on "
                            + beanName + "." + method.getName() + "() — alias not found in racer.channels.*. "
                            + "Defined aliases: " + racerProperties.getChannels().keySet() + ".");
        }

        String resolvedChannel = RacerChannelResolver.resolveChannel(
                channel, channelRef, racerProperties, logPrefix());

        if (resolvedChannel.isEmpty()) {
            log.warn("[RACER-LISTENER] {}.{}() has @RacerListener but no channel/channelRef — skipped.",
                    beanName, method.getName());
            return;
        }

        // --- resolve listener id for logging/metrics ---
        String listenerId = resolveListenerId(
                ann.id().isEmpty() ? "" : resolve(ann.id()), beanName, method);

        // --- concurrency + subscription ---
        method.setAccessible(true);

        registerListenerStats(listenerId);

        // Per-listener @RacerRoute on the handler method
        RacerRoute methodRoute = method.getAnnotation(RacerRoute.class);
        if (methodRoute != null && racerRouterService != null) {
            List<CompiledRouteRule> compiled = racerRouterService.compile(methodRoute);
            perListenerRules.put(listenerId, compiled);
            log.info("[RACER-LISTENER] Per-listener route rules for '{}': {} rule(s)",
                    listenerId, compiled.size());
        }

        final boolean dedupEnabled = ann.dedup();

        // NFD-1: Fail fast if dedup=true on the listener but the global dedup service
        // is not enabled — prevents silent dedup no-ops that are hard to diagnose.
        if (dedupEnabled && !racerProperties.getDedup().isEnabled()) {
            throw new RacerConfigurationException(
                    "@RacerListener on " + beanName + "." + method.getName()
                            + "() has dedup=true but racer.dedup.enabled is false. "
                            + "Either set racer.dedup.enabled=true or remove dedup=true from the listener.");
        }

        // Pre-compute per-parameter type flags and a compiled ObjectReader so that
        // the hot dispatch path never pays for repeated type resolution or deserializer
        // lookup (eliminates the per-message JavaType construction overhead at scale).
        boolean isRacerMessageParam = false;
        boolean isStringParam = false;
        ObjectReader payloadReader = null;
        for (Parameter p : method.getParameters()) {
            // Skip @Routed boolean flags — they are not the primary payload parameter.
            if (p.isAnnotationPresent(com.cheetah.racer.annotation.Routed.class))
                continue;
            Class<?> pType = p.getType();
            isRacerMessageParam = RacerMessage.class.isAssignableFrom(pType);
            isStringParam = String.class.equals(pType);
            if (!isRacerMessageParam && !isStringParam) {
                // Use the fully-parameterized type (e.g. List<Foo>) so generic POJOs
                // are deserialized correctly via the pre-compiled reader.
                JavaType javaType = objectMapper.constructType(p.getParameterizedType());
                payloadReader = objectMapper.readerFor(javaType);
            }
            break; // only the first non-@Routed parameter is the payload parameter
        }

        // Store registration metadata so RacerTestHarness can inject synthetic messages
        // directly into the processing pipeline without going through Redis Pub/Sub.
        // Stored after dedupEnabled is resolved so the registration carries the correct
        // flag.
        listenerRegistrations.put(listenerId, new ListenerRegistration(
                bean, method, resolvedChannel, dedupEnabled, payloadReader, isRacerMessageParam, isStringParam));

        // Store dedupKey field name for use in dispatch(); pre-register counter at 0
        if (dedupEnabled) {
            if (!ann.dedupKey().isEmpty()) {
                perListenerDedupKeys.put(listenerId, resolve(ann.dedupKey()));
            }
            racerMetrics.initializeDedupCounter(listenerId);
        }
        RacerProperties.ChannelProperties chProps = channelRef.isBlank()
                ? null
                : racerProperties.getChannels().get(channelRef);
        if (chProps != null && chProps.isDurable()) {
            if (redisTemplate == null) {
                log.warn("[RACER-LISTENER] Channel '{}' is durable but no ReactiveRedisTemplate " +
                        "is available — falling back to Pub/Sub for {}.{}()",
                        channelRef, beanName, method.getName());
            } else {
                registerDurableListener(bean, method, ann, beanName, listenerId,
                        resolvedChannel, channelRef, chProps);
                return;
            }
        }

        // Capture volatile dependencies once at registration — avoids repeated volatile
        // reads
        // in the dispatch hot path (called millions of times per listener
        // subscription).
        final RacerDedupService capturedDedup = dedupEnabled ? getDedupService() : null;
        final RacerCircuitBreakerRegistry capturedCbRegistry = getCircuitBreakerRegistry();
        final List<RacerMessageInterceptor> capturedInterceptors = new ArrayList<>(interceptors);

        // Build the dispatch pipeline at registration time so that conditional
        // feature checks are performed once here, not on every message.
        final DispatchPipeline pipeline = buildPipeline(
                listenerId, bean, method, resolvedChannel,
                dedupEnabled, capturedDedup, capturedCbRegistry, capturedInterceptors,
                false /* wasForwarded — runtime value; routing stage handles this */);
        dispatchPipelines.put(listenerId, pipeline);

        final RacerListenerContext listenerCtx = new RacerListenerContext(listenerId, resolvedChannel, method);

        if (ann.mode() == ConcurrencyMode.AUTO) {
            AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner(
                    listenerId,
                    processedCounts.get(listenerId),
                    failedCounts.get(listenerId),
                    racerProperties.getThreadPool().getMaxSize());
            tuners.put(listenerId, tuner);

            log.info(
                    "[RACER-LISTENER] Registered {}.{}() <- channel '{}' (mode=AUTO, initial-concurrency={}, dedup={})",
                    beanName, method.getName(), resolvedChannel, tuner.getConcurrency(), dedupEnabled);

            // flatMap has no reactive cap (Integer.MAX_VALUE); the semaphore inside
            // AdaptiveConcurrencyTuner is the sole throttle and is dynamically resized.
            final Scheduler scheduler = schedulerForListener(listenerId, method,
                    racerProperties.getThreadPool().getMaxSize());
            Disposable sub = listenerContainer
                    .receive(ChannelTopic.of(resolvedChannel))
                    .flatMap(msg -> Mono.fromCallable(() -> {
                        tuner.acquireSlot();
                        return msg;
                    })
                            .subscribeOn(scheduler)
                            .flatMap(m -> pipelineDispatch(pipeline, listenerCtx, m.getMessage())
                                    .doFinally(signal -> tuner.releaseSlot()))
                            .onErrorResume(ex -> {
                                if (ex instanceof InterruptedException) {
                                    Thread.currentThread().interrupt();
                                }
                                log.warn("[RACER-LISTENER] AUTO '{}' skipping message after error: {}",
                                        listenerId, ex.getMessage());
                                return Mono.empty();
                            }),
                            Integer.MAX_VALUE)
                    .subscribe(
                            v -> {
                            },
                            ex -> log.error("[RACER-LISTENER] Fatal subscription error on listener '{}': {}",
                                    listenerId, ex.getMessage(), ex));

            subscriptions.add(sub);
        } else {
            int effectiveConcurrency = ann.mode() == ConcurrencyMode.SEQUENTIAL
                    ? 1
                    : Math.max(1, ann.concurrency());

            log.info("[RACER-LISTENER] Registered {}.{}() <- channel '{}' (mode={}, concurrency={}, dedup={})",
                    beanName, method.getName(), resolvedChannel,
                    ann.mode(), effectiveConcurrency, dedupEnabled);

            final Scheduler scheduler = schedulerForListener(listenerId, method, effectiveConcurrency);
            Disposable sub = listenerContainer
                    .receive(ChannelTopic.of(resolvedChannel))
                    .flatMap(msg -> Mono.fromCallable(() -> msg)
                            .subscribeOn(scheduler)
                            .flatMap(m -> pipelineDispatch(pipeline, listenerCtx, m.getMessage()))
                            .onErrorResume(ex -> {
                                if (ex instanceof InterruptedException) {
                                    Thread.currentThread().interrupt();
                                }
                                log.warn("[RACER-LISTENER] '{}' skipping message after error: {}",
                                        listenerId, ex.getMessage());
                                return Mono.empty();
                            }),
                            effectiveConcurrency)
                    .subscribe(
                            v -> {
                            },
                            ex -> log.error("[RACER-LISTENER] Fatal subscription error on listener '{}': {}",
                                    listenerId, ex.getMessage(), ex));

            subscriptions.add(sub);
        }
    }

    // ── Durable (stream-backed) listener ────────────────────────────────────

    /**
     * Registers a Redis Stream (XREADGROUP) poll loop for the given listener
     * method.
     * Called when {@code racer.channels.<alias>.durable=true}.
     */
    private void registerDurableListener(Object bean, Method method, RacerListener ann,
            String beanName, String listenerId,
            String resolvedChannel, String channelRef,
            RacerProperties.ChannelProperties chProps) {
        String streamKey = chProps.getStreamKey().isBlank()
                ? resolvedChannel + ":stream"
                : chProps.getStreamKey();
        String group = chProps.getDurableGroup().isBlank()
                ? channelRef + "-group"
                : chProps.getDurableGroup();

        int effectiveConcurrency = ann.mode() == ConcurrencyMode.AUTO ? 4
                : ann.mode() == ConcurrencyMode.SEQUENTIAL ? 1
                        : Math.max(1, ann.concurrency());

        final boolean dedupEnabled = ann.dedup();
        // Use the annotation's pollIntervalMs; fall back to 200 ms if unset or
        // non-positive
        final Duration pollInterval = Duration.ofMillis(ann.pollIntervalMs() > 0 ? ann.pollIntervalMs() : 200);
        final String consumerName = listenerId.replace('.', '-') + "-0";

        // Capture volatile dependencies once at registration time
        final RacerDedupService capturedDedup = dedupEnabled ? getDedupService() : null;
        final RacerCircuitBreakerRegistry capturedCbRegistry = getCircuitBreakerRegistry();
        final List<RacerMessageInterceptor> capturedInterceptors = new ArrayList<>(interceptors);

        // Build pipeline for this durable listener
        final DispatchPipeline durablePipeline = buildPipeline(
                listenerId, bean, method, resolvedChannel,
                dedupEnabled, capturedDedup, capturedCbRegistry, capturedInterceptors, false);
        dispatchPipelines.put(listenerId, durablePipeline);
        final RacerListenerContext durableCtx = new RacerListenerContext(listenerId, resolvedChannel, method);

        final ReactiveRedisTemplate<String, String> template = redisTemplate;

        // Ensure the consumer group exists (ignores BUSYGROUP error if already present)
        RacerStreamUtils.ensureGroup(template, streamKey, group)
                .onErrorResume(e -> Mono.empty())
                .subscribe();

        Disposable sub = Flux.defer(() -> pollOnceDurable(template, streamKey, group, consumerName,
                listenerId, effectiveConcurrency, durablePipeline, durableCtx))
                .repeatWhen(flux -> flux.flatMap(v -> Mono.delay(pollInterval)))
                .onErrorContinue((ex, o) -> log.error("[RACER-LISTENER] Durable '{}' poll error: {}", listenerId,
                        ex.getMessage()))
                .subscribe(
                        v -> {
                        },
                        ex -> log.error("[RACER-LISTENER] Durable '{}' fatal error: {}",
                                listenerId, ex.getMessage(), ex));

        subscriptions.add(sub);
        log.info("[RACER-LISTENER] Registered (DURABLE) {}.{}() ← stream='{}' group='{}' concurrency={}",
                beanName, method.getName(), streamKey, group, effectiveConcurrency);
    }

    private Flux<Void> pollOnceDurable(ReactiveRedisTemplate<String, String> template,
            String streamKey, String group, String consumerName,
            String listenerId, int concurrency,
            DispatchPipeline pipeline, RacerListenerContext ctx) {
        StreamReadOptions readOptions = StreamReadOptions.empty()
                .count(racerProperties.getConsumer().getPollBatchSize());
        Consumer consumer = Consumer.from(group, consumerName);
        StreamOffset<String> offset = StreamOffset.create(streamKey, ReadOffset.lastConsumed());

        // Process all records in the batch concurrently, collecting the RecordId of
        // every
        // successfully processed record. Immediate single-record ACKs are issued only
        // for
        // the two exceptional fast-paths (shutdown gate, malformed record); all other
        // records
        // are ACKed together in a single XACK call at the end — reducing ACK
        // round-trips from
        // N to 1 per poll cycle (50-500ms latency savings depending on batch size and
        // network).
        return template.opsForStream()
                .read(consumer, readOptions, offset)
                .onErrorResume(ex -> {
                    log.debug("[RACER-LISTENER] XREADGROUP on '{}' error: {}", streamKey, ex.getMessage());
                    return Flux.empty();
                })
                .flatMap(record -> {
                    if (isStopping()) {
                        // Immediate ACK on shutdown — do not wait for batch to avoid stalling drain
                        return RacerStreamUtils.ackRecord(template, streamKey, group, record.getId())
                                .then(Mono.<RecordId>empty());
                    }
                    Object raw = record.getValue().get("data");
                    if (raw == null) {
                        log.warn("[RACER-LISTENER] Record {} on '{}' missing 'data' field — acking and skipping",
                                record.getId(), streamKey);
                        // Malformed record: ACK immediately so it is not redelivered indefinitely
                        return RacerStreamUtils.ackRecord(template, streamKey, group, record.getId())
                                .then(Mono.<RecordId>empty());
                    }
                    String rawJson = raw.toString();
                    // On success: return the RecordId so it is collected for batch ACK.
                    // On unexpected error: return empty — record is NOT ACKed and Redis will
                    // redeliver it.
                    return pipelineDispatch(pipeline, ctx, rawJson)
                            .thenReturn(record.getId())
                            .onErrorResume(ex -> Mono.empty());
                }, concurrency)
                .collectList()
                .flatMapMany(successIds -> {
                    if (successIds.isEmpty())
                        return Flux.empty();
                    // Single XACK for all successfully processed record IDs in this poll cycle
                    log.debug("[RACER-LISTENER] '{}' batch-ACKing {} record(s) on '{}'",
                            listenerId, successIds.size(), streamKey);
                    return template.opsForStream()
                            .acknowledge(streamKey, group, successIds.toArray(new RecordId[0]))
                            .doOnSuccess(count -> log.debug(
                                    "[RACER-LISTENER] '{}' XACK confirmed {} record(s) on '{}'",
                                    listenerId, count, streamKey))
                            .then()
                            .flux();
                });
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    /**
     * Builds a {@link DispatchPipeline} for the given listener at registration
     * time.
     *
     * <p>
     * Each conditional feature (back-pressure, circuit breaker, dedup, schema,
     * routing,
     * interceptors) is evaluated here and only wired if present. The resulting
     * pipeline
     * is stored in {@link #dispatchPipelines} so the dispatch hot path performs no
     * conditional logic.
     *
     * @param listenerId           resolved listener identifier
     * @param bean                 the Spring bean owning the handler method
     * @param method               the {@code @RacerListener}-annotated handler
     *                             method
     * @param channel              the resolved Redis channel name
     * @param dedupEnabled         whether deduplication is enabled for this
     *                             listener
     * @param capturedDedup        the dedup service (may be {@code null})
     * @param capturedCbRegistry   the circuit-breaker registry (may be
     *                             {@code null})
     * @param capturedInterceptors ordered interceptors captured at registration
     *                             time
     * @param wasForwarded         initial value for the wasForwarded injection
     *                             (usually {@code false})
     * @return the fully composed {@link DispatchPipeline}
     */
    private DispatchPipeline buildPipeline(
            String listenerId,
            Object bean,
            Method method,
            String channel,
            boolean dedupEnabled,
            @Nullable RacerDedupService capturedDedup,
            @Nullable RacerCircuitBreakerRegistry capturedCbRegistry,
            List<RacerMessageInterceptor> capturedInterceptors,
            boolean wasForwarded) {
        List<com.cheetah.racer.listener.pipeline.RacerMessageStage> stages = new ArrayList<>();

        // Stage 0a: back-pressure gate
        stages.add(new BackPressureStage(backPressureActive, listenerId));

        // Stage 0b: circuit breaker (only when a registry is configured)
        RacerCircuitBreaker cb = capturedCbRegistry != null
                ? capturedCbRegistry.getOrCreate(listenerId)
                : null;
        if (cb != null) {
            stages.add(new CircuitBreakerStage(cb));
        }

        // Stage 1: deduplication (only when enabled + service present)
        if (dedupEnabled && capturedDedup != null) {
            stages.add(new DedupStage(capturedDedup,
                    perListenerDedupKeys.get(listenerId), objectMapper));
        }

        // Stage 2: routing (only when a router is available)
        if (racerRouterService != null) {
            stages.add(new RoutingStage(racerRouterService, perListenerRules.get(listenerId)));
        }

        // Stage 3: interceptors (always added; InterceptorStage is a no-op when the
        // list is empty)
        stages.add(new InterceptorStage(capturedInterceptors));

        // Stage 4: schema validation (only when registry is configured)
        if (racerSchemaRegistry != null) {
            stages.add(new SchemaValidationStage(racerSchemaRegistry));
        }

        // Stage 5: handler invocation
        stages.add(new InvocationStage(bean, method, listenerScheduler,
                perListenerSchedulers, racerMetrics, listenerRegistrations, wasForwarded, cb));

        return new DispatchPipeline(stages);
    }

    /**
     * Executes the pre-built pipeline for a raw JSON message string.
     *
     * <p>
     * Handles the shutdown gate and message deserialization before delegating to
     * the pipeline. DLQ enqueue is triggered for back-pressure and invocation
     * failures.
     *
     * @param pipeline the pre-built pipeline for this listener
     * @param ctx      listener context (listenerId, channel, method)
     * @param rawJson  raw JSON string from Redis
     * @return a {@link Mono} that completes when the pipeline finishes
     */
    private Mono<Void> pipelineDispatch(DispatchPipeline pipeline, RacerListenerContext ctx, String rawJson) {
        if (isStopping()) {
            return Mono.empty();
        }
        return Mono.<Void>defer(() -> {
            RacerMessage message;
            try {
                message = objectMapper.readValue(rawJson, RacerMessage.class);
            } catch (Exception e) {
                log.error("[RACER-LISTENER] '{}' — failed to deserialize message from channel '{}': {}",
                        ctx.listenerId(), ctx.channel(), e.getMessage());
                return Mono.empty();
            }
            log.debug("[RACER-LISTENER] '{}' received message id={}", ctx.listenerId(), message.getId());

            return pipeline.execute(message, ctx)
                    .doOnSuccess(v -> incrementProcessed(ctx.listenerId()))
                    .onErrorResume(ex -> {
                        incrementFailed(ctx.listenerId());
                        if (ex instanceof BackPressureStage.BackPressureActiveException) {
                            racerMetrics.recordBackPressureDrop(ctx.listenerId());
                            log.warn("[RACER-LISTENER] '{}' back-pressure active — routing message to DLQ",
                                    ctx.listenerId());
                        }
                        return enqueueDeadLetter(message, ex).then();
                    });
        })
                .doOnSubscribe(s -> incrementInFlight())
                .doFinally(s -> decrementInFlight());
    }

    // ── Test harness API ──────────────────────────────────────────────────────

    /**
     * Returns the registration for all currently-registered pub/sub listeners.
     * Intended for use by {@link com.cheetah.racer.test.RacerTestHarness} to
     * enumerate
     * registered listener IDs without referencing implementation details.
     *
     * @return unmodifiable view of all listener registrations, keyed by listener ID
     */
    public Map<String, ListenerRegistration> getListenerRegistrations() {
        return Map.copyOf(listenerRegistrations);
    }

    /**
     * Directly feeds {@code message} into the full processing pipeline of the
     * listener
     * identified by {@code listenerId}, bypassing Redis Pub/Sub entirely.
     *
     * <p>
     * The message goes through exactly the same stages as a real Redis-delivered
     * message:
     * back-pressure gate → circuit-breaker gate → deduplication → routing →
     * interceptors → schema validation → argument resolution → handler invocation →
     * DLQ on error.
     *
     * <p>
     * This method is the primary entry point for
     * {@link com.cheetah.racer.test.RacerTestHarness}.
     *
     * @param listenerId the listener ID as configured via
     *                   {@code @RacerListener(id="...")} or
     *                   the auto-generated {@code "<beanName>.<methodName>"}
     *                   fallback
     * @param message    the synthetic message to process
     * @return a {@link Mono} that completes when the pipeline finishes
     * @throws IllegalArgumentException if no listener is registered with the given
     *                                  ID
     */
    public Mono<Void> processMessage(String listenerId, RacerMessage message) {
        ListenerRegistration reg = listenerRegistrations.get(listenerId);
        if (reg == null) {
            return Mono.error(new IllegalArgumentException(
                    "[RACER-LISTENER] No listener registered with id '" + listenerId
                            + "'. Registered ids: " + listenerRegistrations.keySet()));
        }
        try {
            // Serialize the message to JSON so pipelineDispatch() receives the same
            // raw-JSON input it would get from a real Redis message.
            String rawJson = objectMapper.writeValueAsString(message);

            // Use the pre-built pipeline; build on-the-fly if not yet registered
            // (e.g. the harness calls processMessage before registerListener runs).
            DispatchPipeline pipeline = dispatchPipelines.computeIfAbsent(listenerId, id -> {
                RacerDedupService dedupSvc = reg.dedupEnabled() ? getDedupService() : null;
                return buildPipeline(id, reg.bean(), reg.method(), reg.channel(),
                        reg.dedupEnabled(), dedupSvc, getCircuitBreakerRegistry(),
                        new ArrayList<>(interceptors), false);
            });
            RacerListenerContext ctx = new RacerListenerContext(listenerId, reg.channel(), reg.method());
            return pipelineDispatch(pipeline, ctx, rawJson);
        } catch (Exception e) {
            return Mono.error(new IllegalStateException(
                    "[RACER-LISTENER] Failed to serialize message for listener '" + listenerId + "': "
                            + e.getMessage(),
                    e));
        }
    }

    /**
     * Resolves Spring {@code ${...}} placeholders in annotation attribute values.
     */
    private String resolve(String value) {
        if (value == null || value.isEmpty())
            return value == null ? "" : value;
        try {
            return environment.resolvePlaceholders(value);
        } catch (Exception e) {
            return value;
        }
    }
}
