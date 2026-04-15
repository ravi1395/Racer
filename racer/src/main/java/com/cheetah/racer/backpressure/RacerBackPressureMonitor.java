package com.cheetah.racer.backpressure;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.lang.Nullable;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.listener.RacerListenerRegistrar;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Monitors the Racer listener thread-pool queue and activates back-pressure
 * when it fills up.
 *
 * <h3>Behaviour</h3>
 * <p>
 * On every {@code racer.backpressure.check-interval-ms} tick the monitor
 * inspects the
 * ratio {@code queueSize / queueCapacity}. When that ratio reaches or exceeds
 * {@code racer.backpressure.queue-threshold}:
 * <ol>
 * <li>Pub/Sub dispatch is paused — {@link RacerListenerRegistrar} silently
 * drops incoming
 * Pub/Sub messages until the queue drains. This is safe because Redis Pub/Sub
 * is
 * inherently ephemeral; the broker does not retain undelivered messages.</li>
 * <li>Stream poll intervals are increased to
 * {@code racer.backpressure.stream-poll-backoff-ms} for all
 * {@code @RacerStreamListener} loops, slowing down XREADGROUP reads.</li>
 * </ol>
 * <p>
 * When the fill ratio falls below the threshold both mechanisms are reversed:
 * Pub/Sub resumes and stream poll intervals revert to their annotation-defined
 * values.
 *
 * <p>
 * Activated when {@code racer.backpressure.enabled=true}.
 *
 * <pre>
 * # application.properties
 * racer.backpressure.enabled=true
 * racer.backpressure.queue-threshold=0.80
 * racer.backpressure.check-interval-ms=1000
 * racer.backpressure.stream-poll-backoff-ms=2000
 * </pre>
 */
@Slf4j
public class RacerBackPressureMonitor {

    private final ThreadPoolExecutor executor;
    private final RacerProperties racerProperties;

    @Nullable
    private final RacerListenerRegistrar listenerRegistrar;
    @Nullable
    private final RacerStreamListenerRegistrar streamListenerRegistrar;
    private final RacerMetricsPort racerMetrics;

    private final AtomicBoolean backPressureActive = new AtomicBoolean(false);
    /**
     * Tracks the poll-interval override currently applied to
     * {@link RacerStreamListenerRegistrar}.
     * Used by the graduated-recovery logic so the monitor does not need a getter on
     * the registrar.
     */
    private final AtomicLong currentPollOverrideMs = new AtomicLong(0);
    private volatile Disposable monitorLoop;

    public RacerBackPressureMonitor(
            ThreadPoolExecutor executor,
            RacerProperties racerProperties,
            @Nullable RacerListenerRegistrar listenerRegistrar,
            @Nullable RacerStreamListenerRegistrar streamListenerRegistrar,
            @Nullable RacerMetricsPort racerMetrics) {
        this.executor = executor;
        this.racerProperties = racerProperties;
        this.listenerRegistrar = listenerRegistrar;
        this.streamListenerRegistrar = streamListenerRegistrar;
        this.racerMetrics = racerMetrics != null ? racerMetrics : NoOpRacerMetrics.INSTANCE;
    }

    @PostConstruct
    public void start() {
        RacerProperties.BackPressureProperties bp = racerProperties.getBackpressure();
        log.info("[RACER-BACKPRESSURE] Monitor started — threshold={}% checkInterval={}ms",
                String.format("%.0f", bp.getQueueThreshold() * 100), bp.getCheckIntervalMs());

        // Register a single persistent gauge for the current active/inactive state
        racerMetrics.registerBackPressureActiveGauge(() -> backPressureActive.get() ? 1 : 0);

        monitorLoop = Flux.interval(Duration.ofMillis(bp.getCheckIntervalMs()))
                .subscribe(tick -> checkAndApply(),
                        ex -> log.error("[RACER-BACKPRESSURE] Monitor loop error: {}", ex.getMessage(), ex));
    }

    @PreDestroy
    public void stop() {
        if (monitorLoop != null && !monitorLoop.isDisposed()) {
            monitorLoop.dispose();
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void checkAndApply() {
        int queueSize = executor.getQueue().size();
        int queueCapacity = racerProperties.getThreadPool().getQueueCapacity();
        double fillRatio = queueCapacity > 0
                ? (double) queueSize / queueCapacity
                : 0.0;

        RacerProperties.BackPressureProperties bp = racerProperties.getBackpressure();
        boolean shouldActivate = fillRatio >= bp.getQueueThreshold();
        boolean wasActive = backPressureActive.get();

        if (shouldActivate && !wasActive) {
            backPressureActive.set(true);
            log.warn("[RACER-BACKPRESSURE] ACTIVATED — queue fill {}% (size={}, capacity={}) >= threshold {}%",
                    String.format("%.1f", fillRatio * 100), queueSize, queueCapacity,
                    String.format("%.0f", bp.getQueueThreshold() * 100));

            if (listenerRegistrar != null) {
                listenerRegistrar.setBackPressureActive(true);
            }
            if (streamListenerRegistrar != null) {
                long backoffMs = bp.getStreamPollBackoffMs();
                streamListenerRegistrar.setBackPressurePollIntervalMs(backoffMs);
                currentPollOverrideMs.set(backoffMs);
            }
            racerMetrics.recordBackPressureEvent("active");

        } else if (!shouldActivate && wasActive) {
            // Immediately release Pub/Sub back-pressure; stream poll recovery is graduated
            // below.
            backPressureActive.set(false);
            log.info("[RACER-BACKPRESSURE] RELIEVED — queue fill {}% (size={}, capacity={}) < threshold {}%",
                    String.format("%.1f", fillRatio * 100), queueSize, queueCapacity,
                    String.format("%.0f", bp.getQueueThreshold() * 100));

            if (listenerRegistrar != null) {
                listenerRegistrar.setBackPressureActive(false);
            }
            racerMetrics.recordBackPressureEvent("inactive");
        }

        // Graduated stream poll-interval recovery: runs every below-threshold tick
        // while
        // an override is still active. Independent of the Pub/Sub flag so recovery
        // continues after the flag is cleared.
        if (!shouldActivate && streamListenerRegistrar != null) {
            long originalInterval = racerProperties.getConsumer().getPollIntervalMs();
            long currentOverride = currentPollOverrideMs.get();

            if (currentOverride > originalInterval) {
                // Halve the gap each cycle — avoids a sudden snap-back under intermittent load
                // spikes.
                long newInterval = Math.max(originalInterval, currentOverride / 2);
                streamListenerRegistrar.setBackPressurePollIntervalMs(newInterval);
                currentPollOverrideMs.set(newInterval);
                log.info("[RACER-BACKPRESSURE] RECOVERING — poll interval reduced from {}ms to {}ms (target: {}ms)",
                        currentOverride, newInterval, originalInterval);
            } else if (currentOverride > 0) {
                // Override is at or below the original interval — release completely.
                streamListenerRegistrar.setBackPressurePollIntervalMs(0);
                currentPollOverrideMs.set(0);
                log.info("[RACER-BACKPRESSURE] FULLY RECOVERED — poll interval restored to default ({}ms)",
                        originalInterval);
            }
        }
    }

    /** Returns {@code true} if back-pressure is currently active. */
    public boolean isActive() {
        return backPressureActive.get();
    }
}
