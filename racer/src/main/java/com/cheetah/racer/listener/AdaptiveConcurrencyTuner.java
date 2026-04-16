package com.cheetah.racer.listener;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;

/**
 * Adaptive concurrency controller for a single {@link RacerListener} running in
 * {@link com.cheetah.racer.annotation.ConcurrencyMode#AUTO} mode.
 *
 * <h3>Algorithm: AIMD (Additive Increase / Multiplicative Decrease)</h3>
 * Every {@value #TUNE_INTERVAL_SECONDS} seconds the tuner samples the processed
 * and
 * failed counters for the listener it owns and applies the following rules:
 * <ol>
 * <li><b>Error rate &gt; 10 %</b> — multiply workers by 0.75 (multiplicative
 * back-off).
 * This reacts quickly to downstream pressure.</li>
 * <li><b>Throughput improved since last interval</b> — add one worker (additive
 * increase),
 * up to {@link #MAX_CONCURRENCY} ({@code 10 × availableProcessors()}).</li>
 * <li><b>Throughput dropped</b> — subtract one worker; the current concurrency
 * is above
 * the system's sweet spot.</li>
 * <li><b>Throughput stable</b> — no change; the system has converged.</li>
 * </ol>
 *
 * <h3>Concurrency enforcement</h3>
 * A {@link ResizableSemaphore} (a thin subclass that exposes the protected
 * {@code reducePermits}) is the actual gate. Each message must acquire a permit
 * before
 * entering dispatch and releases it via {@code doFinally}, regardless of
 * success or failure.
 * The {@code flatMap} upstream uses {@code Integer.MAX_VALUE} so the reactive
 * pipeline
 * never back-pressures; the semaphore is the sole throttle.
 *
 * <h3>Starting concurrency</h3>
 * Begins at {@code 2 × availableProcessors()}, which is a safe midpoint for
 * mixed
 * I/O and CPU workloads. The tuner ramps up by +1 every interval as long as
 * throughput
 * keeps improving, converging to the workload's natural ceiling within a few
 * minutes.
 */
@Slf4j
class AdaptiveConcurrencyTuner {

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    static final int MIN_CONCURRENCY = 1;
    private static final int DEFAULT_MAX = 10 * CPU_COUNT;
    private static final int TUNE_INTERVAL_SECONDS = 10;

    private final int maxConcurrency;

    private final String listenerId;
    private final AtomicInteger targetConcurrency;
    private final ResizableSemaphore semaphore;

    /**
     * Snapshots from the previous tuning interval used to compute delta-throughput.
     */
    private final AtomicLong processedSnapshot = new AtomicLong(0);
    private final AtomicLong failedSnapshot = new AtomicLong(0);
    private long lastThroughput = 0;

    private final ScheduledExecutorService tuneExecutor;
    private volatile ScheduledFuture<?> tuneTask;

    /**
     * Shared scheduler for all tuner instances — avoids a dedicated thread per
     * listener (#27).
     * Single thread is sufficient since tune() is lightweight and executes every 10
     * s.
     */
    private static final ScheduledExecutorService SHARED_TUNER_SCHEDULER;
    static {
        SHARED_TUNER_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "racer-tuner-shared");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    /**
     * Constructor used by tests — ceiling defaults to
     * {@code 10 × availableProcessors()}.
     */
    AdaptiveConcurrencyTuner(String listenerId,
            AtomicLong processedCounter,
            AtomicLong failedCounter) {
        this(listenerId, processedCounter, failedCounter, DEFAULT_MAX);
    }

