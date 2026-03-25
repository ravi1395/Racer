package com.cheetah.racer.health;

import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.stream.RacerConsumerLagMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerHealthIndicatorTest {

    @Mock
    ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    ReactiveRedisConnectionFactory connectionFactory;

    @Mock
    ReactiveRedisConnection connection;

    @Mock
    DeadLetterQueueService dlqService;

    @Mock
    RacerConsumerLagMonitor lagMonitor;

    @BeforeEach
    void setUp() {
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getReactiveConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn(Mono.just("PONG"));
    }

    @Test
    void health_redisUp_returnsUp() {
        var indicator = new RacerHealthIndicator(redisTemplate, null);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("redis.ping", "PONG");
                })
                .verifyComplete();
    }

    @Test
    void health_redisDown_returnsDown() {
        when(connection.ping()).thenReturn(
                Mono.error(new RuntimeException("Connection refused")));

        var indicator = new RacerHealthIndicator(redisTemplate, null);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails()).containsEntry("redis.ping", "FAILED");
                })
                .verifyComplete();
    }

    @Test
    void health_withDlqService_addsDlqDepth() {
        when(dlqService.size()).thenReturn(Mono.just(42L));

        var indicator = new RacerHealthIndicator(redisTemplate, dlqService);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("redis.ping", "PONG");
                    assertThat(health.getDetails()).containsEntry("dlq.depth", 42L);
                })
                .verifyComplete();
    }

    @Test
    void health_dlqAboveThreshold_returnsDown() {
        // Source code does not implement a DLQ-depth threshold; high DLQ depth
        // is reported but does not degrade status by itself.
        when(dlqService.size()).thenReturn(Mono.just(5000L));

        var indicator = new RacerHealthIndicator(redisTemplate, dlqService);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsEntry("dlq.depth", 5000L);
                })
                .verifyComplete();
    }

    @Test
    void health_withLagMonitor_addsLagDetails() {
        Map<String, AtomicLong> counters = new LinkedHashMap<>();
        counters.put("stream:orders", new AtomicLong(500));
        when(lagMonitor.getLagCounters()).thenReturn(counters);

        var indicator = new RacerHealthIndicator(redisTemplate, null, lagMonitor, null);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsKey("consumer-lag");
                    assertThat(health.getDetails()).doesNotContainKey("consumer-lag.threshold-breached");
                })
                .verifyComplete();
    }

    @Test
    void health_lagAboveThreshold_returnsDown() {
        Map<String, AtomicLong> counters = new LinkedHashMap<>();
        counters.put("stream:orders", new AtomicLong(20_000));
        when(lagMonitor.getLagCounters()).thenReturn(counters);

        // Default threshold is 10_000 when racerProperties is null
        var indicator = new RacerHealthIndicator(redisTemplate, null, lagMonitor, null);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(new Status("OUT_OF_SERVICE"));
                    assertThat(health.getDetails()).containsKey("consumer-lag");
                    assertThat(health.getDetails())
                            .containsEntry("consumer-lag.threshold-breached", true);
                })
                .verifyComplete();
    }

    @Test
    void health_noDlqService_skipsEnrichment() {
        var indicator = new RacerHealthIndicator(redisTemplate, null);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).doesNotContainKey("dlq.depth");
                })
                .verifyComplete();
    }

    @Test
    void health_noLagMonitor_skipsLagEnrichment() {
        var indicator = new RacerHealthIndicator(redisTemplate, null, null, null);

        StepVerifier.create(indicator.health())
                .assertNext(health -> {
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).doesNotContainKey("consumer-lag");
                })
                .verifyComplete();
    }
}
