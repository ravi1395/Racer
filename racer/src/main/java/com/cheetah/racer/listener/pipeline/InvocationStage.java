package com.cheetah.racer.listener.pipeline;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

import org.springframework.lang.Nullable;

import com.cheetah.racer.annotation.Routed;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.listener.RacerListenerRegistrar.ListenerRegistration;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.model.RacerMessage;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Pipeline stage that resolves method arguments and invokes the
 * {@code @RacerListener}-annotated handler method.
 *
 * <p>
 * On success: records processed metrics and (if present) notifies the circuit
 * breaker.
 * On failure: records failed metrics, notifies the circuit breaker, and signals
 * an error so the surrounding pipeline can route the message to the DLQ.
 *
 * <p>
 * Several fields are pre-computed at construction time (#17) to avoid
 * per-message reflection overhead:
 * <ul>
 * <li>{@link #methodName} — derived from the listener ID at construction.</li>
 * <li>{@link #parameters} — cached from {@code method.getParameters()}
 * once.</li>
 * </ul>
 */
@Slf4j
public final class InvocationStage implements RacerMessageStage {

    private final Object bean;
    private final Method method;
    private final Scheduler scheduler;
    private final RacerMetricsPort metrics;
    private final Map<String, Scheduler> perListenerSchedulers;
    private final Map<String, ListenerRegistration> listenerRegistrations;

    /** Pre-computed method name for metrics; stable per-listener. */
    private final String methodName;

    /**
     * Pre-cached parameter array — avoids {@code method.getParameters()} allocating
     * per message.
     */
    private final Parameter[] parameters;

    /**
     * Whether this message arrived via a {@code FORWARD_AND_PROCESS} routing
     * decision (used to populate the {@code @Routed boolean} parameter).
     */
    private final boolean wasForwarded;

    /**
     * Circuit breaker for this listener; {@code null} when none is configured.
     */
    @Nullable
    private final RacerCircuitBreaker circuitBreaker;

    /**
     * Creates a new invocation stage.
     *
     * @param listenerId            the resolved listener identifier; used to
     *                              pre-compute {@link #methodName}
     * @param bean                  the Spring bean that owns the handler method
     * @param method                the {@code @RacerListener}-annotated handler
     *                              method
     * @param scheduler             fallback scheduler for blocking handlers
     * @param perListenerSchedulers per-listener dedicated schedulers (keyed by
     *                              listener ID)
     * @param metrics               metrics port for recording processed/failed
     *                              counts
     * @param listenerRegistrations registrations map for pre-compiled
     *                              {@link ListenerRegistration}
     * @param wasForwarded          whether the routing decision was
     *                              {@code FORWARD_AND_PROCESS}
     * @param circuitBreaker        optional per-listener circuit breaker; may be
     *                              {@code null}
     */
    public InvocationStage(
            String listenerId,
            Object bean,
            Method method,
            Scheduler scheduler,
            Map<String, Scheduler> perListenerSchedulers,
            RacerMetricsPort metrics,
            Map<String, ListenerRegistration> listenerRegistrations,
            boolean wasForwarded,
            @Nullable RacerCircuitBreaker circuitBreaker) {
        this.bean = bean;
        this.method = method;
        this.scheduler = scheduler;
        this.perListenerSchedulers = perListenerSchedulers;
        this.metrics = metrics;
        this.listenerRegistrations = listenerRegistrations;
        this.wasForwarded = wasForwarded;
        this.circuitBreaker = circuitBreaker;
        // Pre-compute stable per-listener fields (#17)
        this.methodName = listenerId.contains(".")
                ? listenerId.split("\\.")[1]
                : listenerId;
        this.parameters = method.getParameters();
    }

    @Override
    public Mono<RacerMessage> execute(RacerMessage msg, RacerListenerContext ctx) {
        // Resolve method arguments via the pre-compiled registration (avoids
        // per-message type resolution and deserializer lookup)
        ListenerRegistration reg = listenerRegistrations.get(ctx.listenerId());
        Object[] args;
        try {
            args = resolveArguments(msg, wasForwarded, reg, ctx.parsedPayload());
        } catch (Exception e) {
            String paramType = method.getParameterCount() > 0
                    ? method.getParameterTypes()[0].getSimpleName()
                    : "unknown";
            String payload = msg.getPayload();
            String preview = payload != null ? payload.substring(0, Math.min(200, payload.length())) : "<null>";
            log.warn("[RACER-LISTENER] '{}' — failed to deserialize payload to {} for id={}: {}. "
                    + "Message will be forwarded to DLQ. Payload preview: {}",
                    ctx.listenerId(), paramType, msg.getId(), e.getMessage(), preview);
            if (circuitBreaker != null) {
                circuitBreaker.onFailure();
            }
            return Mono.error(e);
        }

        final Object[] resolvedArgs = args;

        // Dispatch to the appropriate scheduler for this listener
        Scheduler effectiveScheduler = perListenerSchedulers.getOrDefault(ctx.listenerId(), scheduler);

        return Mono.fromCallable(() -> method.invoke(bean, resolvedArgs))
                .subscribeOn(effectiveScheduler)
                .flatMap(result -> {
                    if (result instanceof Mono<?> mono) {
                        return mono.then(Mono.just(msg));
                    }
                    return Mono.just(msg);
                })
                .doOnSuccess(m -> {
                    if (circuitBreaker != null) {
                        circuitBreaker.onSuccess();
                    }
                    metrics.recordConsumed(ctx.channel(), methodName);
                    log.debug("[RACER-LISTENER] '{}' processed id={}", ctx.listenerId(), msg.getId());
                })
                .onErrorMap(ex -> {
                    // Unwrap InvocationTargetException
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (circuitBreaker != null) {
                        circuitBreaker.onFailure();
                    }
                    log.error("[RACER-LISTENER] '{}' failed processing id={}: {}",
                            ctx.listenerId(), msg.getId(), cause.getMessage(), cause);
                    metrics.recordFailed(ctx.channel(), cause.getClass().getSimpleName());
                    return cause;
                });
    }

    /**
     * Resolves the argument array to pass to the handler method.
     * Uses the pre-compiled {@link ListenerRegistration} and cached
     * {@link #parameters} array to skip per-message type resolution.
     *
     * @param message       the message being processed
     * @param forwarded     whether the message was FORWARD_AND_PROCESS routed
     * @param reg           pre-compiled listener registration; may be {@code null}
     * @param parsedPayload pre-parsed JSON node from the context; may be
     *                      {@code null}
     */
    private Object[] resolveArguments(RacerMessage message,
            boolean forwarded, @Nullable ListenerRegistration reg,
            @Nullable com.fasterxml.jackson.databind.JsonNode parsedPayload) throws Exception {
        int count = method.getParameterCount();
        if (count == 0) {
            return new Object[0];
        }
        Object[] args = new Object[count];
        boolean primaryHandled = false;
        for (int i = 0; i < count; i++) {
            // Use the cached parameters array — avoids method.getParameters() allocation
            Parameter param = parameters[i];
            if (param.isAnnotationPresent(Routed.class)) {
                args[i] = forwarded;
            } else if (!primaryHandled) {
                args[i] = resolvePrimary(param.getType(), message, reg, parsedPayload);
                primaryHandled = true;
            } else {
                throw new IllegalArgumentException("Unsupported parameter at index " + i + " in " + method);
            }
        }
        return args;
    }

    /**
     * Resolves the primary payload parameter from the message envelope.
     * Prefers the pre-parsed {@code parsedPayload} tree when available to
     * skip re-parsing the raw JSON string (#6).
     *
     * @param paramType     target parameter type
     * @param message       the message being processed
     * @param reg           pre-compiled registration carrying the ObjectReader
     * @param parsedPayload pre-parsed JSON tree; may be {@code null}
     */
    private Object resolvePrimary(Class<?> paramType, RacerMessage message,
            @Nullable ListenerRegistration reg,
            @Nullable com.fasterxml.jackson.databind.JsonNode parsedPayload) throws Exception {
        if (com.cheetah.racer.model.RacerMessage.class.isAssignableFrom(paramType)) {
            return message;
        }
        if (String.class.equals(paramType)) {
            return message.getPayload();
        }
        if (reg != null && reg.payloadReader() != null) {
            // Use the pre-parsed tree when available — avoids a second JSON string parse
            if (parsedPayload != null) {
                return reg.payloadReader().readValue(parsedPayload);
            }
            return reg.payloadReader().readValue(message.getPayload());
        }
        throw new IllegalStateException(
                "No pre-compiled ObjectReader available for parameter type " + paramType
                        + " — listener must be registered before processMessage() is called");
    }
}
