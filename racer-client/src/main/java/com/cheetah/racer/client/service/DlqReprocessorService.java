package com.cheetah.racer.client.service;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.metrics.RacerMetrics;
import com.cheetah.racer.common.model.DeadLetterMessage;
import com.cheetah.racer.common.model.RacerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Service to reprocess messages from the Dead Letter Queue.
 * Dequeues messages and re-attempts processing. If processing fails again
 * and max retries are exceeded, the message is permanently discarded (or logged).
 */
@Slf4j
@Service
public class DlqReprocessorService {

    private final DeadLetterQueueService dlqService;
    private final MessageProcessor syncProcessor;
    private final MessageProcessor asyncProcessor;
    private final ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Nullable
    @Autowired(required = false)
    private RacerMetrics racerMetrics;

    private final AtomicLong reprocessedCount = new AtomicLong(0);
    private final AtomicLong permanentlyFailedCount = new AtomicLong(0);

    public DlqReprocessorService(
            DeadLetterQueueService dlqService,
            @Qualifier("syncProcessor") MessageProcessor syncProcessor,
            @Qualifier("asyncProcessor") MessageProcessor asyncProcessor,
            ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate,
            ObjectMapper objectMapper) {
        this.dlqService = dlqService;
        this.syncProcessor = syncProcessor;
        this.asyncProcessor = asyncProcessor;
        this.reactiveStringRedisTemplate = reactiveStringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Reprocess a single message from the DLQ.
     * Returns true if a message was dequeued and reprocessed, false if queue was empty.
     */
    public Mono<Boolean> reprocessOne(String mode) {
        return dlqService.dequeue()
                .flatMap(dlm -> reprocessMessage(dlm, mode))
                .defaultIfEmpty(false);
    }

    /**
     * Reprocess all messages currently in the DLQ.
     * Returns the count of messages reprocessed.
     */
    public Mono<Long> reprocessAll(String mode) {
        return dlqService.size()
                .flatMap(size -> {
                    if (size == 0) {
                        log.info("[DLQ-REPROCESSOR] Queue is empty, nothing to reprocess");
                        return Mono.just(0L);
                    }
                    log.info("[DLQ-REPROCESSOR] Starting reprocessing of {} messages in {} mode", size, mode);
                    return reprocessBatch(size, mode);
                });
    }

    private Mono<Long> reprocessBatch(long count, String mode) {
        if (count <= 0) return Mono.just(0L);

        return dlqService.dequeue()
                .flatMap(dlm -> reprocessMessage(dlm, mode)
                        .flatMap(success -> reprocessBatch(count - 1, mode)
                                .map(remaining -> remaining + (success ? 1 : 0))))
                .defaultIfEmpty(0L);
    }

    private Mono<Boolean> reprocessMessage(DeadLetterMessage dlm, String mode) {
        RacerMessage message = dlm.getOriginalMessage();
        message.setRetryCount(message.getRetryCount() + 1);

        if (message.getRetryCount() > RedisChannels.MAX_RETRY_ATTEMPTS) {
            permanentlyFailedCount.incrementAndGet();
            log.error("[DLQ-REPROCESSOR] Message id={} exceeded max retries ({}). Permanently discarding.",
                    message.getId(), RedisChannels.MAX_RETRY_ATTEMPTS);
            return Mono.just(false);
        }

        log.info("[DLQ-REPROCESSOR] Reprocessing message id={} attempt={}/{}",
                message.getId(), message.getRetryCount(), RedisChannels.MAX_RETRY_ATTEMPTS);

        MessageProcessor processor = "SYNC".equalsIgnoreCase(mode) ? syncProcessor : asyncProcessor;

        return processor.process(message)
                .doOnSuccess(v -> {
                    reprocessedCount.incrementAndGet();
                    log.info("[DLQ-REPROCESSOR] Successfully reprocessed message id={}", message.getId());
                    if (racerMetrics != null) {
                        racerMetrics.recordDlqReprocessed();
                    }
                })
                .thenReturn(true)
                .onErrorResume(error -> {
                    log.error("[DLQ-REPROCESSOR] Reprocess failed for message id={}: {}",
                            message.getId(), error.getMessage());
                    // Re-enqueue with updated retry count
                    return dlqService.enqueue(message, error).thenReturn(false);
                });
    }

    /**
     * Republish a DLQ message back to the original Pub/Sub channel.
     * This sends it back through the normal pipeline instead of direct processing.
     */
    public Mono<Long> republishOne() {
        return dlqService.dequeue()
                .flatMap(dlm -> {
                    RacerMessage message = dlm.getOriginalMessage();
                    message.setRetryCount(message.getRetryCount() + 1);

                    if (message.getRetryCount() > RedisChannels.MAX_RETRY_ATTEMPTS) {
                        permanentlyFailedCount.incrementAndGet();
                        log.error("[DLQ-REPROCESSOR] Message id={} exceeded max retries. Discarding.", message.getId());
                        return Mono.just(0L);
                    }

                    try {
                        String json = objectMapper.writeValueAsString(message);
                        return reactiveStringRedisTemplate.convertAndSend(message.getChannel(), json)
                                .doOnSuccess(count -> log.info("[DLQ-REPROCESSOR] Republished message id={} to channel={}", 
                                        message.getId(), message.getChannel()));
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                })
                .defaultIfEmpty(0L);
    }

    public long getReprocessedCount() {
        return reprocessedCount.get();
    }

    public long getPermanentlyFailedCount() {
        return permanentlyFailedCount.get();
    }
}
