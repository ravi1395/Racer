package com.cheetah.racer.backpressure;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.listener.RacerListenerRegistrar;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link RacerBackPressureMonitor} recovers the stream poll interval
 * gradually (halving the excess each check cycle) rather than snapping straight back
 * to the original interval when the queue drains.
 *
 * <p>Recovery progression with {@code streamPollBackoffMs=800} and
 * {@code consumer.pollIntervalMs=100}:
 * <pre>
 * Cycle 0 (activation)  : 800 ms
 * Cycle 1 (first relief): 400 ms
 * Cycle 2               : 200 ms
 * Cycle 3               : 100 ms (clamped to originalInterval)
 * Cycle 4               :   0 ms (fully recovered — revert to annotation value)
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerBackPressureMonitorRecoveryTest {

    @Mock RacerListenerRegistrar       listenerRegistrar;
    @Mock RacerStreamListenerRegistrar streamListenerRegistrar;

    private static final int QUEUE_CAPACITY = 10;

    private ThreadPoolExecutor executor;
    private RacerProperties    props;

    @BeforeEach
    void setUp() {
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue);

        props = new RacerProperties();
        props.getThreadPool().setQueueCapacity(QUEUE_CAPACITY);
        props.getBackpressure().setEnabled(true);
        props.getBackpressure().setQueueThreshold(0.80);
        props.getBackpressure().setCheckIntervalMs(1_000);
        props.getBackpressure().setStreamPollBackoffMs(800);
        // Lower original interval so graduated recovery produces a clear multi-step sequence
        props.getConsumer().setPollIntervalMs(100);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /**
     * After activation the stream poll interval must step down in halving increments
     * (800 → 400 → 200 → 100 → 0) across successive below-threshold check cycles.
     * It must never jump directly from 800 to 0.
     */
    @Test
    void streamPollInterval_stepsDownGradually_afterRelief() {
        RacerBackPressureMonitor monitor = buildMonitor();
        ArgumentCaptor<Long> intervalCaptor = ArgumentCaptor.forClass(Long.class);

        // Cycle 0: fill queue above threshold → activation
        fillQueue(9);
        invokeCheckAndApply(monitor);
        assertThat(monitor.isActive()).isTrue();

        // Drain queue; subsequent cycles should produce graduated step-downs
        drainQueue();

        // Cycle 1: RELIEVED + first recovery step (800 → 400)
        invokeCheckAndApply(monitor);
        assertThat(monitor.isActive()).isFalse(); // Pub/Sub released immediately

        // Cycles 2–4: continued stream recovery (400 → 200 → 100 → 0)
        invokeCheckAndApply(monitor); // 400 → 200
        invokeCheckAndApply(monitor); // 200 → 100
        invokeCheckAndApply(monitor); // 100 → 0 (fully recovered)

        verify(streamListenerRegistrar, atLeast(1))
                .setBackPressurePollIntervalMs(intervalCaptor.capture());

        List<Long> recordedIntervals = intervalCaptor.getAllValues();

        // Must start at the full backoff value
        assertThat(recordedIntervals).first().isEqualTo(800L);

        // Must end at 0 (revert to annotation-defined interval)
        assertThat(recordedIntervals).last().isEqualTo(0L);

        // No snap directly from 800 to 0 — there must be intermediate steps
        assertThat(recordedIntervals)
                .as("Recovery must include intermediate steps between 800ms and 0ms")
                .containsSequence(800L, 400L, 200L, 100L, 0L);

        // Verify the exact graduated sequence in order
        assertThat(recordedIntervals).containsExactly(800L, 400L, 200L, 100L, 0L);
    }

    /**
     * Pub/Sub back-pressure ({@code listenerRegistrar.setBackPressureActive(false)}) must
     * be released on the very first below-threshold tick, not deferred until stream recovery
     * is complete.
     */
    @Test
    void pubSub_releasedImmediately_streamRecoveryIsIndependent() {
        RacerBackPressureMonitor monitor = buildMonitor();

        fillQueue(9);
        invokeCheckAndApply(monitor); // activate

        drainQueue();
        invokeCheckAndApply(monitor); // first relief tick

        // Pub/Sub released on this tick
        verify(listenerRegistrar, times(1)).setBackPressureActive(false);

        // Stream is NOT yet fully recovered after one tick (still stepping down)
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(streamListenerRegistrar, atLeast(1)).setBackPressurePollIntervalMs(captor.capture());
        long lastRecorded = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(lastRecorded)
                .as("Stream recovery after one tick should be 400ms, not yet at 0")
                .isEqualTo(400L);
    }

    /**
     * If the queue fills up again during graduated recovery, the monitor must re-activate
     * immediately and reset the stream poll to the full backoff value.
     */
    @Test
    void reactivation_duringRecovery_resetsToFullBackoff() {
        RacerBackPressureMonitor monitor = buildMonitor();

        // Activate
        fillQueue(9);
        invokeCheckAndApply(monitor);

        // One recovery step
        drainQueue();
        invokeCheckAndApply(monitor); // 800 → 400

        // Queue fills again before recovery completes
        fillQueue(9);
        invokeCheckAndApply(monitor); // re-activate → back to 800

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(streamListenerRegistrar, atLeast(1)).setBackPressurePollIntervalMs(captor.capture());

        List<Long> values = captor.getAllValues();
        // Last value must be 800 (full backoff after re-activation)
        assertThat(values).last().isEqualTo(800L);
        assertThat(monitor.isActive()).isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RacerBackPressureMonitor buildMonitor() {
        return new RacerBackPressureMonitor(
                executor, props, listenerRegistrar, streamListenerRegistrar, null);
    }

    private void fillQueue(int count) {
        for (int i = 0; i < count + 1; i++) {
            executor.submit(() -> {
                try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ignored) {}
            });
        }
        awaitQueueSize(count);
    }

    private void drainQueue() {
        executor.getQueue().clear();
    }

    private void awaitQueueSize(int expected) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (executor.getQueue().size() < expected && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }

    private void invokeCheckAndApply(RacerBackPressureMonitor monitor) {
        try {
            var method = RacerBackPressureMonitor.class.getDeclaredMethod("checkAndApply");
            method.setAccessible(true);
            method.invoke(monitor);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke checkAndApply", e);
        }
    }
}
