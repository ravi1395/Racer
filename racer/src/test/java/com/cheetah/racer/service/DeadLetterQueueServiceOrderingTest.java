package com.cheetah.racer.service;

import com.cheetah.racer.RedisChannels;
import com.cheetah.racer.model.DeadLetterMessage;
import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.Mockito.when;

/**
 * Verifies that {@link DeadLetterQueueService#peekAll()} returns DLQ messages in FIFO order
 * (oldest first) — consistent with the order that {@link DeadLetterQueueService#dequeue()}
 * pops them, regardless of the underlying Redis LPUSH newest-first storage order.
 */
@ExtendWith(MockitoExtension.class)
class DeadLetterQueueServiceOrderingTest {

    @Mock ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock ReactiveListOperations<String, String> listOps;

    ObjectMapper objectMapper;
    DeadLetterQueueService service;

    static final RuntimeException ERROR = new RuntimeException("test-error");

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        when(redisTemplate.opsForList()).thenReturn(listOps);
        service = new DeadLetterQueueService(redisTemplate, objectMapper);
    }

    /**
     * Enqueue order: msg-1, msg-2, msg-3.
     * Redis LPUSH stores newest-first: [msg-3-json, msg-2-json, msg-1-json].
     * LRANGE(0,-1) returns that order.
     * {@code peekAll()} must reverse it so msg-1 comes out first.
     */
    @Test
    void peekAll_returnsMessagesOldestFirst() throws Exception {
        RacerMessage msg1 = RacerMessage.create("racer:orders", "payload-1", "svc");
        RacerMessage msg2 = RacerMessage.create("racer:orders", "payload-2", "svc");
        RacerMessage msg3 = RacerMessage.create("racer:orders", "payload-3", "svc");

        String json1 = objectMapper.writeValueAsString(DeadLetterMessage.from(msg1, ERROR));
        String json2 = objectMapper.writeValueAsString(DeadLetterMessage.from(msg2, ERROR));
        String json3 = objectMapper.writeValueAsString(DeadLetterMessage.from(msg3, ERROR));

        // Redis LRANGE returns newest-first (LPUSH order)
        when(listOps.range(RedisChannels.DEAD_LETTER_QUEUE, 0, -1))
                .thenReturn(Flux.fromIterable(List.of(json3, json2, json1)));

        StepVerifier.create(service.peekAll().map(m -> m.getOriginalMessage().getPayload()))
                .expectNext("payload-1")
                .expectNext("payload-2")
                .expectNext("payload-3")
                .verifyComplete();
    }

    /**
     * {@code dequeue()} uses RPOP which pops from the tail of the list —
     * the tail holds the oldest entry (the one pushed first via LPUSH).
     * Verify that sequential dequeue calls also return msg-1, msg-2, msg-3 in order.
     */
    @Test
    void dequeue_returnsMessagesOldestFirst() throws Exception {
        RacerMessage msg1 = RacerMessage.create("racer:orders", "payload-1", "svc");
        RacerMessage msg2 = RacerMessage.create("racer:orders", "payload-2", "svc");
        RacerMessage msg3 = RacerMessage.create("racer:orders", "payload-3", "svc");

        String json1 = objectMapper.writeValueAsString(DeadLetterMessage.from(msg1, ERROR));
        String json2 = objectMapper.writeValueAsString(DeadLetterMessage.from(msg2, ERROR));
        String json3 = objectMapper.writeValueAsString(DeadLetterMessage.from(msg3, ERROR));

        // RPOP returns oldest (tail) first
        when(listOps.rightPop(RedisChannels.DEAD_LETTER_QUEUE))
                .thenReturn(Mono.just(json1))
                .thenReturn(Mono.just(json2))
                .thenReturn(Mono.just(json3));

        StepVerifier.create(service.dequeue().map(m -> m.getOriginalMessage().getPayload()))
                .expectNext("payload-1")
                .verifyComplete();

        StepVerifier.create(service.dequeue().map(m -> m.getOriginalMessage().getPayload()))
                .expectNext("payload-2")
                .verifyComplete();

        StepVerifier.create(service.dequeue().map(m -> m.getOriginalMessage().getPayload()))
                .expectNext("payload-3")
                .verifyComplete();
    }

    /**
     * Corrupted entries (invalid JSON) must be skipped with a WARN log and not abort the stream.
     */
    @Test
    void peekAll_corruptedEntry_isSkippedNotAborted() throws Exception {
        RacerMessage msg1 = RacerMessage.create("racer:orders", "payload-1", "svc");
        RacerMessage msg3 = RacerMessage.create("racer:orders", "payload-3", "svc");

        String json1 = objectMapper.writeValueAsString(DeadLetterMessage.from(msg1, ERROR));
        String json3 = objectMapper.writeValueAsString(DeadLetterMessage.from(msg3, ERROR));

        // Middle entry is corrupted JSON — stored newest-first, so after reversal it sits between 1 and 3
        when(listOps.range(RedisChannels.DEAD_LETTER_QUEUE, 0, -1))
                .thenReturn(Flux.fromIterable(List.of(json3, "{ not valid json }", json1)));

        StepVerifier.create(service.peekAll().map(m -> m.getOriginalMessage().getPayload()))
                .expectNext("payload-1")
                .expectNext("payload-3")
                .verifyComplete();
    }
}
