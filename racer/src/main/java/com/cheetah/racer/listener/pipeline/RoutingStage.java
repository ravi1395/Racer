package com.cheetah.racer.listener.pipeline;

import java.util.List;

import org.springframework.lang.Nullable;

import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.router.CompiledRouteRule;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.router.RouteDecision;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Pipeline stage that evaluates content-based routing rules.
 *
 * <p>
 * Per-listener rules (from {@code @RacerRoute} on the handler method) are
 * evaluated first.
 * If they return {@link RouteDecision#PASS} the global router is consulted.
 *
 * <p>
 * Routing outcomes:
 * <ul>
 * <li>{@link RouteDecision#PASS} — continue to the next stage.</li>
 * <li>{@link RouteDecision#FORWARDED} — message was re-published; return empty
 * to skip local dispatch.</li>
 * <li>{@link RouteDecision#FORWARDED_AND_PROCESS} — message was re-published
 * but local dispatch continues.</li>
 * <li>{@link RouteDecision#DROPPED} / {@link RouteDecision#DROPPED_TO_DLQ} —
 * return empty or signal error.</li>
 * </ul>
 *
 * <p>
 * Routing failures are non-fatal: errors from the router fall back to
 * {@link RouteDecision#PASS}.
 */
@Slf4j
public final class RoutingStage implements RacerMessageStage {

    private final RacerRouterService router;

    /**
     * Per-listener compiled rules from {@code @RacerRoute}; {@code null} → use
     * global router only.
     */
    @Nullable
    private final List<CompiledRouteRule> listenerRules;

    /**
     * Creates a new routing stage.
     *
     * @param router        the router service; must not be {@code null}
     * @param listenerRules per-listener compiled rules, or {@code null} for
     *                      global-only routing
     */
    public RoutingStage(RacerRouterService router, @Nullable List<CompiledRouteRule> listenerRules) {
        this.router = router;
        this.listenerRules = listenerRules;
    }

    @Override
    public Mono<RacerMessage> execute(RacerMessage msg, RacerListenerContext ctx) {
        Mono<RouteDecision> routeStep;
        if (listenerRules != null) {
            // Evaluate per-listener rules first; if PASS, also run global router
            routeStep = router.evaluate(msg, listenerRules)
                    .flatMap(d -> d == RouteDecision.PASS ? router.route(msg) : Mono.just(d));
        } else {
            routeStep = router.route(msg);
        }

        // Routing failures are non-fatal — fall back to PASS so the message is
        // delivered locally
        return routeStep
                .onErrorReturn(RouteDecision.PASS)
                .flatMap(decision -> switch (decision) {
                    case FORWARDED -> {
                        log.debug("[RACER-LISTENER] '{}' message id={} forwarded — skipping local dispatch",
                                ctx.listenerId(), msg.getId());
                        yield Mono.empty();
                    }
                    case DROPPED -> {
                        log.debug("[RACER-LISTENER] '{}' message id={} dropped by routing rule",
                                ctx.listenerId(), msg.getId());
                        yield Mono.empty();
                    }
                    case DROPPED_TO_DLQ -> {
                        log.debug("[RACER-LISTENER] '{}' message id={} dropped to DLQ by routing rule",
                                ctx.listenerId(), msg.getId());
                        yield Mono.error(new RoutedToDlqException(
                                "Dropped to DLQ by routing rule for listener '" + ctx.listenerId() + "'"));
                    }
                    // PASS and FORWARDED_AND_PROCESS both allow local dispatch
                    default -> Mono.just(msg);
                });
    }

    /**
     * Thrown when a routing rule explicitly drops the message to the DLQ.
     * Caught by {@code RacerListenerRegistrar} to enqueue it to the dead-letter
     * queue.
     */
    public static final class RoutedToDlqException extends RuntimeException {
        public RoutedToDlqException(String message) {
            super(message);
        }
    }
}
