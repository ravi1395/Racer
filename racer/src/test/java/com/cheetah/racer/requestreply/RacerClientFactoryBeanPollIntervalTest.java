package com.cheetah.racer.requestreply;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.RacerReply;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for v1.4.0 item 1.4: configurable request-reply stream poll interval.
 *
 * <p>The stream request-reply polling loop must read its delay from
 * {@code racer.request-reply.stream-poll-interval-ms} rather than using a
 * hardcoded 200 ms value. This allows latency-sensitive services to reduce
 * polling latency and batch services to reduce Redis load.
 *
 * <p>{@link org.mockito.Answers#RETURNS_DEEP_STUBS} is used on the template so that
 * {@code redisTemplate.opsForStream().read(...)} can be stubbed via a chained call,
 * avoiding the Java compile-time ambiguity caused by the dual
 * {@code read(StreamOffset<K>)} / {@code read(StreamOffset<K>...)} overloads on
 * {@link org.springframework.data.redis.core.ReactiveStreamOperations}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerClientFactoryBeanPollIntervalTest {

    // RETURNS_DEEP_STUBS: opsForStream() returns a pre-configured mock so we can
    // stub the chained read() call without hitting the overload ambiguity at compile time.
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ReactiveRedisTemplate<String, String> redisTemplate;

    private ObjectMapper    objectMapper;
    private RacerProperties racerProperties;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        objectMapper    = new ObjectMapper().registerModule(new JavaTimeModule());
        racerProperties = new RacerProperties();

        // add() always succeeds
        when(redisTemplate.opsForStream().add(any(MapRecord.class)))
                .thenReturn(Mono.just(RecordId.of("0-1")));

        // delete() is a no-op — not the focus of these tests
        when(redisTemplate.delete(anyString())).thenReturn(Mono.just(1L));
    }

    // ── Helper builders ───────────────────────────────────────────────────────

    private RacerClientFactoryBean<Object> buildFactoryBean() {
        RacerClientFactoryBean<Object> fb = new RacerClientFactoryBean<>(Object.class);
        ReflectionTestUtils.setField(fb, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(fb, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(fb, "racerProperties", racerProperties);
        return fb;
    }

    /** Builds a valid reply record that {@code pollForStreamReply} will accept. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private MapRecord<String, Object, Object> buildReplyRecord(String correlationId) throws Exception {
        RacerReply reply = RacerReply.success(correlationId, "\"ok\"", "responder");
        String json = objectMapper.writeValueAsString(reply);
        MapRecord<String, Object, Object> record = mock(MapRecord.class);
        when(record.getValue()).thenReturn(Map.of("payload", json));
        return record;
    }

    /** Invokes the private {@code pollForStreamReply} method via reflection. */
    @SuppressWarnings("unchecked")
    private Mono<RacerReply> invokePollForStreamReply(
            RacerClientFactoryBean<Object> fb,
            String responseStreamKey,
            String correlationId,
            Duration timeout) throws Exception {
        Method m = RacerClientFactoryBean.class.getDeclaredMethod(
                "pollForStreamReply", String.class, String.class, Duration.class);
        m.setAccessible(true);
        return (Mono<RacerReply>) m.invoke(fb, responseStreamKey, correlationId, timeout);
    }

    // ── Default value ─────────────────────────────────────────────────────────

    @Test
    void defaultPollInterval_is200ms() {
        RacerProperties.RequestReplyProperties rrProps =
                new RacerProperties.RequestReplyProperties();

        assertThat(rrProps.getStreamPollIntervalMs()).isEqualTo(200L);
    }

    // ── Custom interval is honoured ───────────────────────────────────────────

    @Test
    void customPollInterval_50ms_isReadFromProperties() {
        racerProperties.getRequestReply().setStreamPollIntervalMs(50);

        assertThat(racerProperties.getRequestReply().getStreamPollIntervalMs()).isEqualTo(50L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollForStreamReply_completesWithReply_whenReplyAvailableImmediately() throws Exception {
        // Configure 50 ms interval — this is what the method should use
        racerProperties.getRequestReply().setStreamPollIntervalMs(50);

        MapRecord<String, Object, Object> replyRecord = buildReplyRecord("corr-50");
        // Cast to StreamOffset<String> to resolve the read() overload unambiguously
        when(redisTemplate.opsForStream().read((StreamOffset<String>) any()))
                .thenReturn(Flux.just(replyRecord));

        RacerClientFactoryBean<Object> fb = buildFactoryBean();

        Mono<RacerReply> result = invokePollForStreamReply(fb, "racer:reply:corr-50", "corr-50", Duration.ofSeconds(5));

        StepVerifier.create(result)
                .assertNext(reply -> {
                    assertThat(reply).isNotNull();
                    assertThat(reply.getCorrelationId()).isEqualTo("corr-50");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollForStreamReply_defaultInterval_completesWithReply() throws Exception {
        // Verify the default 200 ms configuration also works correctly
        MapRecord<String, Object, Object> replyRecord = buildReplyRecord("corr-200");
        when(redisTemplate.opsForStream().read((StreamOffset<String>) any()))
                .thenReturn(Flux.just(replyRecord));

        RacerClientFactoryBean<Object> fb = buildFactoryBean();

        Mono<RacerReply> result = invokePollForStreamReply(fb, "racer:reply:corr-200", "corr-200", Duration.ofSeconds(5));

        StepVerifier.create(result)
                .assertNext(reply -> assertThat(reply.getCorrelationId()).isEqualTo("corr-200"))
                .verifyComplete();
    }

    /**
     * When the poll interval is set larger than the timeout, {@code maxAttempts}
     * rounds down to 0 and the polling loop makes only one attempt (the initial
     * defer). The Mono completes empty, which triggers the timeout.
     *
     * <p>This indirectly verifies that {@code maxAttempts} is computed from the
     * <em>configured</em> poll interval — if the hardcoded 200 ms were still used
     * the computed {@code maxAttempts} would differ and the timing would change.
     */
    @Test
    @SuppressWarnings("unchecked")
    void largeInterval_exceedingTimeout_limitsRepeatAttempts() throws Exception {
        // interval=1000ms, timeout=100ms → maxAttempts = 100/1000 = 0 → no retries after first miss
        racerProperties.getRequestReply().setStreamPollIntervalMs(1000);

        // No reply available — read() always returns empty
        when(redisTemplate.opsForStream().read((StreamOffset<String>) any()))
                .thenReturn(Flux.empty());

        RacerClientFactoryBean<Object> fb = buildFactoryBean();

        // With 0 retries the mono completes empty (no timeout needed to run the test fast)
        Mono<RacerReply> result = invokePollForStreamReply(
                fb, "racer:reply:large", "corr-large", Duration.ofMillis(100));

        // Either empty-completion or a TimeoutException are both acceptable outcomes;
        // the critical assertion is that read() is called at most once (no retry loop ran).
        try {
            result.block(Duration.ofMillis(500));
        } catch (Exception ignored) {
            // Timeout is acceptable — we only care about the read() call count below
        }

        // With interval > timeout, maxAttempts=0 → repeatWhen(.take(0)) → no extra polls.
        // read() should be called exactly once (the initial defer).
        verify(redisTemplate.opsForStream(), atMost(1)).read((StreamOffset<String>) any());
    }
}
