package com.cheetah.racer.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerStreamUtilsTest {

    @Mock
    ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    ReactiveStreamOperations<String, Object, Object> streamOps;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
    }

    // ── ensureGroup ─────────────────────────────────────────────

    @Test
    void ensureGroup_success_createsGroup() {
        when(streamOps.createGroup(eq("stream:orders"), any(ReadOffset.class), eq("my-group")))
                .thenReturn(Mono.just("OK"));

        StepVerifier.create(RacerStreamUtils.ensureGroup(redisTemplate, "stream:orders", "my-group"))
                .verifyComplete();
    }

    @Test
    void ensureGroup_busyGroupError_swallowed() {
        when(streamOps.createGroup(eq("stream:orders"), any(ReadOffset.class), eq("my-group")))
                .thenReturn(Mono.error(new RedisSystemException(
                        "BUSYGROUP Consumer Group name already exists",
                        new RuntimeException("BUSYGROUP Consumer Group name already exists"))));

        StepVerifier.create(RacerStreamUtils.ensureGroup(redisTemplate, "stream:orders", "my-group"))
                .verifyComplete();
    }

    @Test
    void ensureGroup_busyGroupNestedCause_swallowed() {
        RuntimeException root = new RuntimeException("BUSYGROUP Consumer Group name already exists");
        RuntimeException wrapped = new RuntimeException("Wrapper", root);
        RedisSystemException ex = new RedisSystemException("Redis error", wrapped);

        when(streamOps.createGroup(eq("stream:orders"), any(ReadOffset.class), eq("my-group")))
                .thenReturn(Mono.error(ex));

        StepVerifier.create(RacerStreamUtils.ensureGroup(redisTemplate, "stream:orders", "my-group"))
                .verifyComplete();
    }

    @Test
    void ensureGroup_otherError_propagated() {
        RuntimeException error = new RuntimeException("Some other error");

        when(streamOps.createGroup(eq("stream:orders"), any(ReadOffset.class), eq("my-group")))
                .thenReturn(Mono.error(error));

        StepVerifier.create(RacerStreamUtils.ensureGroup(redisTemplate, "stream:orders", "my-group"))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── ackRecord ───────────────────────────────────────────────

    @Test
    void ackRecord_success_acknowledges() {
        RecordId recordId = RecordId.of("1234-0");

        when(streamOps.acknowledge(eq("stream:orders"), eq("my-group"), eq(recordId)))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(RacerStreamUtils.ackRecord(redisTemplate, "stream:orders", "my-group", recordId))
                .verifyComplete();
    }

    @Test
    void ackRecord_error_propagates() {
        RecordId recordId = RecordId.of("1234-0");

        when(streamOps.acknowledge(eq("stream:orders"), eq("my-group"), eq(recordId)))
                .thenReturn(Mono.error(new RuntimeException("ACK failed")));

        StepVerifier.create(RacerStreamUtils.ackRecord(redisTemplate, "stream:orders", "my-group", recordId))
                .expectError(RuntimeException.class)
                .verify();
    }

    // ── isBusyGroup (tested indirectly via ensureGroup) ─────────

    @Test
    void isBusyGroup_directMessage_true() {
        when(streamOps.createGroup(eq("key"), any(ReadOffset.class), eq("g")))
                .thenReturn(Mono.error(new RuntimeException("BUSYGROUP")));

        StepVerifier.create(RacerStreamUtils.ensureGroup(redisTemplate, "key", "g"))
                .verifyComplete();
    }

    @Test
    void isBusyGroup_wrappedCause_true() {
        RuntimeException root = new RuntimeException("BUSYGROUP msg");
        RuntimeException mid = new RuntimeException("mid", root);
        RuntimeException top = new RuntimeException("top", mid);

        when(streamOps.createGroup(eq("key"), any(ReadOffset.class), eq("g")))
                .thenReturn(Mono.error(top));

        StepVerifier.create(RacerStreamUtils.ensureGroup(redisTemplate, "key", "g"))
                .verifyComplete();
    }

    @Test
    void isBusyGroup_nullMessage_false() {
        when(streamOps.createGroup(eq("key"), any(ReadOffset.class), eq("g")))
                .thenReturn(Mono.error(new RuntimeException((String) null)));

        StepVerifier.create(RacerStreamUtils.ensureGroup(redisTemplate, "key", "g"))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    void isBusyGroup_noCause_false() {
        when(streamOps.createGroup(eq("key"), any(ReadOffset.class), eq("g")))
                .thenReturn(Mono.error(new RuntimeException("Some other error")));

        StepVerifier.create(RacerStreamUtils.ensureGroup(redisTemplate, "key", "g"))
                .expectError(RuntimeException.class)
                .verify();
    }
}
