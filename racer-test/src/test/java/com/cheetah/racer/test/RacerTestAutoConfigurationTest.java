package com.cheetah.racer.test;

import com.cheetah.racer.publisher.RacerPublisherRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link RacerTestAutoConfiguration}.
 *
 * <p>Uses {@link RacerTest @RacerTest} to verify that the test auto-configuration
 * boots a complete Spring context without a real Redis connection and registers the
 * expected beans.
 *
 * <h3>Verified behaviours</h3>
 * <ul>
 *   <li>The Spring context loads cleanly without Redis infrastructure.</li>
 *   <li>{@link InMemoryRacerPublisherRegistry} is registered as the {@code @Primary}
 *       {@link RacerPublisherRegistry} bean.</li>
 *   <li>The no-op {@link ReactiveRedisTemplate} stub is a Mockito mock whose reactive
 *       methods return empty publishers.</li>
 *   <li>The no-op {@link ReactiveRedisMessageListenerContainer} stub is a Mockito mock.</li>
 *   <li>Channels declared in {@code application.properties} are registered in the
 *       in-memory registry immediately after context startup.</li>
 *   <li>{@link ConditionalOnMissingBean} guards prevent duplicates — the registry is
 *       an instance of {@link InMemoryRacerPublisherRegistry}, not the production class.</li>
 * </ul>
 */
@RacerTest
class RacerTestAutoConfigurationTest {

    /**
     * Injected by type — the {@code @Primary} qualifier ensures this is the
     * {@link InMemoryRacerPublisherRegistry} rather than the production registry.
     */
    @Autowired
    RacerPublisherRegistry racerPublisherRegistry;

    /**
     * The no-op stub registered by {@link RacerTestAutoConfiguration} when no real
     * Redis template is present.
     */
    @Qualifier("reactiveStringRedisTemplate")
    @Autowired
    ReactiveRedisTemplate<String, String> redisTemplate;

    /**
     * The no-op stub registered by {@link RacerTestAutoConfiguration} when no real
     * listener container is present.
     */
    @Autowired
    ReactiveRedisMessageListenerContainer listenerContainer;

    // ── Context load ──────────────────────────────────────────────────────────

    @Test
    void contextLoads_withoutRealRedis() {
        // If this test method executes, the context loaded successfully — no Redis needed.
        assertThat(racerPublisherRegistry).isNotNull();
    }

    // ── InMemoryRacerPublisherRegistry ────────────────────────────────────────

    @Test
    void primaryBean_isInMemoryRacerPublisherRegistry() {
        assertThat(racerPublisherRegistry).isInstanceOf(InMemoryRacerPublisherRegistry.class);
    }

    @Test
    void inMemoryRegistry_channelsFromPropertiesAreRegistered() {
        // "orders" and "notifications" are declared in test application.properties.
        InMemoryRacerPublisherRegistry reg =
                (InMemoryRacerPublisherRegistry) racerPublisherRegistry;

        assertThat(reg.getTestPublisher("orders")).isNotNull();
        assertThat(reg.getTestPublisher("notifications")).isNotNull();
    }

    @Test
    void inMemoryRegistry_defaultChannelPublisherIsRegistered() {
        InMemoryRacerPublisherRegistry reg =
                (InMemoryRacerPublisherRegistry) racerPublisherRegistry;

        assertThat(reg.getTestPublisher("__default__")).isNotNull();
    }

    // ── No-op ReactiveRedisTemplate ───────────────────────────────────────────

    @Test
    void reactiveRedisTemplate_isNoOpMock() {
        // The stub is created via Mockito.mock() inside RacerTestAutoConfiguration.
        assertThat(Mockito.mockingDetails(redisTemplate).isMock()).isTrue();
    }

    @Test
    void reactiveRedisTemplate_convertAndSend_returnsEmptyMono() {
        // ReactiveEmptyAnswer returns Mono.empty() for Mono-returning methods.
        StepVerifier.create(redisTemplate.convertAndSend("test-channel", "payload"))
                .verifyComplete();
    }

    // ── No-op ReactiveRedisMessageListenerContainer ────────────────────────────

    @Test
    void listenerContainer_isNoOpMock() {
        assertThat(Mockito.mockingDetails(listenerContainer).isMock()).isTrue();
    }

    // ── @ConditionalOnMissingBean guard ───────────────────────────────────────

    @Test
    void primaryBean_notInstanceOfProductionRegistry() {
        // The production class is com.cheetah.racer.publisher.RacerPublisherRegistry.
        // The @Primary bean must be the in-memory subclass, not the production superclass.
        assertThat(racerPublisherRegistry.getClass())
                .isEqualTo(InMemoryRacerPublisherRegistry.class);
    }
}
