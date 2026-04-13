package com.cheetah.racer.service;

import com.cheetah.racer.RedisChannels;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.DeadLetterMessage;
import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerRetentionServiceTest {

    @Mock ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock ReactiveStreamOperations<String, Object, Object> streamOps;
    @Mock ReactiveListOperations<String, String> listOps;
    @Mock DeadLetterQueueService dlqService;

    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    RacerRetentionService service;
    RacerProperties props;

    static final long STREAM_MAX_LEN = 10_000L;
    static final long DLQ_MAX_AGE_HOURS = 24L;

    @BeforeEach
    void setUp() {
        props = new RacerProperties();

        RacerProperties.ChannelProperties ch = new RacerProperties.ChannelProperties();
        ch.setName("orders");
        ch.setStreamKey("stream:orders");
        ch.setDurable(true);
        props.getChannels().put("orders", ch);

        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(redisTemplate.opsForList()).thenReturn(listOps);

        service = new RacerRetentionService(
                redisTemplate, dlqService, objectMapper,
                STREAM_MAX_LEN, DLQ_MAX_AGE_HOURS, props);
    }

    // ── trimStreams ────────────────────────────────────────────────────────────

    @Test
    void trimStreams_trimsConfiguredStreams() {
        when(streamOps.trim(anyString(), eq(STREAM_MAX_LEN), eq(true)))
                .thenReturn(Mono.just(5L));

        StepVerifier.create(service.trimStreams())
                .verifyComplete();

        // Should trim REQUEST_STREAM and the configured durable channel
        verify(streamOps).trim(eq(RedisChannels.REQUEST_STREAM), eq(STREAM_MAX_LEN), eq(true));
        verify(streamOps).trim(eq("stream:orders"), eq(STREAM_MAX_LEN), eq(true));
    }

    @Test
    void trimStreams_noChannels_completesEmpty() {
        RacerProperties emptyProps = new RacerProperties();
        RacerRetentionService svc = new RacerRetentionService(
                redisTemplate, dlqService, objectMapper,
                STREAM_MAX_LEN, DLQ_MAX_AGE_HOURS, emptyProps);

        when(streamOps.trim(anyString(), eq(STREAM_MAX_LEN), eq(true)))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(svc.trimStreams())
                .verifyComplete();

        // Only the built-in REQUEST_STREAM is trimmed
        verify(streamOps, times(1)).trim(anyString(), eq(STREAM_MAX_LEN), eq(true));
    }

    @Test
    void trimStreams_trimError_handledGracefully() {
        when(streamOps.trim(eq(RedisChannels.REQUEST_STREAM), eq(STREAM_MAX_LEN), eq(true)))
                .thenReturn(Mono.error(new RuntimeException("connection lost")));
        when(streamOps.trim(eq("stream:orders"), eq(STREAM_MAX_LEN), eq(true)))
                .thenReturn(Mono.just(3L));

        StepVerifier.create(service.trimStreams())
                .verifyComplete();
    }

    // ── pruneDlq ──────────────────────────────────────────────────────────────

    @Test
    void pruneDlq_removesExpiredEntries() {
        DeadLetterMessage expired = DeadLetterMessage.builder()
                .id("dlm-1")
                .originalMessage(RacerMessage.create("ch", "{}", "sender"))
                .errorMessage("test error")
                .failedAt(Instant.now().minus(Duration.ofHours(25)))
                .attemptCount(1)
                .build();

        DeadLetterMessage fresh = DeadLetterMessage.builder()
                .id("dlm-2")
                .originalMessage(RacerMessage.create("ch", "{}", "sender"))
                .errorMessage("test error")
                .failedAt(Instant.now())
                .attemptCount(1)
                .build();

        when(dlqService.peekAll()).thenReturn(Flux.just(expired, fresh));
        when(listOps.remove(eq(RedisChannels.DEAD_LETTER_QUEUE), eq(1L), anyString()))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(service.pruneDlq())
                .expectNext(1L) // only the expired entry removed
                .verifyComplete();
    }

    @Test
    void pruneDlq_keepsRecentEntries() {
        DeadLetterMessage fresh = DeadLetterMessage.builder()
                .id("dlm-3")
                .originalMessage(RacerMessage.create("ch", "{}", "sender"))
                .errorMessage("recent error")
                .failedAt(Instant.now())
                .attemptCount(1)
                .build();

        when(dlqService.peekAll()).thenReturn(Flux.just(fresh));

        StepVerifier.create(service.pruneDlq())
                .expectNext(0L) // nothing removed
                .verifyComplete();
    }

    @Test
    void pruneDlq_emptyDlq_returnsZero() {
        when(dlqService.peekAll()).thenReturn(Flux.empty());

        StepVerifier.create(service.pruneDlq())
                .expectNext(0L)
                .verifyComplete();
    }

    // ── getConfig ─────────────────────────────────────────────────────────────

    @Test
    void getConfig_returnsConfigurationMap() {
        Map<String, Object> config = service.getConfig();

        assertThat(config)
                .containsEntry("streamMaxLen", STREAM_MAX_LEN)
                .containsEntry("dlqMaxAgeHours", DLQ_MAX_AGE_HOURS)
                .containsEntry("totalStreamTrimmed", 0L)
                .containsEntry("totalDlqPruned", 0L);
    }

    // ── counters ──────────────────────────────────────────────────────────────

    @Test
    void getTotalStreamTrimmed_initiallyZero() {
        assertThat(service.getTotalStreamTrimmed()).isZero();
    }

    @Test
    void getTotalDlqPruned_initiallyZero() {
        assertThat(service.getTotalDlqPruned()).isZero();
    }

    // ── runRetention ──────────────────────────────────────────────────────────

    @Test
    void runRetention_callsBothTrimAndPrune() {
        when(streamOps.trim(anyString(), eq(STREAM_MAX_LEN), eq(true)))
                .thenReturn(Mono.just(0L));
        when(dlqService.peekAll()).thenReturn(Flux.empty());

        service.runRetention();

        verify(redisTemplate, atLeastOnce()).opsForStream();
        verify(dlqService).peekAll();
    }
}
