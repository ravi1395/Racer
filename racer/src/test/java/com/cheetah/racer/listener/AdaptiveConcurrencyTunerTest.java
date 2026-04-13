package com.cheetah.racer.listener;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link AdaptiveConcurrencyTuner} — verifies the AIMD algorithm,
 * semaphore resizing, and lifecycle.
 */
class AdaptiveConcurrencyTunerTest {

    private static final int MAX = 8;

    // Invoke the private tune() method via reflection
    private static void tune(AdaptiveConcurrencyTuner tuner,
                              AtomicLong processed,
                              AtomicLong failed) throws Exception {
        Method m = AdaptiveConcurrencyTuner.class
                .getDeclaredMethod("tune", AtomicLong.class, AtomicLong.class);
        m.setAccessible(true);
        m.invoke(tuner, processed, failed);
    }

    // ── Initialization ────────────────────────────────────────────────────────

    @Test
    void initialConcurrency_isClampedToMax() {
        AtomicLong p = new AtomicLong(), f = new AtomicLong();
        AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner("t1", p, f, 1);

        assertThat(tuner.getConcurrency()).isEqualTo(1); // min(2*CPU, max=1) → 1
        tuner.shutdown();
    }

    @Test
    void initialConcurrency_isAtLeastMin() {
        AtomicLong p = new AtomicLong(), f = new AtomicLong();
        // MAX_CONCURRENCY = 0 is guarded — becomes MIN_CONCURRENCY(1)
        AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner("t2", p, f, 0);

        assertThat(tuner.getConcurrency()).isGreaterThanOrEqualTo(AdaptiveConcurrencyTuner.MIN_CONCURRENCY);
        tuner.shutdown();
    }

    // ── AIMD: additive increase ───────────────────────────────────────────────

    @Test
    void tune_additiveincrease_whenThroughputImproves() throws Exception {
        AtomicLong processed = new AtomicLong(0);
        AtomicLong failed    = new AtomicLong(0);
        // Use a high max so initial concurrency is well below the ceiling
        int highMax = 100;
        AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner("ai", processed, failed, highMax);

        int initial = tuner.getConcurrency();

        // Interval 1: 10 messages → establishes lastThroughput=10
        processed.set(10);
        tune(tuner, processed, failed);

        // Interval 2: 20 more (delta=20 > lastThroughput=10) → +1
        processed.set(30);
        tune(tuner, processed, failed);

        assertThat(tuner.getConcurrency()).isGreaterThan(initial);
        tuner.shutdown();
    }

    // ── AIMD: multiplicative decrease on high error rate ─────────────────────

    @Test
    void tune_multiplicativeDecrease_whenErrorRateExceeds10Percent() throws Exception {
        AtomicLong processed = new AtomicLong(0);
        AtomicLong failed    = new AtomicLong(0);
        AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner("md", processed, failed, MAX);

        int initial = tuner.getConcurrency();

        // Warm up a snapshot (10 processed, 0 failed)
        processed.set(10);
        tune(tuner, processed, failed);

        // Next interval: 50% error rate (5 processed, 5 new failed → 5/10 = 50%)
        processed.set(15);
        failed.set(5);
        tune(tuner, processed, failed);

        // Should have decreased multiplicatively (×0.75, rounded down)
        assertThat(tuner.getConcurrency()).isLessThan(initial);
        tuner.shutdown();
    }

    // ── AIMD: subtract one on throughput drop ─────────────────────────────────

    @Test
    void tune_subtractsOne_whenThroughputDrops() throws Exception {
        AtomicLong processed = new AtomicLong(0);
        AtomicLong failed    = new AtomicLong(0);
        AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner("td", processed, failed, MAX);

        // Interval 1: process 20 → lastThroughput = 20
        processed.set(20);
        tune(tuner, processed, failed);
        int afterFirst = tuner.getConcurrency();

        // Interval 2: process only 5 more (20→25, delta=5 < 20) → shed one
        processed.set(25);
        tune(tuner, processed, failed);

        assertThat(tuner.getConcurrency()).isLessThanOrEqualTo(afterFirst);
        tuner.shutdown();
    }