    /**
     * Production constructor — ceiling is driven by the configured thread-pool max
     * size
     * so the tuner never attempts more parallelism than the pool can provide.
     */
    AdaptiveConcurrencyTuner(String listenerId,
            AtomicLong processedCounter,
            AtomicLong failedCounter,
            int maxConcurrency) {
        this.listenerId = listenerId;
        this.maxConcurrency = Math.max(MIN_CONCURRENCY, maxConcurrency);
        int initial = Math.min(2 * CPU_COUNT, this.maxConcurrency);
        this.targetConcurrency = new AtomicInteger(initial);
        this.semaphore = new ResizableSemaphore(initial);

        tuneExecutor = SHARED_TUNER_SCHEDULER;
        tuneTask = tuneExecutor.scheduleAtFixedRate(
                () -> tune(processedCounter, failedCounter),
                TUNE_INTERVAL_SECONDS, TUNE_INTERVAL_SECONDS, TimeUnit.SECONDS);

        log.info("[RACER-TUNER] '{}' started — initial={} max={} (CPUs={})",
                listenerId, initial, this.maxConcurrency, CPU_COUNT);
    }

    // ── Slot management (called from boundedElastic threads) ─────────────────

    /**
     * Blocks until a worker slot is available. Must be called on a boundedElastic
     * thread.
     */
    void acquireSlot() throws InterruptedException {
        semaphore.acquire();
    }

    /** Releases a previously acquired worker slot. */
    void releaseSlot() {
        semaphore.release();
    }

    /** Returns the current target concurrency (may change at runtime). */
    int getConcurrency() {
        return targetConcurrency.get();
    }

    /** Stops the background tuning loop. Called on application shutdown. */
    void shutdown() {
        if (tuneTask != null) {
            tuneTask.cancel(false);
        }
    }

    // ── AIMD tuning loop ─────────────────────────────────────────────────────

    private void tune(AtomicLong processedCounter, AtomicLong failedCounter) {
        try {
            long nowProcessed = processedCounter.get();
            long nowFailed = failedCounter.get();

            long intervalProcessed = nowProcessed - processedSnapshot.getAndSet(nowProcessed);
            long intervalFailed = nowFailed - failedSnapshot.getAndSet(nowFailed);

            long total = intervalProcessed + intervalFailed;
            double errorRate = total > 0 ? (double) intervalFailed / total : 0.0;

            int prev = targetConcurrency.get();
            int next;

            if (errorRate > 0.10) {
                // Multiplicative decrease — back off fast under pressure
                next = Math.max(MIN_CONCURRENCY, (int) (prev * 0.75));
            } else if (intervalProcessed > lastThroughput) {
                // Additive increase — throughput is still growing, try one more worker
                next = Math.min(maxConcurrency, prev + 1);
            } else if (intervalProcessed < lastThroughput && prev > MIN_CONCURRENCY) {
                // Throughput dropped — shed one worker; we may have over-committed
                next = Math.max(MIN_CONCURRENCY, prev - 1);
            } else {
                next = prev; // stable
            }

            lastThroughput = intervalProcessed;

            if (next != prev) {
                applyConcurrencyChange(prev, next);
                targetConcurrency.set(next);
                log.info("[RACER-TUNER] '{}' {} → {}  throughput={} errors={}%",
                        listenerId, prev, next, intervalProcessed,
                        String.format("%.1f", errorRate * 100));
            } else {
                log.debug("[RACER-TUNER] '{}' stable={}  throughput={} errors={}%",
                        listenerId, prev, intervalProcessed,
                        String.format("%.1f", errorRate * 100));
            }
        } catch (Exception e) {
            log.warn("[RACER-TUNER] '{}' tuning cycle failed: {}", listenerId, e.getMessage());
        }
    }

    private void applyConcurrencyChange(int from, int to) {
        if (to > from) {
            semaphore.release(to - from);
        } else {
            semaphore.reducePermits(from - to);
        }
    }

    // ── Inner helper ─────────────────────────────────────────────────────────

    /**
     * Exposes the {@code protected} {@link Semaphore#reducePermits(int)} method so
     * the
     * tuner can shrink the permit count without draining and re-issuing, which
     * would
     * race with in-flight acquisitions.
     */
    static final class ResizableSemaphore extends Semaphore {
        ResizableSemaphore(int permits) {
            super(permits, true);
        }

        @Override
        public void reducePermits(int reduction) {
            super.reducePermits(reduction);
        }
    }
}
