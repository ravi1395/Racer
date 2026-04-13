package com.cheetah.racer.integration;

import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.model.RacerMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Phase 3.2 circuit breaker feature.
 *
 * <p>Uses a small sliding window (4 calls) and a 50% failure threshold so a run of
 * 4 failures reliably opens the circuit.  Each test scenario uses its own channel
 * (and therefore its own {@code listenerId}), giving each a completely independent
 * circuit breaker instance and eliminating inter-test state pollution.
 *
 * <p>Requires a running Redis (provided by {@link RacerIntegrationTestBase}).
 */
@TestPropertySource(properties = {
        "racer.circuit-breaker.enabled=true",
        "racer.circuit-breaker.sliding-window-size=4",
        "racer.circuit-breaker.failure-rate-threshold=50",
        // Long cooldown so the circuit stays OPEN for the duration of the test
        "racer.circuit-breaker.wait-duration-in-open-state-seconds=300",
        "racer.circuit-breaker.permitted-calls-in-half-open-state=2"
})
@Import(CircuitBreakerIntegrationTest.ReceiverConfig.class)
class CircuitBreakerIntegrationTest extends RacerIntegrationTestBase {

    /** Channel for success-delivery tests; circuit breaker ID = {@code "cb-it-normal"}. */
    static final String CHANNEL_NORMAL  = "racer:it:cb:normal";
    /** Channel for failure/open tests; circuit breaker ID = {@code "cb-it-fail"}. */
    static final String CHANNEL_FAILURE = "racer:it:cb:failure";

    // ── Test receiver ────────────────────────────────────────────────────────

    static class TestReceiver {

        // --- normal (success) listener ---
        final AtomicInteger normalCount = new AtomicInteger();
        final AtomicReference<CountDownLatch> normalLatch = new AtomicReference<>();

        @RacerListener(channel = CHANNEL_NORMAL, id = "cb-it-normal")
        public void onNormal(RacerMessage msg) {
            normalCount.incrementAndGet();
            CountDownLatch l = normalLatch.get();
            if (l != null) l.countDown();
        }

        // --- failure listener — always throws so the circuit breaker opens ---
        final AtomicInteger failureCount = new AtomicInteger();
        final AtomicReference<CountDownLatch> failureLatch = new AtomicReference<>();

        @RacerListener(channel = CHANNEL_FAILURE, id = "cb-it-fail")
        public void onAlwaysFails(RacerMessage msg) {
            failureCount.incrementAndGet();
            CountDownLatch l = failureLatch.get();
            if (l != null) l.countDown(); // decrement BEFORE throwing so the latch is reached
            throw new RuntimeException("simulated failure");
        }
    }

    @TestConfiguration
    static class ReceiverConfig {
        @Bean
        TestReceiver cbReceiver() { return new TestReceiver(); }
    }

    @Autowired
    private TestReceiver receiver;

    @Autowired(required = false)
    private RacerCircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void reset() {
        receiver.normalCount.set(0);
        receiver.failureCount.set(0);
        clearDlq();
    }

    @AfterEach
    void afterEach() {
        clearDlq();
    }

    // ── Registry availability ────────────────────────────────────────────────

    @Test
    void circuitBreakerRegistry_isAvailableWhenEnabled() {
        assertThat(circuitBreakerRegistry).isNotNull();
    }

    // ── Normal (CLOSED) operation ────────────────────────────────────────────

    /**
     * All messages published to the normal channel must be delivered while
     * the circuit is CLOSED.
     */
    @Test
    void circuit_closed_deliversAllMessages() throws Exception {
        int count = 3;
        CountDownLatch latch = new CountDownLatch(count);
        receiver.normalLatch.set(latch);

        for (int i = 0; i < count; i++) {
            publishPubSub(CHANNEL_NORMAL, message(CHANNEL_NORMAL, "ok-" + i));
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).as("all %d messages delivered".formatted(count)).isTrue();
        assertThat(receiver.normalCount.get()).isEqualTo(count);
    }

    // ── CLOSED → OPEN transition ─────────────────────────────────────────────

    /**
     * After 4 consecutive failures the sliding window reaches 100% ≥ 50% threshold,
     * opening the circuit.  A subsequent message must be silently dropped (ACK'd
     * without invoking the listener).
     */
    @Test
    void circuit_opensAfterThresholdFailures_thenBlocksMessages() throws Exception {
        // Fill the window with 4 failures — circuit opens at 100% ≥ 50% threshold
        CountDownLatch failLatch = new CountDownLatch(4);
        receiver.failureLatch.set(failLatch);

        for (int i = 0; i < 4; i++) {
            publishPubSub(CHANNEL_FAILURE, message(CHANNEL_FAILURE, "fail-" + i));
        }

        assertThat(failLatch.await(15, TimeUnit.SECONDS)).as("4 failing dispatches").isTrue();

        // Allow the reactive pipeline to invoke cb.onFailure() and evaluate the window
        Thread.sleep(300);

        RacerCircuitBreaker cb = circuitBreakerRegistry.getOrCreate("cb-it-fail");
        assertThat(cb.getState()).as("circuit state after 4 failures").isEqualTo(RacerCircuitBreaker.State.OPEN);

        // One more message — the OPEN circuit must block it (listener never called)
        int countBefore = receiver.failureCount.get();
        CountDownLatch blockedLatch = new CountDownLatch(1);
        receiver.failureLatch.set(blockedLatch);

        publishPubSub(CHANNEL_FAILURE, message(CHANNEL_FAILURE, "should-be-blocked"));

        boolean delivered = blockedLatch.await(2, TimeUnit.SECONDS);
        assertThat(delivered).as("message must be blocked by OPEN circuit").isFalse();
        assertThat(receiver.failureCount.get()).isEqualTo(countBefore);
    }
}
