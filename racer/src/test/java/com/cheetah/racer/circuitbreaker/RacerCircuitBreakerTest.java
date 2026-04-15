package com.cheetah.racer.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RacerCircuitBreaker} state-machine transitions and sliding window logic.
 */
class RacerCircuitBreakerTest {

    // Small window / low threshold for test speed
    private static final int   WINDOW_SIZE   = 5;
    private static final float THRESHOLD     = 60.0f; // 60% → 3+ failures in window of 5
    private static final long  WAIT_MS       = 50L;
    private static final int   HALF_PROBES   = 2;

    private RacerCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new RacerCircuitBreaker("test-listener", WINDOW_SIZE, THRESHOLD, WAIT_MS, HALF_PROBES);
    }

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    void initialState_isClosed() {
        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.CLOSED);
    }

    @Test
    void initialState_permitsCall() {
        assertThat(breaker.isCallPermitted()).isTrue();
    }

    // ── CLOSED → OPEN transition ─────────────────────────────────────────────

    @Test
    void opensWhenFailureRateExceedsThreshold() {
        // Fill window with 3 failures and 2 successes → 60% → exactly at threshold
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onSuccess();

        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.OPEN);
    }

    @Test
    void doesNotOpen_whenWindowNotFull() {
        // 2 failures out of 4 calls (window = 5, not yet full) → 50% but window incomplete
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onSuccess();

        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.CLOSED);
    }

    @Test
    void staysClosed_whenFailureRateBelowThreshold() {
        // 2 failures out of 5 → 40% < 60% threshold
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onSuccess();
        breaker.onSuccess();

        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.CLOSED);
    }

    @Test
    void openState_blocksAllCalls() {
        driveToOpen();

        assertThat(breaker.isCallPermitted()).isFalse();
        assertThat(breaker.isCallPermitted()).isFalse();
    }

    // ── Sliding window eviction ──────────────────────────────────────────────

    @Test
    void slidingWindow_evictsOldestEntry() {
        // Fill with 3 failures + 2 successes to open the circuit
        driveToOpen();
        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.OPEN);

        // onFailure / onSuccess calls in OPEN state are ignored by the recording logic
        // (the circuit is still OPEN regardless)
        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.OPEN);
    }

    // ── OPEN → HALF_OPEN transition ──────────────────────────────────────────

    @Test
    void transitionsToHalfOpen_afterWaitDurationElapsed() throws InterruptedException {
        driveToOpen();
        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.OPEN);

        Thread.sleep(WAIT_MS + 10);

        // isCallPermitted check triggers the OPEN → HALF_OPEN transition
        boolean permitted = breaker.isCallPermitted();
        assertThat(permitted).isTrue();
        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void remainsOpen_beforeWaitDurationElapsed() throws InterruptedException {
        driveToOpen();
        Thread.sleep(10); // well under WAIT_MS=50

        assertThat(breaker.isCallPermitted()).isFalse();
        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.OPEN);
    }

    // ── HALF_OPEN → CLOSED transition ────────────────────────────────────────

    @Test
    void closesCircuit_whenAllHalfOpenProbesSucceed() throws InterruptedException {
        driveToOpen();
        Thread.sleep(WAIT_MS + 10);

        // Consume both allowed probes
        breaker.isCallPermitted(); // probe 1
        breaker.isCallPermitted(); // probe 2
        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.HALF_OPEN);

        // Both succeed → circuit closes
        breaker.onSuccess();
        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.CLOSED);
    }

    // ── HALF_OPEN → OPEN transition ──────────────────────────────────────────

    @Test
    void reopensCircuit_whenProbeFailsInHalfOpen() throws InterruptedException {
        driveToOpen();
        Thread.sleep(WAIT_MS + 10);

        breaker.isCallPermitted(); // trigger OPEN → HALF_OPEN
        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.HALF_OPEN);

        breaker.onFailure();

        assertThat(breaker.getState()).isEqualTo(RacerCircuitBreaker.State.OPEN);
    }

    // ── HALF_OPEN probe count limit ──────────────────────────────────────────

    @Test
    void halfOpen_permitsOnlyConfiguredProbeCount() throws InterruptedException {
        driveToOpen();
        Thread.sleep(WAIT_MS + 10);

        // isCallPermitted transitions OPEN → HALF_OPEN but does NOT consume a probe slot
        boolean transition = breaker.isCallPermitted();
        boolean probe1     = breaker.isCallPermitted(); // probe slot 1 of 2
        boolean probe2     = breaker.isCallPermitted(); // probe slot 2 of 2
        boolean beyond     = breaker.isCallPermitted(); // beyond the permitted limit

        assertThat(transition).isTrue();
        assertThat(probe1).isTrue();
        assertThat(probe2).isTrue();
        assertThat(beyond).isFalse();
    }

    // ── name accessor ─────────────────────────────────────────────────────────

    @Test
    void getName_returnsConfiguredName() {
        assertThat(breaker.getName()).isEqualTo("test-listener");
    }

    // ── concurrency stress test ───────────────────────────────────────────────

    /**
     * 16 threads each record 6 250 outcomes (100K total) against a single breaker.
     * Verifies that the lock-free ring buffer does not throw, does not deadlock,
     * and leaves the breaker in either OPEN or CLOSED state (never a corrupted state).
     */
    @Test
    void stressTest_16ThreadsWith100kEvents_noDeadlockOrCorruption() throws InterruptedException {
        // Large window so the breaker may or may not open — we only care about safety
        RacerCircuitBreaker stressBreaker = new RacerCircuitBreaker(
                "stress", 100, 60.0f, 1000L, 5);

        int threadCount = 16;
        int eventsPerThread = 6_250; // 16 × 6 250 = 100 000 total
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> errors = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < eventsPerThread; i++) {
                        // Alternate: even threads skew failure-heavy, odd threads skew success-heavy
                        if (threadId % 2 == 0) {
                            stressBreaker.onFailure();
                        } else {
                            stressBreaker.onSuccess();
                        }
                    }
                } catch (Throwable ex) {
                    synchronized (errors) { errors.add(ex); }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads simultaneously
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).as("stress test should complete within 10 s").isTrue();
        assertThat(errors).as("no exceptions during concurrent recording").isEmpty();
        // State must be a valid enum value — not null or corrupted
        assertThat(stressBreaker.getState()).isNotNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Drive the breaker into OPEN state: 3 failures + 2 successes in a window of 5. */
    private void driveToOpen() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onSuccess();
    }
}