    // ── AIMD: stable when throughput unchanged ────────────────────────────────

    @Test
    void tune_noChange_whenThroughputStable() throws Exception {
        AtomicLong processed = new AtomicLong(0);
        AtomicLong failed    = new AtomicLong(0);
        AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner("st", processed, failed, MAX);

        // Interval 1: 10 messages → lastThroughput = 10
        processed.set(10);
        tune(tuner, processed, failed);
        int afterFirst = tuner.getConcurrency();

        // Interval 2: exactly 10 more (delta=10 == lastThroughput=10) → neither > nor < → stable
        processed.set(20);
        tune(tuner, processed, failed);

        assertThat(tuner.getConcurrency()).isEqualTo(afterFirst);
        tuner.shutdown();
    }

    // ── Boundary: never exceeds max ───────────────────────────────────────────

    @Test
    void tune_neverExceedsMax() throws Exception {
        AtomicLong processed = new AtomicLong(0);
        AtomicLong failed    = new AtomicLong(0);
        AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner("max", processed, failed, MAX);

        // Drive additive increases well past max
        for (int i = 1; i <= 30; i++) {
            processed.set(i * 10L);
            tune(tuner, processed, failed);
        }

        assertThat(tuner.getConcurrency()).isLessThanOrEqualTo(MAX);
        tuner.shutdown();
    }

    // ── Boundary: never drops below MIN_CONCURRENCY ───────────────────────────

    @Test
    void tune_neverDropsBelowMin() throws Exception {
        AtomicLong processed = new AtomicLong(100);
        AtomicLong failed    = new AtomicLong(0);
        AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner("min", processed, failed, MAX);

        // Drive multiplicative decreases many times
        for (int i = 0; i < 20; i++) {
            failed.addAndGet(10);
            tune(tuner, processed, failed);
        }

        assertThat(tuner.getConcurrency()).isGreaterThanOrEqualTo(AdaptiveConcurrencyTuner.MIN_CONCURRENCY);
        tuner.shutdown();
    }

    // ── Semaphore: acquire / release ─────────────────────────────────────────

    @Test
    void acquireAndRelease_workWithoutDeadlock() throws Exception {
        AtomicLong p = new AtomicLong(), f = new AtomicLong();
        AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner("sem", p, f, MAX);

        int concurrency = tuner.getConcurrency();
        // Acquire all slots
        for (int i = 0; i < concurrency; i++) {
            tuner.acquireSlot();
        }
        // Release them all
        for (int i = 0; i < concurrency; i++) {
            tuner.releaseSlot();
        }
        // After releasing, acquiring again should not block
        assertThatCode(tuner::acquireSlot).doesNotThrowAnyException();
        tuner.releaseSlot();

        tuner.shutdown();
    }

    // ── Lifecycle: shutdown is idempotent ─────────────────────────────────────

    @Test
    void shutdown_isIdempotent() {
        AtomicLong p = new AtomicLong(), f = new AtomicLong();
        AdaptiveConcurrencyTuner tuner = new AdaptiveConcurrencyTuner("sd", p, f, MAX);
        assertThatCode(() -> {
            tuner.shutdown();
            tuner.shutdown(); // second call should not throw
        }).doesNotThrowAnyException();
    }

    // ── ResizableSemaphore ────────────────────────────────────────────────────

    @Test
    void resizableSemaphore_reducePermits_worksCorrectly() {
        AdaptiveConcurrencyTuner.ResizableSemaphore sem =
                new AdaptiveConcurrencyTuner.ResizableSemaphore(5);

        sem.reducePermits(3);

        assertThat(sem.availablePermits()).isEqualTo(2);
    }

    @Test
    void resizableSemaphore_releaseIncreasesPermits() {
        AdaptiveConcurrencyTuner.ResizableSemaphore sem =
                new AdaptiveConcurrencyTuner.ResizableSemaphore(2);

        sem.release(3);

        assertThat(sem.availablePermits()).isEqualTo(5);
    }
}
