package com.cheetah.racer.circuitbreaker;

import com.cheetah.racer.config.RacerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RacerCircuitBreakerRegistry}.
 */
class RacerCircuitBreakerRegistryTest {

    private RacerCircuitBreakerRegistry registry;

    @BeforeEach
    void setUp() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setEnabled(true);
        props.getCircuitBreaker().setFailureRateThreshold(50.0f);
        props.getCircuitBreaker().setSlidingWindowSize(10);
        props.getCircuitBreaker().setWaitDurationInOpenStateSeconds(30);
        props.getCircuitBreaker().setPermittedCallsInHalfOpenState(3);
        registry = new RacerCircuitBreakerRegistry(props);
    }

    // ── getOrCreate ──────────────────────────────────────────────────────────

    @Test
    void getOrCreate_returnsNonNull() {
        RacerCircuitBreaker cb = registry.getOrCreate("listener-a");
        assertThat(cb).isNotNull();
    }

    @Test
    void getOrCreate_sameId_returnsSameInstance() {
        RacerCircuitBreaker first  = registry.getOrCreate("listener-a");
        RacerCircuitBreaker second = registry.getOrCreate("listener-a");
        assertThat(first).isSameAs(second);
    }

    @Test
    void getOrCreate_differentIds_returnDifferentInstances() {
        RacerCircuitBreaker a = registry.getOrCreate("listener-a");
        RacerCircuitBreaker b = registry.getOrCreate("listener-b");
        assertThat(a).isNotSameAs(b);
    }

    @Test
    void getOrCreate_usesConfiguredWindowSize() {
        RacerProperties props = new RacerProperties();
        props.getCircuitBreaker().setSlidingWindowSize(4);
        props.getCircuitBreaker().setFailureRateThreshold(50.0f);
        props.getCircuitBreaker().setWaitDurationInOpenStateSeconds(30);
        props.getCircuitBreaker().setPermittedCallsInHalfOpenState(2);

        RacerCircuitBreakerRegistry r = new RacerCircuitBreakerRegistry(props);
        RacerCircuitBreaker cb = r.getOrCreate("x");

        // 3 failures out of 4 calls = 75% > 50% threshold → should open
        cb.onFailure(); cb.onFailure(); cb.onFailure(); cb.onSuccess();

        assertThat(cb.getState()).isEqualTo(RacerCircuitBreaker.State.OPEN);
    }

    // ── getAll ───────────────────────────────────────────────────────────────

    @Test
    void getAll_returnsAllRegisteredBreakers() {
        registry.getOrCreate("a");
        registry.getOrCreate("b");
        registry.getOrCreate("c");

        assertThat(registry.getAll())
                .extracting(RacerCircuitBreaker::getName)
                .containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void getAll_whenEmpty_returnsEmpty() {
        assertThat(registry.getAll()).isEmpty();
    }

    // ── name assignment ──────────────────────────────────────────────────────

    @Test
    void createdBreaker_hasCorrectName() {
        RacerCircuitBreaker cb = registry.getOrCreate("my-listener");
        assertThat(cb.getName()).isEqualTo("my-listener");
    }
}
