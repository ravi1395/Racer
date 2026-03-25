package com.cheetah.racer.publisher;

import com.cheetah.racer.exception.RacerRateLimitException;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.ratelimit.RacerRateLimiter;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RacerChannelPublisherImpl}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerChannelPublisherImplTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveStreamOperations<String, String, String> streamOps;

    @Mock
    private RacerMetricsPort racerMetrics;

    @Mock
    private RacerSchemaRegistry schemaRegistry;

    @Mock
    private RacerRateLimiter rateLimiter;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Default stubs
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(Mono.just(1L));
        doReturn(streamOps).when(redisTemplate).opsForStream();
        doReturn(Mono.just(RecordId.of("1234-0"))).when(streamOps).add(any(MapRecord.class), any(XAddOptions.class));
        when(rateLimiter.checkLimit(anyString())).thenReturn(Mono.empty());
        when(schemaRegistry.validateForPublishReactive(anyString(), any())).thenReturn(Mono.empty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RacerChannelPublisherImpl pubsubPublisher() {
        return new RacerChannelPublisherImpl(
                redisTemplate, objectMapper, "my-channel", "my-alias", "default-sender",
                false, "", 0L, racerMetrics, schemaRegistry, rateLimiter);
    }

    private RacerChannelPublisherImpl durablePublisher() {
        return new RacerChannelPublisherImpl(
                redisTemplate, objectMapper, "my-channel", "my-alias", "default-sender",
                true, "my-stream-key", 1000L, racerMetrics, schemaRegistry, rateLimiter);
    }

    // ── Pub/Sub publishing ────────────────────────────────────────────────────

    @Test
    void publishAsync_pubsub_publishesViaConvertAndSend() {
        RacerChannelPublisherImpl publisher = pubsubPublisher();

        StepVerifier.create(publisher.publishAsync("test-payload"))
                .expectNextMatches(count -> count > 0)
                .verifyComplete();

        verify(redisTemplate).convertAndSend(eq("my-channel"), anyString());
    }

    // ── Durable stream publishing ─────────────────────────────────────────────

    @Test
    void publishAsync_durable_publishesViaXadd() {
        RacerChannelPublisherImpl publisher = durablePublisher();

        StepVerifier.create(publisher.publishAsync("test-payload"))
                .expectNextMatches(count -> count == 1L)
                .verifyComplete();

        verify(redisTemplate).opsForStream();
        verify(streamOps).add(any(MapRecord.class), any(XAddOptions.class));
    }

    // ── Rate limiting ─────────────────────────────────────────────────────────

    @Test
    void publishAsync_rateLimitExceeded_returnsErrorMono() {
        when(rateLimiter.checkLimit(anyString()))
                .thenReturn(Mono.error(new RacerRateLimitException("my-alias")));

        RacerChannelPublisherImpl publisher = pubsubPublisher();

        StepVerifier.create(publisher.publishAsync("payload"))
                .expectError(RacerRateLimitException.class)
                .verify();
    }

    // ── Schema validation ─────────────────────────────────────────────────────

    @Test
    void publishAsync_schemaValidation_passes() {
        when(schemaRegistry.validateForPublishReactive(anyString(), any())).thenReturn(Mono.empty());

        RacerChannelPublisherImpl publisher = pubsubPublisher();

        StepVerifier.create(publisher.publishAsync("valid-payload"))
                .expectNextMatches(count -> count > 0)
                .verifyComplete();
    }

    @Test
    void publishAsync_schemaValidation_fails_throwsException() {
        RuntimeException schemaError = new RuntimeException("Schema violation");
        when(schemaRegistry.validateForPublishReactive(anyString(), any()))
                .thenReturn(Mono.error(schemaError));

        RacerChannelPublisherImpl publisher = pubsubPublisher();

        StepVerifier.create(publisher.publishAsync("bad-payload"))
                .expectErrorMatches(ex -> ex.getMessage().equals("Schema violation"))
                .verify();
    }

    // ── Routed publishing ─────────────────────────────────────────────────────

    @Test
    void publishRoutedAsync_setsRoutedFlag() {
        RacerChannelPublisherImpl publisher = pubsubPublisher();

        StepVerifier.create(publisher.publishRoutedAsync("data", "sender"))
                .expectNextMatches(count -> count > 0)
                .verifyComplete();

        // Capture the JSON string passed to convertAndSend and verify it contains "routed":true
        verify(redisTemplate).convertAndSend(eq("my-channel"), argThat(json ->
                json.contains("\"routed\":true") || json.contains("\"routed\": true")));
    }

    // ── Sync publishing ───────────────────────────────────────────────────────

    @Test
    void publishSync_blocksOnAsyncResult() {
        RacerChannelPublisherImpl publisher = pubsubPublisher();

        Long result = publisher.publishSync("payload");

        assertThat(result).isEqualTo(1L);
        verify(redisTemplate).convertAndSend(eq("my-channel"), anyString());
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    @Test
    void getChannelName_returnsChannelName() {
        assertThat(pubsubPublisher().getChannelName()).isEqualTo("my-channel");
    }

    @Test
    void getChannelAlias_returnsChannelAlias() {
        assertThat(pubsubPublisher().getChannelAlias()).isEqualTo("my-alias");
    }

    // ── Null metrics defaults to NoOp ─────────────────────────────────────────

    @Test
    void publishAsync_nullMetrics_usesNoOp() {
        // Pass null for metrics — constructor should default to NoOpRacerMetrics
        RacerChannelPublisherImpl publisher = new RacerChannelPublisherImpl(
                redisTemplate, objectMapper, "ch", "alias", "sender",
                false, "", 0L, null, null, null);

        // Should not throw NPE
        StepVerifier.create(publisher.publishAsync("payload"))
                .expectNextMatches(count -> count > 0)
                .verifyComplete();
    }
}
