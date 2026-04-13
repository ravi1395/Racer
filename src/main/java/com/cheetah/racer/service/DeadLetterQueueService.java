package com.cheetah.racer.service;

import com.cheetah.racer.RedisChannels;
import com.cheetah.racer.listener.RacerDeadLetterHandler;
import com.cheetah.racer.model.DeadLetterMessage;
import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Manages the Dead Letter Queue (DLQ) in Redis.
 *
 * <p>Failed messages are pushed to a Redis List ({@code racer:dlq}) for later inspection
 * and reprocessing. Implements {@link RacerDeadLetterHandler} so that all annotation-driven
 * consumers ({@code @RacerListener}, {@code @RacerStreamListener}, {@code @RacerResponder})
 * can forward failed messages automatically.
 *
 * <p>Registered as a Spring bean by {@link com.cheetah.racer.config.RacerAutoConfiguration}.
 */
@Slf4j
public class DeadLetterQueueService implements RacerDeadLetterHandler {

    /** Default maximum DLQ entries; older entries are trimmed on each enqueue. */
    public static final long DEFAULT_MAX_SIZE = 10_000;

    private final ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final long maxSize;

    public DeadLetterQueueService(ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
                                   ObjectMapper objectMapper) {
        this(reactiveStringRedisTemplate, objectMapper, DEFAULT_MAX_SIZE);
    }

    public DeadLetterQueueService(ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
                                   ObjectMapper objectMapper,
                                   long maxSize) {
        this.reactiveStringRedisTemplate = reactiveStringRedisTemplate;
        this.objectMapper = objectMapper;
        this.maxSize = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
    }

    /**
     * Push a failed message to the DLQ (LPUSH — newest entries first).
     *
     * <p>Logs at WARN before the push so that any DLQ entry is always accompanied by
     * a log line — even if the downstream Redis call fails.  After a successful push
     * an INFO confirmation is emitted that includes the channel and the failure reason,
     * making it easy to correlate log lines with DLQ entries during incidents.
     */
    @Override
    public Mono<Long> enqueue(RacerMessage message, Throwable error) {
        DeadLetterMessage dlm = DeadLetterMessage.from(message, error);
        // Capture the reason string once so the lambda below does not reference the Throwable
        String reason = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(dlm))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(json -> log.warn("[RACER-DLQ] Enqueuing failed message id={} channel='{}' reason='{}'",
                        message.getId(), message.getChannel(), reason))
                .flatMap(json -> reactiveStringRedisTemplate.opsForList()
                        .leftPush(RedisChannels.DEAD_LETTER_QUEUE, json)
                        .flatMap(size -> {
                            // Confirm successful enqueue at INFO so it is visible in default log configs
                            log.info("[RACER-DLQ] Enqueued message id={} channel='{}' — reason: {}. DLQ size: {}",
                                    message.getId(), message.getChannel(), reason, size);
                            if (size > maxSize) {
                                return reactiveStringRedisTemplate.opsForList()
                                        .trim(RedisChannels.DEAD_LETTER_QUEUE, 0, maxSize - 1)
                                        .doOnSuccess(v -> log.warn("[RACER-DLQ] Trimmed DLQ to {} entries (was {})", maxSize, size))
                                        .thenReturn(size);
                            }
                            return Mono.just(size);
                        }))
                .onErrorResume(JsonProcessingException.class, e -> {
                    log.error("[RACER-DLQ] Failed to serialize dead letter message id={}: {}", message.getId(), e.getMessage(), e);
                    return Mono.error(e);
                });
    }

    /**
     * Pop a message from the DLQ for reprocessing (FIFO order via rightPop).
     */
    public Mono<DeadLetterMessage> dequeue() {
        return reactiveStringRedisTemplate.opsForList()
                .rightPop(RedisChannels.DEAD_LETTER_QUEUE)
                .flatMap(this::deserializeDlm);
    }

    /**
     * Peek at all messages currently in the DLQ without removing them.
     */
    public Flux<DeadLetterMessage> peekAll() {
        return reactiveStringRedisTemplate.opsForList()
                .range(RedisChannels.DEAD_LETTER_QUEUE, 0, -1)
                .flatMap(json -> deserializeDlm(json).onErrorResume(e -> Mono.empty()));
    }

    private Mono<DeadLetterMessage> deserializeDlm(String json) {
        return Mono.fromCallable(() -> objectMapper.readValue(json, DeadLetterMessage.class))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(JsonProcessingException.class, e -> {
                    log.error("[DLQ] Failed to deserialize dead letter message", e);
                    return Mono.error(e);
                });
    }

    /**
     * Returns the current number of entries in the DLQ.
     */
    public Mono<Long> size() {
        return reactiveStringRedisTemplate.opsForList()
                .size(RedisChannels.DEAD_LETTER_QUEUE);
    }

    /**
     * Removes all messages from the DLQ.
     */
    public Mono<Boolean> clear() {
        return reactiveStringRedisTemplate.delete(RedisChannels.DEAD_LETTER_QUEUE)
                .map(count -> count > 0)
                .doOnSuccess(cleared -> log.info("[DLQ] Queue cleared: {}", cleared));
    }
}
