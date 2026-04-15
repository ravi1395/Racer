package com.cheetah.racer.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.listener.RacerDeadLetterHandler;
import com.cheetah.racer.listener.RacerListenerRegistrar;
import com.cheetah.racer.listener.RacerMessageInterceptor;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.requestreply.RacerResponderRegistrar;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.service.DlqReprocessorService;
import com.cheetah.racer.service.RacerRetentionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Auto-configuration for Racer's listener infrastructure: the dedicated thread
 * pool and scheduler, Pub/Sub listener registrar, Dead Letter Queue, DLQ
 * reprocessor, retention service, and the request-reply responder registrar.
 *
 * <p>
 * {@link RacerListenerRegistrar} is conditional on a
 * {@link ReactiveRedisMessageListenerContainer} bean being present, so
 * publishing-only applications will not incur the listener startup overhead.
 */
@Configuration
@EnableConfigurationProperties(RacerProperties.class)
public class RacerListenerAutoConfiguration {

    // ── Dedicated listener thread pool ───────────────────────────────────────

    /**
     * The Racer-owned thread pool backing all {@code @RacerListener} handler
     * invocations. Pool size is controlled by {@code racer.thread-pool.*}
     * properties.
     * The scheduler's {@code dispose()} will shut down the executor automatically.
     */
    @Bean
    public ThreadPoolExecutor racerListenerExecutor(
            RacerProperties racerProperties,
            RacerMetricsPort racerMetrics) {
        RacerProperties.ThreadPoolProperties tp = racerProperties.getThreadPool();
        AtomicInteger counter = new AtomicInteger(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                tp.getCoreSize(),
                tp.getMaxSize(),
                tp.getKeepAliveSeconds(), TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(tp.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, tp.getThreadNamePrefix() + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                });
        executor.allowCoreThreadTimeOut(false);
        racerMetrics.registerThreadPoolGauges(executor);
        return executor;
    }

    /**
     * A Reactor {@link Scheduler} backed by the Racer-owned thread pool.
     * Disposal shuts down the backing executor.
     */
    @Bean(destroyMethod = "dispose")
    public Scheduler racerListenerScheduler(ThreadPoolExecutor racerListenerExecutor) {
        return Schedulers.fromExecutorService(racerListenerExecutor, "racer-listener");
    }

    // ── @RacerListener registrar ─────────────────────────────────────────────

    /**
     * Registers the {@link RacerListenerRegistrar} that scans for
     * {@code @RacerListener} methods and subscribes them to Redis Pub/Sub channels.
     * Only activated when a {@link ReactiveRedisMessageListenerContainer} bean is
     * present — publishing-only apps are unaffected.
     */
    @Bean
    @ConditionalOnBean(ReactiveRedisMessageListenerContainer.class)
    public RacerListenerRegistrar racerListenerRegistrar(
            ReactiveRedisMessageListenerContainer listenerContainer,
            RacerProperties racerProperties,
            ObjectMapper objectMapper,
            Scheduler racerListenerScheduler,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerMetricsPort racerMetrics,
            Optional<RacerSchemaRegistry> racerSchemaRegistry,
            Optional<RacerRouterService> racerRouterService,
            ObjectProvider<RacerDeadLetterHandler> deadLetterHandler,
            ObjectProvider<RacerDedupService> racerDedupService,
            ObjectProvider<RacerCircuitBreakerRegistry> racerCircuitBreakerRegistry,
            ApplicationContext applicationContext) {
        RacerListenerRegistrar registrar = new RacerListenerRegistrar(
                listenerContainer,
                objectMapper,
                racerProperties,
                racerListenerScheduler,
                reactiveStringRedisTemplate,
                racerMetrics,
                racerSchemaRegistry.orElse(null),
                racerRouterService.orElse(null),
                null);
        registrar.setDeadLetterHandlerProvider(deadLetterHandler);
        registrar.setDedupServiceProvider(racerDedupService);
        registrar.setCircuitBreakerRegistryProvider(racerCircuitBreakerRegistry);
        List<RacerMessageInterceptor> interceptors = new ArrayList<>(
                applicationContext.getBeansOfType(RacerMessageInterceptor.class).values());
        AnnotationAwareOrderComparator.sort(interceptors);
        registrar.setInterceptors(interceptors);
        return registrar;
    }

    // ── Dead Letter Queue ────────────────────────────────────────────────────

    /** Stores failed messages in Redis under the configured DLQ key. */
    @Bean
    public DeadLetterQueueService deadLetterQueueService(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            RacerProperties racerProperties) {
        return new DeadLetterQueueService(reactiveStringRedisTemplate, objectMapper,
                racerProperties.getDlq().getMaxSize());
    }

    /** Reprocesses DLQ messages on demand with configurable retry limits. */
    @Bean
    public DlqReprocessorService dlqReprocessorService(
            DeadLetterQueueService deadLetterQueueService,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper,
            RacerMetricsPort racerMetrics) {
        return new DlqReprocessorService(
                deadLetterQueueService,
                reactiveStringRedisTemplate,
                objectMapper,
                racerMetrics);
    }

    // ── Retention ────────────────────────────────────────────────────────────

    /**
     * Periodically trims Redis Streams and the DLQ to their configured max size.
     */
    @Bean
    public RacerRetentionService racerRetentionService(
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            DeadLetterQueueService deadLetterQueueService,
            ObjectMapper objectMapper,
            RacerProperties racerProperties) {
        return new RacerRetentionService(
                reactiveStringRedisTemplate,
                deadLetterQueueService,
                objectMapper,
                racerProperties.getRetention().getStreamMaxLen(),
                racerProperties.getRetention().getDlqMaxAgeHours(),
                racerProperties);
    }

    // ── Request–Reply responder ──────────────────────────────────────────────

    /**
     * Scans for {@code @RacerResponder} methods and registers them as reply
     * handlers.
     */
    @Bean
    public RacerResponderRegistrar racerResponderRegistrar(
            Optional<ReactiveRedisMessageListenerContainer> listenerContainer,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            RacerProperties racerProperties,
            ObjectMapper objectMapper) {
        return new RacerResponderRegistrar(
                listenerContainer.orElse(null),
                reactiveStringRedisTemplate,
                objectMapper,
                racerProperties);
    }

    // ── Retention scheduling (opt-in) ───────────────────────────────────────

    /**
     * Activates {@code @EnableScheduling} only when
     * {@code racer.retention-enabled=true}.
     * This avoids globally enabling Spring's scheduling infrastructure for all
     * consumers of the library.
     */
    @Configuration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "racer.retention-enabled", havingValue = "true")
    @EnableScheduling
    static class RacerRetentionSchedulingConfiguration {
        // @EnableScheduling activates @Scheduled on RacerRetentionService
    }
}
