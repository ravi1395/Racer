package com.cheetah.racer.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.listener.RacerDeadLetterHandler;
import com.cheetah.racer.listener.RacerMessageInterceptor;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.stream.RacerConsumerLagMonitor;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Auto-configuration for Racer's Redis Streams consumer infrastructure:
 * the stream-listener registrar (XREADGROUP poll loops) and the consumer-group
 * lag monitor (Micrometer gauges).
 *
 * <p>
 * {@link RacerConsumerLagMonitor} is conditional on both
 * {@code racer.consumer-lag.enabled=true} and a {@link RacerMetrics} bean being
 * present in the context (Micrometer required for lag gauges).
 */
@Configuration
@EnableConfigurationProperties(RacerProperties.class)
public class RacerStreamAutoConfiguration {

    // ── @RacerStreamListener registrar ──────────────────────────────────────

    /**
     * Scans for {@code @RacerStreamListener} methods and registers XREADGROUP
     * consumer-group poll loops.
     */
    @Bean
    public RacerStreamListenerRegistrar racerStreamListenerRegistrar(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerProperties racerProperties,
            ObjectMapper objectMapper,
            RacerMetricsPort racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry,
            ObjectProvider<RacerDeadLetterHandler> deadLetterHandler,
            ObjectProvider<RacerDedupService> racerDedupService,
            ObjectProvider<RacerCircuitBreakerRegistry> racerCircuitBreakerRegistry,
            ObjectProvider<RacerConsumerLagMonitor> consumerLagMonitorProvider,
            ApplicationContext applicationContext) {
        RacerStreamListenerRegistrar registrar = new RacerStreamListenerRegistrar(
                reactiveStringRedisTemplate,
                objectMapper,
                racerProperties,
                racerMetrics,
                racerSchemaRegistry.orElse(null),
                null);
        registrar.setDeadLetterHandlerProvider(deadLetterHandler);
        registrar.setDedupServiceProvider(racerDedupService);
        registrar.setCircuitBreakerRegistryProvider(racerCircuitBreakerRegistry);
        RacerConsumerLagMonitor lagMonitor = consumerLagMonitorProvider.getIfAvailable();
        if (lagMonitor != null) {
            registrar.setConsumerLagMonitor(lagMonitor);
        }
        List<RacerMessageInterceptor> interceptors = new ArrayList<>(
                applicationContext.getBeansOfType(RacerMessageInterceptor.class).values());
        AnnotationAwareOrderComparator.sort(interceptors);
        registrar.setInterceptors(interceptors);
        return registrar;
    }

    // ── Consumer group lag dashboard ─────────────────────────────────────────

    /**
     * Activated via {@code racer.consumer-lag.enabled=true} when Micrometer is
     * present. Periodically publishes {@code XPENDING} lag counts as Micrometer
     * gauges for observability dashboards.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.consumer-lag.enabled", havingValue = "true")
    @ConditionalOnBean(RacerMetrics.class)
    public RacerConsumerLagMonitor racerConsumerLagMonitor(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerMetrics racerMetrics,
            RacerProperties racerProperties,
            Optional<RacerStreamListenerRegistrar> streamRegistrar) {
        RacerConsumerLagMonitor monitor = new RacerConsumerLagMonitor(
                reactiveStringRedisTemplate, racerMetrics, racerProperties);
        // Bootstrap lag tracking from streams already registered by
        // RacerStreamListenerRegistrar
        streamRegistrar.ifPresent(r -> r.getTrackedStreamGroups().forEach(monitor::trackStream));
        return monitor;
    }
}
