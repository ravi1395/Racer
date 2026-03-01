package com.cheetah.racer.common.config;

import com.cheetah.racer.common.aspect.PublishResultAspect;
import com.cheetah.racer.common.metrics.RacerMetrics;
import com.cheetah.racer.common.processor.RacerPublisherFieldProcessor;
import com.cheetah.racer.common.publisher.RacerPublisherRegistry;
import com.cheetah.racer.common.publisher.RacerStreamPublisher;
import com.cheetah.racer.common.router.RacerRouterService;
import com.cheetah.racer.common.tx.RacerTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.util.Optional;

/**
 * Core Racer auto-configuration.
 *
 * <p>Imported by {@link com.cheetah.racer.common.annotation.EnableRacer}.
 * Registers:
 * <ul>
 *   <li>{@link RacerPublisherRegistry} — multi-channel registry driven by
 *       {@code racer.channels.*} properties</li>
 *   <li>{@link PublishResultAspect} — AOP advice for {@code @PublishResult}</li>
 *   <li>{@link RacerPublisherFieldProcessor} — BeanPostProcessor for {@code @RacerPublisher} field injection</li>
 *   <li>{@link RacerMetrics} (conditional) — Micrometer instrumentation, active only when
 *       {@code micrometer-core} is on the classpath</li>
 *   <li>{@link RacerStreamPublisher} — durable stream publishing for {@code @PublishResult(durable=true)}</li>
 *   <li>{@link RacerRouterService} — content-based router scanning {@code @RacerRoute} beans</li>
 *   <li>{@link RacerTransaction} — atomic multi-channel publish via {@code Flux.concat}</li>
 * </ul>
 */
@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(RacerProperties.class)
public class RacerAutoConfiguration {

    @Bean
    public RacerPublisherRegistry racerPublisherRegistry(
            RacerProperties racerProperties,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            Optional<RacerMetrics> racerMetrics) {

        return new RacerPublisherRegistry(
                racerProperties,
                reactiveStringRedisTemplate,
                objectMapper,
                racerMetrics);
    }

    @Bean
    public PublishResultAspect publishResultAspect(
            RacerPublisherRegistry racerPublisherRegistry,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerStreamPublisher racerStreamPublisher) {

        return new PublishResultAspect(racerPublisherRegistry, reactiveStringRedisTemplate, racerStreamPublisher);
    }

    @Bean
    public RacerPublisherFieldProcessor racerPublisherFieldProcessor() {
        return new RacerPublisherFieldProcessor();
    }

    // ── Metrics (optional — requires micrometer-core on classpath) ──────────

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    public RacerMetrics racerMetrics(MeterRegistry meterRegistry) {
        return new RacerMetrics(meterRegistry);
    }

    // ── Durable stream publisher ─────────────────────────────────────────────

    @Bean
    public RacerStreamPublisher racerStreamPublisher(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper) {
        return new RacerStreamPublisher(reactiveStringRedisTemplate, objectMapper);
    }

    // ── Content-based router ─────────────────────────────────────────────────

    @Bean
    public RacerRouterService racerRouterService(
            ApplicationContext applicationContext,
            RacerPublisherRegistry racerPublisherRegistry,
            ObjectMapper objectMapper) {
        return new RacerRouterService(applicationContext, racerPublisherRegistry, objectMapper);
    }

    // ── Transaction support ──────────────────────────────────────────────────

    @Bean
    public RacerTransaction racerTransaction(RacerPublisherRegistry racerPublisherRegistry) {
        return new RacerTransaction(racerPublisherRegistry);
    }
}
