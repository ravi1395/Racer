package com.cheetah.racer.config;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import com.cheetah.racer.aspect.PublishResultAspect;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.poll.RacerPollRegistrar;
import com.cheetah.racer.processor.RacerPublisherFieldProcessor;
import com.cheetah.racer.publisher.RacerConsistentHashRing;
import com.cheetah.racer.publisher.RacerPipelinedPublisher;
import com.cheetah.racer.publisher.RacerPriorityPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.publisher.RacerShardedStreamPublisher;
import com.cheetah.racer.publisher.RacerStreamPublisher;
import com.cheetah.racer.ratelimit.RacerRateLimiter;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.tx.RacerTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Auto-configuration for all Racer publishing features: channel registry,
 * stream publisher, pipelined/sharded/priority publishers, router, schema
 * validation, rate limiting, polling, and the transaction coordinator.
 *
 * <p>
 * Activated via {@link com.cheetah.racer.annotation.EnableRacer} or Spring
 * Boot auto-configuration. Individual publisher variants are gated behind
 * their respective {@code racer.*} properties.
 */
@Configuration
@EnableConfigurationProperties(RacerProperties.class)
public class RacerPublishingAutoConfiguration {

    /**
     * The central registry that maps channel aliases to
     * {@link com.cheetah.racer.publisher.RacerChannelPublisher} instances.
     */
    @Bean
    public RacerPublisherRegistry racerPublisherRegistry(
            RacerProperties racerProperties,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            RacerMetricsPort racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry,
            Optional<RacerRateLimiter> racerRateLimiter) {
        return new RacerPublisherRegistry(
                racerProperties,
                reactiveStringRedisTemplate,
                objectMapper,
                racerMetrics,
                racerSchemaRegistry,
                racerRateLimiter);
    }

    /** AOP advice that intercepts {@code @PublishResult} void methods. */
    @Bean
    public PublishResultAspect publishResultAspect(
            RacerPublisherRegistry racerPublisherRegistry,
            RacerStreamPublisher racerStreamPublisher,
            RacerProperties racerProperties,
            Optional<RacerPriorityPublisher> racerPriorityPublisher) {
        return new PublishResultAspect(racerPublisherRegistry, racerStreamPublisher,
                racerProperties, racerPriorityPublisher.orElse(null));
    }

    /**
     * BeanPostProcessor that injects
     * {@link com.cheetah.racer.publisher.RacerPublisher} fields.
     */
    @Bean
    public RacerPublisherFieldProcessor racerPublisherFieldProcessor() {
        return new RacerPublisherFieldProcessor();
    }

    /**
     * CUX-1: Best-effort startup check that warns when a @PublishResult method
     * exists on a concrete class without self-injection, making silent
     * self-invocation bypass likely.
     */
    @Bean
    public PublishResultSelfInvocationValidator publishResultSelfInvocationValidator() {
        return new PublishResultSelfInvocationValidator();
    }

    /**
     * Durable Redis Streams publisher used by {@code @PublishResult(durable=true)}.
     */
    @Bean
    public RacerStreamPublisher racerStreamPublisher(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            RacerProperties racerProperties) {
        return new RacerStreamPublisher(reactiveStringRedisTemplate, objectMapper,
                racerProperties.getRetention().getStreamMaxLen());
    }

    /** Content-based message router activated by {@code @RacerRoute} beans. */
    @Bean
    public RacerRouterService racerRouterService(
            ApplicationContext applicationContext,
            RacerPublisherRegistry racerPublisherRegistry,
            ObjectMapper objectMapper,
            Optional<RacerPriorityPublisher> racerPriorityPublisher,
            RacerMetricsPort racerMetrics) {
        return new RacerRouterService(applicationContext, racerPublisherRegistry, objectMapper,
                racerPriorityPublisher.orElse(null), racerMetrics);
    }

    /** Atomic multi-channel publisher backed by {@code Flux.concat}. */
    @Bean
    public RacerTransaction racerTransaction(RacerPublisherRegistry racerPublisherRegistry,
            ObjectMapper objectMapper,
            Optional<RacerPipelinedPublisher> pipelinedPublisher) {
        return new RacerTransaction(racerPublisherRegistry, objectMapper, pipelinedPublisher.orElse(null));
    }

