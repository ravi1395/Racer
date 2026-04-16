package com.cheetah.racer.requestreply;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.RacerReply;
import com.cheetah.racer.model.RacerRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for v1.4.0 item 1.3: request-reply stream cleanup error handling.
 *
 * <p>Verifies that when the ephemeral reply-stream delete call fails (e.g. due to a
 * transient Redis error), the failure is:
 * <ul>
 *   <li>Swallowed via {@code .onErrorResume} so the overall request-reply result
 *       is unaffected.</li>
 *   <li>Logged at WARN level so leaked stream keys are observable in operations
 *       dashboards without raising an alert.</li>
 * </ul>
 *
 * <p>The test invokes the private {@code sendStreamRequest} method via reflection
 * to isolate the unit without needing a full Spring context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerClientFactoryBeanCleanupTest {

    // RETURNS_DEEP_STUBS allows chained stubbing of redisTemplate.opsForStream().read(...)
    // without hitting the ReactiveStreamOperations.read() overload ambiguity at compile time.
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private ReactiveRedisTemplate<String, String> redisTemplate;

    private ObjectMapper objectMapper;
    private RacerProperties racerProperties;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        racerProperties = new RacerProperties();

        // Stream entry add succeeds immediately
        when(redisTemplate.opsForStream().add(any(MapRecord.class)))
                .thenReturn(Mono.just(RecordId.of("0-1")));

        // Build a valid RacerReply JSON record for the poll to find immediately.
        // Must stub the two-argument overload read(StreamReadOptions, StreamOffset) because
        // pollForStreamReply uses XREAD BLOCK via StreamReadOptions — the single-argument
        // overload stub would not match and would leave the Mono permanently pending.
        RacerReply validReply = RacerReply.success("corr-1", "\"done\"", "responder");
        String replyJson = objectMapper.writeValueAsString(validReply);

        MapRecord<String, Object, Object> mockRecord = mock(MapRecord.class);
        when(mockRecord.getValue()).thenReturn(Map.of("payload", replyJson));

        when(redisTemplate.opsForStream().read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(Flux.just(mockRecord));

        // delete() FAILS with a simulated Redis error — this is the behaviour under test
        when(redisTemplate.delete(anyString()))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));
    }

    // ── Factory bean builder ──────────────────────────────────────────────────

    private RacerClientFactoryBean<Object> buildFactoryBean() {
        RacerClientFactoryBean<Object> fb = new RacerClientFactoryBean<>(Object.class);
        ReflectionTestUtils.setField(fb, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(fb, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(fb, "racerProperties", racerProperties);
        return fb;
    }

    // ── Helper: invoke sendStreamRequest via reflection ───────────────────────

    private Mono<RacerReply> invokeSendStreamRequest(
            RacerClientFactoryBean<Object> fb,
            RacerRequest request,
            String streamKey,
            Duration timeout) throws Exception {
        Method m = RacerClientFactoryBean.class.getDeclaredMethod(
                "sendStreamRequest", RacerRequest.class, String.class, Duration.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Mono<RacerReply> result = (Mono<RacerReply>) m.invoke(fb, request, streamKey, timeout);
        return result;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void deleteFailure_doesNotPropagateToCallerMono() throws Exception {
        // delete() is set up to fail in setUp(); verify the overall Mono still completes
        RacerClientFactoryBean<Object> fb = buildFactoryBean();
        RacerRequest req = RacerRequest.builder()
                .correlationId("corr-1")
                .payload("\"hello\"")
                .replyTo("racer:reply:corr-1")
                .build();

        Mono<RacerReply> result = invokeSendStreamRequest(fb, req, "racer:requests", Duration.ofSeconds(1));

        // The Mono must complete with a reply — not propagate the delete() error
        StepVerifier.create(result)
                .assertNext(reply -> assertThat(reply).isNotNull())
                .verifyComplete();
    }

    @Test
    void deleteFailure_doesNotThrowSynchronously() throws Exception {
        RacerClientFactoryBean<Object> fb = buildFactoryBean();
        RacerRequest req = RacerRequest.builder()
                .correlationId("corr-1")
                .payload("\"hello\"")
                .replyTo("racer:reply:corr-1")
                .build();

        // Building and subscribing to the Mono must never throw synchronously
        assertThatCode(() -> {
            Mono<RacerReply> result = invokeSendStreamRequest(fb, req, "racer:requests", Duration.ofSeconds(1));
            result.block(Duration.ofSeconds(2));
        }).doesNotThrowAnyException();
    }

    @Test
    void deleteIsAttempted_afterSuccessfulRequest() throws Exception {
        RacerClientFactoryBean<Object> fb = buildFactoryBean();
        RacerRequest req = RacerRequest.builder()
                .correlationId("corr-1")
                .payload("\"hello\"")
                .replyTo("racer:reply:corr-1")
                .build();

        Mono<RacerReply> result = invokeSendStreamRequest(fb, req, "racer:requests", Duration.ofSeconds(1));
        result.block(Duration.ofSeconds(2));

        // Verify that delete() was called on the response stream key (fire-and-forget cleanup)
        verify(redisTemplate, atLeastOnce()).delete("racer:reply:corr-1");
    }
}
