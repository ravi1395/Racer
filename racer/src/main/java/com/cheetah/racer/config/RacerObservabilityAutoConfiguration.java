package com.cheetah.racer.config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.tracing.RacerTracingInterceptor;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Flux;

/**
 * Auto-configuration for Racer observability features: Micrometer metrics,
 * the no-op fallback implementation, distributed tracing, and DLQ gauge
 * registration.
 *
 * <p>
 * Activated via {@link com.cheetah.racer.annotation.EnableRacer} or Spring
 * Boot auto-configuration. All beans are conditional — only the features
 * explicitly enabled at runtime are loaded.
 */
@Configuration
@EnableConfigurationProperties(RacerProperties.class)
public class RacerObservabilityAutoConfiguration {

    // ── Micrometer metrics (optional — requires micrometer-core on classpath) ──

    /**
     * Registers the Micrometer-backed {@link RacerMetrics} bean when
     * {@code micrometer-core} is present on the classpath.
     */
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    public RacerMetrics racerMetrics(MeterRegistry meterRegistry) {
        return new RacerMetrics(meterRegistry);
    }

    /**
     * Registers a shared {@link NoOpRacerMetrics} singleton when Micrometer is
     * absent.
     * Ensures all components always receive a non-null {@link RacerMetricsPort}
     * without null-checks or per-component instantiation.
     */
    @Bean
    @ConditionalOnMissingBean(RacerMetricsPort.class)
    public NoOpRacerMetrics racerMetricsNoop() {
        return NoOpRacerMetrics.INSTANCE;
    }

    // ── DLQ size gauge ───────────────────────────────────────────────────────

    /**
     * Registers the {@code racer.dlq.size} Micrometer gauge when Micrometer is
     * present. The gauge value is refreshed every 30 seconds via a non-blocking
     * reactive subscription to avoid calling {@code block()} on the event loop.
     */
    @Bean
    @ConditionalOnBean(RacerMetrics.class)
    public Object racerDlqMetricsRegistration(
            DeadLetterQueueService deadLetterQueueService,
            RacerMetrics racerMetrics) {
        AtomicLong dlqSizeCache = new AtomicLong(0L);
        Flux.interval(Duration.ofSeconds(30))
                .startWith(0L)
                .flatMap(tick -> deadLetterQueueService.size().onErrorReturn(0L))
                .subscribe(dlqSizeCache::set);
        racerMetrics.registerDlqSizeGauge(dlqSizeCache::get);
        return "dlq-metrics-registered";
    }

    // ── Distributed Tracing ──────────────────────────────────────────────────

    /**
     * Activated via {@code racer.tracing.enabled=true}.
     * Propagates W3C {@code traceparent} across all consumed messages and writes
     * the value to MDC for automatic log correlation.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "racer.tracing.enabled", havingValue = "true")
    public RacerTracingInterceptor racerTracingInterceptor(RacerProperties racerProperties) {
        return new RacerTracingInterceptor(racerProperties.getTracing().isPropagateToMdc());
    }
}
