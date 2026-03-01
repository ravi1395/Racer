package com.cheetah.racer.client.service;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.model.DeadLetterMessage;
import com.cheetah.racer.common.model.RacerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Manages the Dead Letter Queue (DLQ) in Redis.
 * Failed messages are pushed to a Redis List for later reprocessing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterQueueService {

    private final ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Push a failed message to the Dead Letter Queue.
     */
    public Mono<Long> enqueue(RacerMessage message, Throwable error) {
        DeadLetterMessage dlm = DeadLetterMessage.from(message, error);
        try {
            String json = objectMapper.writeValueAsString(dlm);
            log.warn("[DLQ] Enqueuing failed message id={} error='{}'", message.getId(), error.getMessage());
            return reactiveStringRedisTemplate.opsForList()
                    .leftPush(RedisChannels.DEAD_LETTER_QUEUE, json)
                    .doOnSuccess(size -> log.info("[DLQ] Queue size after enqueue: {}", size));
        } catch (JsonProcessingException e) {
            log.error("[DLQ] Failed to serialize dead letter message", e);
            return Mono.error(e);
        }
    }

    /**
     * Pop a message from the DLQ for reprocessing (FIFO order via rightPop).
     */
    public Mono<DeadLetterMessage> dequeue() {
        return reactiveStringRedisTemplate.opsForList()
                .rightPop(RedisChannels.DEAD_LETTER_QUEUE)
                .flatMap(json -> {
                    try {
                        return Mono.just(objectMapper.readValue(json, DeadLetterMessage.class));
                    } catch (JsonProcessingException e) {
                        log.error("[DLQ] Failed to deserialize dead letter message", e);
                        return Mono.error(e);
                    }
                });
    }

    /**
     * Peek at all messages currently in the DLQ without removing them.
     */
    public Flux<DeadLetterMessage> peekAll() {
        return reactiveStringRedisTemplate.opsForList()
                .range(RedisChannels.DEAD_LETTER_QUEUE, 0, -1)
                .flatMap(json -> {
                    try {
                        return Mono.just(objectMapper.readValue(json, DeadLetterMessage.class));
                    } catch (JsonProcessingException e) {
                        log.error("[DLQ] Failed to deserialize dead letter message", e);
                        return Mono.empty();
                    }
                });
    }

    /**
     * Get the current size of the DLQ.
     */
    public Mono<Long> size() {
        return reactiveStringRedisTemplate.opsForList()
                .size(RedisChannels.DEAD_LETTER_QUEUE);
    }

    /**
     * Clear all messages from the DLQ.
     */
    public Mono<Boolean> clear() {
        return reactiveStringRedisTemplate.delete(RedisChannels.DEAD_LETTER_QUEUE)
                .map(count -> count > 0)
                .doOnSuccess(cleared -> log.info("[DLQ] Queue cleared: {}", cleared));
    }
}
