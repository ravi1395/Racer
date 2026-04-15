package com.cheetah.racer.listener.pipeline;

import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.model.RacerMessage;

import reactor.core.publisher.Mono;

/**
 * Pipeline stage that checks the per-listener circuit breaker.
 *
 * <p>
 * When the circuit breaker is open (call not permitted) this stage returns
 * {@code Mono.empty()} to silently skip the message. The circuit breaker's own
 * failure counting is performed by {@link InvocationStage}.
 */
public final class CircuitBreakerStage implements RacerMessageStage {

    /** The per-listener circuit breaker instance; never {@code null}. */
    private final RacerCircuitBreaker breaker;

    /**
     * Creates a new circuit-breaker stage.
     *
     * @param breaker the per-listener circuit breaker to consult; must not be
     *                {@code null}
     */
    public CircuitBreakerStage(RacerCircuitBreaker breaker) {
        this.breaker = breaker;
    }

    @Override
    public Mono<RacerMessage> execute(RacerMessage msg, RacerListenerContext ctx) {
        if (!breaker.isCallPermitted()) {
            // Open — silently drop this message so the listener doesn't pile up errors
            return Mono.empty();
        }
        return Mono.just(msg);
    }

    /**
     * Returns the circuit breaker for downstream stages (e.g.
     * {@link InvocationStage})
     * that need to record success/failure outcomes.
     *
     * @return the circuit breaker
     */
    public RacerCircuitBreaker getBreaker() {
        return breaker;
    }
}
