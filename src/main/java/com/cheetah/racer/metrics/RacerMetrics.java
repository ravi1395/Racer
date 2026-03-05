package com.cheetah.racer.metrics;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Micrometer-based operational metrics for the Racer framework.
 *
 * <p>Registered as a Spring bean only when {@code io.micrometer.core.instrument.MeterRegistry}
 * is present on the classpath (i.e. when {@code spring-boot-starter-actuator} is a dependency).
 * Services that optionally use metrics should inject {@code Optional<RacerMetrics>} or
 * declare the field with {@code @Autowired(required = false)}.
 *
 * <h3>Exposed metrics</h3>
 * <table>
 * <tr><th>Metric</th><th>Type</th><th>Tags</th></tr>
 * <tr><td>racer.messages.published</td><td>Counter</td><td>channel, transport</td></tr>
 * <tr><td>racer.messages.consumed</td><td>Counter</td><td>channel, mode</td></tr>
 * <tr><td>racer.messages.failed</td><td>Counter</td><td>channel, exception</td></tr>
 * <tr><td>racer.dlq.size</td><td>Gauge</td><td>-</td></tr>
 * <tr><td>racer.dlq.reprocessed</td><td>Counter</td><td>-</td></tr>
 * <tr><td>racer.request.reply.latency</td><td>Timer</td><td>transport</td></tr>
 * </table>
 */
@Slf4j
public class RacerMetrics {

    private final MeterRegistry registry;

    public RacerMetrics(MeterRegistry registry) {
        this.registry = registry;
        log.info("[racer-metrics] Micrometer metrics enabled — {} meter registry",
                registry.getClass().getSimpleName());
    }

    // -----------------------------------------------------------------------
    // Publish metrics
    // -----------------------------------------------------------------------

    /**
     * Increments {@code racer.messages.published}.
     *
     * @param channel   Redis channel name, e.g. {@code racer:orders}
     * @param transport {@code "pubsub"} or {@code "stream"}
     */
    public void recordPublished(String channel, String transport) {
        Counter.builder("racer.messages.published")
                .description("Number of messages published by Racer")
                .tag("channel", channel)
                .tag("transport", transport)
                .register(registry)
                .increment();
    }

    // -----------------------------------------------------------------------
    // Consume metrics
    // -----------------------------------------------------------------------

    /** Increments {@code racer.messages.consumed}. */
    public void recordConsumed(String channel, String mode) {
        Counter.builder("racer.messages.consumed")
                .description("Number of messages successfully consumed")
                .tag("channel", channel)
                .tag("mode", mode)
                .register(registry)
                .increment();
    }

    /** Increments {@code racer.messages.failed}. */
    public void recordFailed(String channel, String exceptionClass) {
        Counter.builder("racer.messages.failed")
                .description("Number of messages that failed processing")
                .tag("channel", channel)
                .tag("exception", exceptionClass)
                .register(registry)
                .increment();
    }

    // -----------------------------------------------------------------------
    // DLQ metrics
    // -----------------------------------------------------------------------

    /** Increments {@code racer.dlq.reprocessed}. */
    public void recordDlqReprocessed() {
        Counter.builder("racer.dlq.reprocessed")
                .description("Number of DLQ messages that were reprocessed")
                .register(registry)
                .increment();
    }

    /**
     * Registers a gauge that tracks DLQ depth by calling {@code sizeSupplier} on each scrape.
     * Should be called once at startup.
     *
     * @param sizeSupplier supplier that returns the current DLQ size
     */
    public void registerDlqSizeGauge(Supplier<Number> sizeSupplier) {
        Gauge.builder("racer.dlq.size", sizeSupplier, s -> s.get().doubleValue())
                .description("Current number of messages in the Dead Letter Queue")
                .register(registry);
    }

    // -----------------------------------------------------------------------
    // Request-reply latency
    // -----------------------------------------------------------------------

    /**
     * Starts a latency timer sample.
     * Call {@link #stopRequestReplyTimer(Timer.Sample, String)} when the reply arrives.
     */
    public Timer.Sample startRequestReplyTimer() {
        return Timer.start(registry);
    }

    /**
     * Stops the sample and records elapsed time to {@code racer.request.reply.latency}.
     *
     * @param sample    the sample returned by {@link #startRequestReplyTimer()}
     * @param transport {@code "pubsub"} or {@code "stream"}
     */
    public void stopRequestReplyTimer(Timer.Sample sample, String transport) {
        sample.stop(Timer.builder("racer.request.reply.latency")
                .description("Round-trip latency for Racer request-reply")
                .tag("transport", transport)
                .register(registry));
    }
}
