package com.cheetah.racer.circuitbreaker;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.config.RacerProperties.CircuitBreakerProperties.ListenerCircuitBreakerOverride;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that when a per-listener override only sets some fields, the remaining fields
 * fall back to the global {@code racer.circuit-breaker.*} defaults.
 */
class RacerCircuitBreakerRegistryFallbackTest {

    /**
     * A listener with only {@code failureRateThreshold} overridden (set to 20%) and
     * all other fields left null must use the global {@code slidingWindowSize=4}.
     *
     * <p>After exactly 4 calls (window fully populated) with 1 failure (25% rate):
     * the breaker opens because 25% >= 20% threshold. This confirms:
     * <ul>
     *   <li>The threshold override (20%) is applied.</li>
     *   <li>The global {@code slidingWindowSize=4} is used as fallback (window triggers at 4).</li>
     * </ul>
     */
    @Test
    void partialOverride_thresholdApplied_otherFieldsFallBackToGlobal() {
        RacerProperties props = buildPropsWithPartialOverride();
        RacerCircuitBreakerRegistry registry = new RacerCircuitBreakerRegistry(props, new NoOpRacerMetrics());

        RacerCircuitBreaker cb = registry.getOrCreate("special-listener");

        // 1 failure + 3 successes = 25% failure rate across window of 4 (global default)
        cb.onFailure();
        cb.onSuccess(); cb.onSuccess(); cb.onSuccess();

        // threshold=20%, rate=25% → OPEN
        assertThat(cb.getState())
                .as("25% failure rate should exceed 20% threshold and open the circuit")
                .isEqualTo(RacerCircuitBreaker.State.OPEN);
    }

    /**
     * A listener with no override at all must use every global default.
     * With threshold=50% and window=4, two failures out of four (50%) triggers OPEN.
     */
    @Test
    void noOverride_allGlobalDefaultsApplied() {
        RacerProperties props = buildPropsWithPartialOverride();
        RacerCircuitBreakerRegistry registry = new RacerCircuitBreakerRegistry(props, new NoOpRacerMetrics());

        // "default-listener" has no entry in the listeners map
        RacerCircuitBreaker cb = registry.getOrCreate("default-listener");

        // 1 failure + 3 successes = 25% failure rate — should NOT open at global threshold=50%
        cb.onFailure();
        cb.onSuccess(); cb.onSuccess(); cb.onSuccess();

        assertThat(cb.getState())
                .as("25% failure rate should remain below global 50% threshold")
                .isEqualTo(RacerCircuitBreaker.State.CLOSED);
    }

    /**
     * Verifies that the fallback also applies for {@code slidingWindowSize}: a listener
     * that overrides only {@code failureRateThreshold} evaluates the same window size
     * as the global default.
     */
    @Test
    void partialOverride_windowNotYetFull_doesNotOpen() {
        RacerProperties props = buildPropsWithPartialOverride();
        RacerCircuitBreakerRegistry registry = new RacerCircuitBreakerRegistry(props, new NoOpRacerMetrics());

        RacerCircuitBreaker cb = registry.getOrCreate("special-listener");

        // Only 3 calls — window of 4 not yet full, so threshold not evaluated
        cb.onFailure(); cb.onFailure(); cb.onFailure();

        assertThat(cb.getState())
                .as("Window not yet full — circuit should still be CLOSED regardless of failure rate")
                .isEqualTo(RacerCircuitBreaker.State.CLOSED);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds properties where the global window size is 4 and threshold is 50%.
     * "special-listener" only overrides {@code failureRateThreshold} to 20%;
     * all other fields ({@code slidingWindowSize}, {@code waitDurationInOpenStateSeconds},
     * {@code permittedCallsInHalfOpenState}) are left null and must fall back to globals.
     */
    private RacerProperties buildPropsWithPartialOverride() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setEnabled(true);
        props.getCircuitBreaker().setFailureRateThreshold(50.0f);
        props.getCircuitBreaker().setSlidingWindowSize(4);
        props.getCircuitBreaker().setWaitDurationInOpenStateSeconds(30);
        props.getCircuitBreaker().setPermittedCallsInHalfOpenState(3);

        // Override only failureRateThreshold; all other fields remain null (fallback to global)
        ListenerCircuitBreakerOverride override = new ListenerCircuitBreakerOverride();
        override.setFailureRateThreshold(20.0f);
        props.getCircuitBreaker().getListeners().put("special-listener", override);

        return props;
    }
}