    // ── R-9: Pipelined batch publisher ───────────────────────────────────────

    /** Parallel batch publisher that groups messages before flushing to Redis. */
    @Bean
    public RacerPipelinedPublisher racerPipelinedPublisher(
            RacerProperties racerProperties,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            RacerMetricsPort racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry) {
        return new RacerPipelinedPublisher(
                reactiveStringRedisTemplate,
                objectMapper,
                racerProperties.getPipeline().getMaxBatchSize(),
                racerMetrics,
                racerSchemaRegistry.orElse(null));
    }

    // ── R-8: Sharded stream publisher (optional) ─────────────────────────────

    /**
     * Activated via {@code racer.sharding.enabled=true}.
     * Routes messages to shard-addressed streams using consistent hashing.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.sharding.enabled", havingValue = "true")
    public RacerShardedStreamPublisher racerShardedStreamPublisher(
            RacerProperties racerProperties,
            RacerStreamPublisher racerStreamPublisher) {
        RacerProperties.ShardingProperties sharding = racerProperties.getSharding();
        RacerConsistentHashRing hashRing = null;
        if (sharding.isConsistentHashEnabled()) {
            hashRing = new RacerConsistentHashRing(
                    sharding.getShardCount(),
                    sharding.getVirtualNodesPerShard());
        }
        return new RacerShardedStreamPublisher(
                racerStreamPublisher,
                sharding.getShardCount(),
                hashRing,
                sharding.isFailoverEnabled());
    }

    // ── R-10: Priority publisher (optional) ─────────────────────────────────

    /**
     * Activated via {@code racer.priority.enabled=true}.
     * Publishes messages to HIGH/NORMAL/LOW sub-channels.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.priority.enabled", havingValue = "true")
    public RacerPriorityPublisher racerPriorityPublisher(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            Optional<RacerSchemaRegistry> racerSchemaRegistry) {
        return new RacerPriorityPublisher(
                reactiveStringRedisTemplate,
                objectMapper,
                racerSchemaRegistry.orElse(null));
    }

    // ── R-7: Schema registry (optional) ─────────────────────────────────────

    /**
     * Activated via {@code racer.schema.enabled=true}.
     * Validates producer and consumer payloads against JSON Schema definitions.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.schema.enabled", havingValue = "true")
    public RacerSchemaRegistry racerSchemaRegistry(
            RacerProperties racerProperties,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper) {
        return new RacerSchemaRegistry(racerProperties, resourceLoader, objectMapper);
    }

    // ── R-11: Polling registrar ──────────────────────────────────────────────

    /**
     * BeanPostProcessor that scans for {@code @RacerPoll} methods and registers
     * periodic scheduled publishers.
     */
    @Bean
    public RacerPollRegistrar racerPollRegistrar(
            @Lazy RacerPublisherRegistry racerPublisherRegistry,
            ObjectMapper objectMapper,
            RacerMetricsPort racerMetrics) {
        return new RacerPollRegistrar(racerPublisherRegistry, objectMapper, racerMetrics);
    }

    // ── Rate Limiting ────────────────────────────────────────────────────────

    /**
     * Activated via {@code racer.rate-limit.enabled=true}.
     * Redis-backed token-bucket rate limiter injected into every channel publisher.
     */
    @Bean
    @ConditionalOnProperty(name = "racer.rate-limit.enabled", havingValue = "true")
    public RacerRateLimiter racerRateLimiter(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerProperties racerProperties) {
        RacerProperties.RateLimitProperties props = racerProperties.getRateLimit();
        return new RacerRateLimiter(
                reactiveStringRedisTemplate,
                props.getDefaultCapacity(),
                props.getDefaultRefillRate(),
                props.getKeyPrefix(),
                props.getChannels());
    }

    // ── @PublishResult validation ────────────────────────────────────────────

    /**
     * Validates at startup that {@code @PublishResult} is only placed on void
     * methods that return {@code Mono<Void>} or {@code void}.
     */
    @Bean
    public PublishResultMethodValidator publishResultMethodValidator(ApplicationContext applicationContext) {
        return new PublishResultMethodValidator(applicationContext);
    }
}
