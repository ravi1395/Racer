package com.cheetah.racer.circuitbreaker;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.config.RacerProperties.CircuitBreakerProperties.ListenerCircuitBreakerOverride;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link RacerCircuitBreakerRegistry#getOrCreate(String)} applies per-listener
 * threshold overrides independently, so listeners with different criticality levels can have
 * different failure rates before the circuit opens.
 */
class RacerCircuitBreakerRegistryPerListenerTest {

    /**
     * Configures two listeners with different {@code failureRateThreshold} values.
     * After a failure rate of 20% (1 failure in a window of 5):
     * <ul>
     *   <li>{@code order-listener} (threshold=10%) → OPEN</li>
     *   <li>{@code analytics-listener} (threshold=40%) → CLOSED</li>
     * </ul>
     */
    @Test
    void perListenerThreshold_orderListenerOpensBeforeAnalyticsListener() {
        RacerProperties props = buildPropsWithListenerOverrides();
        RacerCircuitBreakerRegistry registry = new RacerCircuitBreakerRegistry(props, new NoOpRacerMetrics());

        RacerCircuitBreaker orderCb     = registry.getOrCreate("order-listener");
        RacerCircuitBreaker analyticsCb = registry.getOrCreate("analytics-listener");

        // Fill the window with 1 failure + 4 successes → 20% failure rate
        orderCb.onFailure();
        orderCb.onSuccess(); orderCb.onSuccess(); orderCb.onSuccess(); orderCb.onSuccess();

        analyticsCb.onFailure();
        analyticsCb.onSuccess(); analyticsCb.onSuccess(); analyticsCb.onSuccess(); analyticsCb.onSuccess();

        // order-listener (threshold=10%) should be OPEN: 20% >= 10%
        assertThat(orderCb.getState())
                .as("order-listener (threshold=10%) should be OPEN at 20% failure rate")
                .isEqualTo(RacerCircuitBreaker.State.OPEN);

        // analytics-listener (threshold=40%) should stay CLOSED: 20% < 40%
        assertThat(analyticsCb.getState())
                .as("analytics-listener (threshold=40%) should remain CLOSED at 20% failure rate")
                .isEqualTo(RacerCircuitBreaker.State.CLOSED);
    }

    /**
     * Confirms that each listener gets a distinct {@link RacerCircuitBreaker} instance,
     * so state transitions on one do not affect the other.
     */
    @Test
    void perListenerThreshold_eachListenerHasItsOwnBreakerInstance() {
        RacerProperties props = buildPropsWithListenerOverrides();
        RacerCircuitBreakerRegistry registry = new RacerCircuitBreakerRegistry(props, new NoOpRacerMetrics());

        RacerCircuitBreaker orderCb     = registry.getOrCreate("order-listener");
        RacerCircuitBreaker analyticsCb = registry.getOrCreate("analytics-listener");

        assertThat(orderCb).isNotSameAs(analyticsCb);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private RacerProperties buildPropsWithListenerOverrides() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setEnabled(true);
        props.getCircuitBreaker().setFailureRateThreshold(50.0f); // global default
        props.getCircuitBreaker().setSlidingWindowSize(5);
        props.getCircuitBreaker().setWaitDurationInOpenStateSeconds(30);
        props.getCircuitBreaker().setPermittedCallsInHalfOpenState(3);

        ListenerCircuitBreakerOverride orderOverride = new ListenerCircuitBreakerOverride();
        orderOverride.setFailureRateThreshold(10.0f);  // stricter than global
        props.getCircuitBreaker().getListeners().put("order-listener", orderOverride);

        ListenerCircuitBreakerOverride analyticsOverride = new ListenerCircuitBreakerOverride();
        analyticsOverride.setFailureRateThreshold(40.0f); // more lenient than global
        props.getCircuitBreaker().getListeners().put("analytics-listener", analyticsOverride);

        return props;
    }
}
