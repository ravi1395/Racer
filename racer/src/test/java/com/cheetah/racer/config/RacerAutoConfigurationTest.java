package com.cheetah.racer.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;

import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.listener.RacerDeadLetterHandler;
import com.cheetah.racer.listener.RacerListenerRegistrar;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.poll.RacerPollRegistrar;
import com.cheetah.racer.processor.RacerPublisherFieldProcessor;
import com.cheetah.racer.publisher.RacerPipelinedPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.publisher.RacerStreamPublisher;
import com.cheetah.racer.requestreply.RacerResponderRegistrar;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.service.DlqReprocessorService;
import com.cheetah.racer.service.RacerRetentionService;
import com.cheetah.racer.stream.RacerConsumerLagMonitor;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;
import com.cheetah.racer.tracing.RacerTracingInterceptor;
import com.cheetah.racer.tx.RacerTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.scheduler.Scheduler;

/**
 * Tests for {@link RacerAutoConfiguration} bean factory methods (not the
 * validator).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerAutoConfigurationTest {

    @Mock
    ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock
    ReactiveStreamOperations<String, Object, Object> streamOps;
    @Mock
    ApplicationContext applicationContext;
    @Mock
    ReactiveRedisMessageListenerContainer listenerContainer;
    @Mock
    RacerPublisherRegistry publisherRegistry;
    @Mock
    RacerStreamPublisher streamPublisher;
    @Mock
    DeadLetterQueueService dlqService;
    @Mock
    ObjectProvider<RacerDeadLetterHandler> dlqHandlerProvider;
    @Mock
    ObjectProvider<RacerDedupService> dedupServiceProvider;
    @Mock
    ObjectProvider<RacerCircuitBreakerRegistry> cbRegistryProvider;
    @Mock
    ObjectProvider<RacerConsumerLagMonitor> lagMonitorProvider;

    RacerAutoConfiguration config;
    RacerObservabilityAutoConfiguration observabilityConfig;
    RacerPublishingAutoConfiguration publishingConfig;
    RacerListenerAutoConfiguration listenerConfig;
    RacerStreamAutoConfiguration streamConfig;
    RacerResilienceAutoConfiguration resilienceConfig;
    RacerProperties properties;
    ObjectMapper objectMapper;
    ThreadPoolExecutor executor;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        config = new RacerAutoConfiguration();
        observabilityConfig = new RacerObservabilityAutoConfiguration();
        publishingConfig = new RacerPublishingAutoConfiguration();
        listenerConfig = new RacerListenerAutoConfiguration();
        streamConfig = new RacerStreamAutoConfiguration();
        resilienceConfig = new RacerResilienceAutoConfiguration();
        properties = new RacerProperties();
        properties.setChannels(new LinkedHashMap<>());
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        when(redisTemplate.opsForStream()).thenReturn((ReactiveStreamOperations) streamOps);
        when(applicationContext.getBeansWithAnnotation(any())).thenReturn(Map.of());
        when(applicationContext.getBeansOfType(any(Class.class))).thenReturn(Map.of());
    }

    @AfterEach
    void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    // ── racerPublisherFieldProcessor ─────────────────────────────────────────

    @Test
    void racerPublisherFieldProcessor_isNotNull() {
        RacerPublisherFieldProcessor proc = publishingConfig.racerPublisherFieldProcessor();
        assertThat(proc).isNotNull();
    }

    // ── racerMetrics ─────────────────────────────────────────────────────────

    @Test
    void racerMetrics_isNotNull() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RacerMetrics metrics = observabilityConfig.racerMetrics(meterRegistry);
        assertThat(metrics).isNotNull();
    }

    // ── racerStreamPublisher ─────────────────────────────────────────────────

    @Test
    void racerStreamPublisher_isNotNull() {
        RacerStreamPublisher publisher = publishingConfig.racerStreamPublisher(
                redisTemplate, objectMapper, properties);
        assertThat(publisher).isNotNull();
    }

    // ── racerTransaction ─────────────────────────────────────────────────────

    @Test
    void racerTransaction_isNotNull() {
        RacerTransaction tx = publishingConfig.racerTransaction(publisherRegistry, objectMapper, Optional.empty());
        assertThat(tx).isNotNull();
    }

    // ── racerPipelinedPublisher ──────────────────────────────────────────────

    @Test
    void racerPipelinedPublisher_isNotNull() {
        RacerPipelinedPublisher pub = publishingConfig.racerPipelinedPublisher(
                properties, redisTemplate, objectMapper, NoOpRacerMetrics.INSTANCE, Optional.empty());
        assertThat(pub).isNotNull();
    }

    // ── racerPollRegistrar ───────────────────────────────────────────────────

    @Test
    void racerPollRegistrar_isNotNull() {
        RacerPollRegistrar registrar = publishingConfig.racerPollRegistrar(
                publisherRegistry, objectMapper, NoOpRacerMetrics.INSTANCE);
        assertThat(registrar).isNotNull();
    }

    // ── racerListenerExecutor ────────────────────────────────────────────────

    @Test
    void racerListenerExecutor_isNotNull() {
        executor = listenerConfig.racerListenerExecutor(properties, NoOpRacerMetrics.INSTANCE);
        assertThat(executor).isNotNull();
        assertThat(executor.getCorePoolSize()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void racerListenerExecutor_withMetrics_registersGauges() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RacerMetrics metrics = new RacerMetrics(meterRegistry);
        executor = listenerConfig.racerListenerExecutor(properties, metrics);
        assertThat(executor).isNotNull();
    }

    // ── racerListenerScheduler ───────────────────────────────────────────────

    @Test
    void racerListenerScheduler_isNotNull() {
        executor = listenerConfig.racerListenerExecutor(properties, NoOpRacerMetrics.INSTANCE);
        Scheduler scheduler = listenerConfig.racerListenerScheduler(executor);
        assertThat(scheduler).isNotNull();
        scheduler.dispose();
    }

    // ── deadLetterQueueService ───────────────────────────────────────────────

    @Test
    void deadLetterQueueService_isNotNull() {
        DeadLetterQueueService svc = listenerConfig.deadLetterQueueService(
                redisTemplate, objectMapper, properties);
        assertThat(svc).isNotNull();
    }

    // ── dlqReprocessorService ────────────────────────────────────────────────

    @Test
    void dlqReprocessorService_isNotNull() {
        DlqReprocessorService svc = listenerConfig.dlqReprocessorService(
                dlqService, redisTemplate, objectMapper, NoOpRacerMetrics.INSTANCE);
        assertThat(svc).isNotNull();
    }

    // ── racerRetentionService ────────────────────────────────────────────────

    @Test
    void racerRetentionService_isNotNull() {
        RacerRetentionService svc = listenerConfig.racerRetentionService(
                redisTemplate, dlqService, objectMapper, properties);
        assertThat(svc).isNotNull();
    }

    // ── racerDedupService ────────────────────────────────────────────────────

    @Test
    void racerDedupService_isNotNull() {
        RacerDedupService svc = resilienceConfig.racerDedupService(redisTemplate, properties,
                NoOpRacerMetrics.INSTANCE);
        assertThat(svc).isNotNull();
    }

    // ── racerCircuitBreakerRegistry ───────────────────────────────────────────

    @Test
    void racerCircuitBreakerRegistry_isNotNull() {
        RacerCircuitBreakerRegistry registry = resilienceConfig.racerCircuitBreakerRegistry(
                properties, NoOpRacerMetrics.INSTANCE);
        assertThat(registry).isNotNull();
    }

    // ── racerRouterService ────────────────────────────────────────────────────

    @Test
    void racerRouterService_isNotNull() {
        RacerRouterService svc = publishingConfig.racerRouterService(
                applicationContext, publisherRegistry, objectMapper,
                Optional.empty(), NoOpRacerMetrics.INSTANCE);
        assertThat(svc).isNotNull();
    }

    // ── racerResponderRegistrar ───────────────────────────────────────────────

    @Test
    void racerResponderRegistrar_withNullContainer_isNotNull() {
        RacerResponderRegistrar reg = listenerConfig.racerResponderRegistrar(
                Optional.empty(), redisTemplate, properties, objectMapper);
        assertThat(reg).isNotNull();
    }

    @Test
    void racerResponderRegistrar_withListenerContainer_isNotNull() {
        RacerResponderRegistrar reg = listenerConfig.racerResponderRegistrar(
                Optional.of(listenerContainer), redisTemplate, properties, objectMapper);
        assertThat(reg).isNotNull();
    }

    // ── racerTracingInterceptor ───────────────────────────────────────────────

    @Test
    void racerTracingInterceptor_isNotNull() {
        RacerTracingInterceptor interceptor = observabilityConfig.racerTracingInterceptor(properties);
        assertThat(interceptor).isNotNull();
    }

    // ── publishResultMethodValidator ─────────────────────────────────────────

    @Test
    void publishResultMethodValidator_isNotNull() {
        PublishResultMethodValidator v = publishingConfig.publishResultMethodValidator(applicationContext);
        assertThat(v).isNotNull();
    }

    // ── racerStreamListenerRegistrar ──────────────────────────────────────────

    @Test
    void racerStreamListenerRegistrar_isNotNull() {
        RacerStreamListenerRegistrar reg = streamConfig.racerStreamListenerRegistrar(
                redisTemplate, properties, objectMapper,
                NoOpRacerMetrics.INSTANCE, Optional.empty(),
                dlqHandlerProvider, dedupServiceProvider, cbRegistryProvider, lagMonitorProvider,
                applicationContext);
        assertThat(reg).isNotNull();
    }

    // ── racerListenerRegistrar ────────────────────────────────────────────────

    @Test
    void racerListenerRegistrar_isNotNull() {
        executor = listenerConfig.racerListenerExecutor(properties, NoOpRacerMetrics.INSTANCE);
        Scheduler scheduler = listenerConfig.racerListenerScheduler(executor);

        RacerListenerRegistrar reg = listenerConfig.racerListenerRegistrar(
                listenerContainer, properties, objectMapper,
                scheduler, redisTemplate,
                NoOpRacerMetrics.INSTANCE, Optional.empty(), Optional.empty(),
                dlqHandlerProvider, dedupServiceProvider, cbRegistryProvider,
                applicationContext);
        assertThat(reg).isNotNull();
        scheduler.dispose();
    }
}
