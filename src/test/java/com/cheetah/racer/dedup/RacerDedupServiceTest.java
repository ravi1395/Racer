package com.cheetah.racer.dedup;

import com.cheetah.racer.config.RacerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerDedupService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("null")
class RacerDedupServiceTest {

    @Mock ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock ReactiveValueOperations<String, String> valueOps;

    private RacerDedupService dedupService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        RacerProperties props = new RacerProperties();
        props.getDedup().setEnabled(true);
        props.getDedup().setTtlSeconds(300);
        props.getDedup().setKeyPrefix("racer:dedup:");

        dedupService = new RacerDedupService(redisTemplate, props);
    }

    // ── first-time message ────────────────────────────────────────────────────

    @Test
    void checkAndMarkProcessed_firstSeen_returnsTrue() {
        when(valueOps.setIfAbsent(eq("racer:dedup:msg-001"), eq("1"), any(Duration.class)))
                .thenReturn(Mono.just(true));

        StepVerifier.create(dedupService.checkAndMarkProcessed("msg-001"))
                .expectNext(true)
                .verifyComplete();
    }

    // ── duplicate message ─────────────────────────────────────────────────────

    @Test
    void checkAndMarkProcessed_duplicate_returnsFalse() {
        // SET NX returns false when key already exists
        when(valueOps.setIfAbsent(eq("racer:dedup:msg-001"), eq("1"), any(Duration.class)))
                .thenReturn(Mono.just(false));

        StepVerifier.create(dedupService.checkAndMarkProcessed("msg-001"))
                .expectNext(false)
                .verifyComplete();
    }

    // ── key prefix ────────────────────────────────────────────────────────────

    @Test
    void checkAndMarkProcessed_usesConfiguredKeyPrefix() {
        RacerProperties props = new RacerProperties();
        props.getDedup().setKeyPrefix("custom:prefix:");

        RacerDedupService svc = new RacerDedupService(redisTemplate, props);

        when(valueOps.setIfAbsent(eq("custom:prefix:abc"), eq("1"), any(Duration.class)))
                .thenReturn(Mono.just(true));

        StepVerifier.create(svc.checkAndMarkProcessed("abc"))
                .expectNext(true)
                .verifyComplete();
    }

    // ── TTL propagation ───────────────────────────────────────────────────────

    @Test
    void checkAndMarkProcessed_passesConfiguredTtl() {
        RacerProperties props = new RacerProperties();
        props.getDedup().setTtlSeconds(60);
        props.getDedup().setKeyPrefix("racer:dedup:");

        RacerDedupService svc = new RacerDedupService(redisTemplate, props);

        when(valueOps.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(60))))
                .thenReturn(Mono.just(true));

        StepVerifier.create(svc.checkAndMarkProcessed("any-id"))
                .expectNext(true)
                .verifyComplete();
    }

    // ── null / empty id ───────────────────────────────────────────────────────

    @Test
    void checkAndMarkProcessed_nullId_allowsThrough() {
        // No Redis call expected — null id is treated as "no id, allow through"
        StepVerifier.create(dedupService.checkAndMarkProcessed(null))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void checkAndMarkProcessed_emptyId_allowsThrough() {
        StepVerifier.create(dedupService.checkAndMarkProcessed(""))
                .expectNext(true)
                .verifyComplete();
    }

    // ── Redis server empty response ───────────────────────────────────────────

    @Test
    void checkAndMarkProcessed_redisReturnsEmpty_defaultsFalse() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.empty());

        // defaultIfEmpty(false) means an empty Mono is treated as duplicate
        StepVerifier.create(dedupService.checkAndMarkProcessed("msg-002"))
                .expectNext(false)
                .verifyComplete();
    }

    // ── fail-open on Redis error ──────────────────────────────────────────────

    @Test
    void checkAndMarkProcessed_redisError_failsOpen() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        // Must return true (fail-open) so processing is not halted by Redis issues
        StepVerifier.create(dedupService.checkAndMarkProcessed("msg-003"))
                .expectNext(true)
                .verifyComplete();
    }
}
