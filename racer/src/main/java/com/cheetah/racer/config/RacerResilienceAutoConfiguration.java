package com.cheetah.racer.config;

import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import com.cheetah.racer.backpressure.RacerBackPressureMonitor;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.listener.RacerListenerRegistrar;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;

/**
 * Auto-configuration for Racer resilience features: message deduplication,
 * per-listener circuit breakers, and back-pressure monitoring.
 *
 * <p>
 * Each bean is individually conditional — enable only the features your
 * application requires via {@code racer.*} properties.
 */
@Configuration
@EnableConfigurationProperties(RacerProperties.class)
public class RacerResilienceAutoConfiguration {

    // ── Message deduplication ────────────────────────────────────────────────

    /**
     * Activated via {@code racer.dedup.enabled=true}.
     * Uses Redis {@code SET NX EX} to suppress duplicate message processing within
     * the configured TTL window.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.dedup.enabled", havingValue = "true")
    public RacerDedupService racerDedupService(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerProperties racerProperties,
            RacerMetricsPort racerMetrics) {
        return new RacerDedupService(reactiveStringRedisTemplate, racerProperties, racerMetrics);
    }

    // ── Circuit breaker ──────────────────────────────────────────────────────

    /**
     * Activated via {@code racer.circuit-breaker.enabled=true}.
     * Provides per-listener sliding-window circuit breakers that open after
     * the configured failure-rate threshold is exceeded.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.circuit-breaker.enabled", havingValue = "true")
    public RacerCircuitBreakerRegistry racerCircuitBreakerRegistry(
            RacerProperties racerProperties,
            RacerMetricsPort racerMetrics) {
        return new RacerCircuitBreakerRegistry(racerProperties, racerMetrics);
    }

    // ── Back-pressure monitoring ─────────────────────────────────────────────

    /**
     * Activated via {@code racer.backpressure.enabled=true}.
     * Monitors the listener thread-pool queue fill ratio and throttles message
     * consumption when it exceeds {@code racer.backpressure.queue-threshold}.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.backpressure.enabled", havingValue = "true")
    public RacerBackPressureMonitor racerBackPressureMonitor(
            ThreadPoolExecutor racerListenerExecutor,
            RacerProperties racerProperties,
            Optional<RacerListenerRegistrar> racerListenerRegistrar,
            Optional<RacerStreamListenerRegistrar> racerStreamListenerRegistrar,
            RacerMetricsPort racerMetrics) {
        return new RacerBackPressureMonitor(
                racerListenerExecutor,
                racerProperties,
                racerListenerRegistrar.orElse(null),
                racerStreamListenerRegistrar.orElse(null),
                racerMetrics);
    }
}
