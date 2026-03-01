package com.cheetah.racer.client.service;

import com.cheetah.racer.common.model.RacerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumer-group reader for durable Redis Streams published via
 * {@code @PublishResult(durable = true)}.
 *
 * <p>Starts a polling loop that reads from each configured durable stream key using
 * the consumer group {@code racer-durable-consumers}. Deserialized messages are
 * dispatched to the active {@link MessageProcessor}; failures are routed to the
 * {@link DeadLetterQueueService}. Each successfully processed entry is ACKed.
 *
 * <h3>Stream key discovery</h3>
 * Stream keys are listed in {@code racer.durable.stream-keys} (comma-separated).
 * Example: {@code racer.durable.stream-keys=racer:orders:stream,racer:audit:stream}
 */
@Slf4j
@Service
public class RacerStreamConsumerService {

    private static final String CONSUMER_GROUP  = "racer-durable-consumers";
    private static final String CONSUMER_NAME   = "racer-durable-consumer-1";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MessageProcessor syncProcessor;
    private final MessageProcessor asyncProcessor;
    private final DeadLetterQueueService dlqService;

    @Value("${racer.durable.stream-keys:}")
    private String streamKeysConfig;

    @Value("${racer.client.processing-mode:ASYNC}")
    private String processingMode;

    private Disposable pollingSubscription;
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong failedCount    = new AtomicLong(0);

    public RacerStreamConsumerService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            @Qualifier("syncProcessor")  MessageProcessor syncProcessor,
            @Qualifier("asyncProcessor") MessageProcessor asyncProcessor,
            DeadLetterQueueService dlqService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper  = objectMapper;
        this.syncProcessor = syncProcessor;
        this.asyncProcessor = asyncProcessor;
        this.dlqService    = dlqService;
    }

    @PostConstruct
    public void start() {
        List<String> streamKeys = resolveStreamKeys();
        if (streamKeys.isEmpty()) {
            log.info("[DURABLE-CONSUMER] No durable stream keys configured — consumer not started. " +
                     "Set racer.durable.stream-keys to enable.");
            return;
        }

        log.info("[DURABLE-CONSUMER] Starting for streams: {}", streamKeys);
        streamKeys.forEach(key ->
                ensureConsumerGroup(key)
                        .doOnSuccess(v -> log.info("[DURABLE-CONSUMER] Group '{}' ready on '{}'", CONSUMER_GROUP, key))
                        .doOnError(e -> log.error("[DURABLE-CONSUMER] Failed to init group on '{}': {}", key, e.getMessage()))
                        .subscribe());

        pollingSubscription = pollAllStreams(streamKeys)
                .repeatWhen(flux -> flux.delayElements(POLL_INTERVAL))
                .subscribe();
    }

    @PreDestroy
    public void stop() {
        if (pollingSubscription != null && !pollingSubscription.isDisposed()) {
            pollingSubscription.dispose();
        }
        log.info("[DURABLE-CONSUMER] Stopped. Processed={}, Failed={}", processedCount.get(), failedCount.get());
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private Mono<Void> pollAllStreams(List<String> streamKeys) {
        return reactor.core.publisher.Flux.fromIterable(streamKeys)
                .flatMap(this::pollOnce)
                .then();
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> pollOnce(String streamKey) {
        return redisTemplate.opsForStream()
                .read(Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed()))
                .next()
                .flatMap(record -> {
                    Map<String, String> body = (Map<String, String>) (Map<?, ?>) record.getValue();
                    String data = body.get("data");
                    if (data == null) {
                        log.warn("[DURABLE-CONSUMER] Stream entry {} has no 'data' field — skipping", record.getId());
                        return ackRecord(streamKey, record.getId());
                    }

                    return processEntry(streamKey, record.getId(), data);
                })
                .then();
    }

    private Mono<Void> processEntry(String streamKey, RecordId recordId, String envelopeJson) {
        return Mono.defer(() -> {
            try {
                // envelope: { id, sender, timestamp, payload: <RacerMessage json> }
                @SuppressWarnings("unchecked")
                Map<String, Object> envelope = objectMapper.readValue(envelopeJson, Map.class);
                Object payloadObj = envelope.get("payload");
                String payloadJson = payloadObj instanceof String
                        ? (String) payloadObj
                        : objectMapper.writeValueAsString(payloadObj);

                RacerMessage message = objectMapper.readValue(payloadJson, RacerMessage.class);
                log.debug("[DURABLE-CONSUMER] Processing stream entry recordId={} messageId={}",
                        recordId, message.getId());

                MessageProcessor processor = "SYNC".equalsIgnoreCase(processingMode) ? syncProcessor : asyncProcessor;
                return processor.process(message)
                        .doOnSuccess(v -> {
                            processedCount.incrementAndGet();
                            log.debug("[DURABLE-CONSUMER] Processed recordId={}", recordId);
                        })
                        .then(ackRecord(streamKey, recordId))
                        .onErrorResume(error -> {
                            failedCount.incrementAndGet();
                            log.error("[DURABLE-CONSUMER] Processing failed recordId={}: {}", recordId, error.getMessage());
                            RacerMessage failedMsg = new RacerMessage();
                            failedMsg.setId(recordId.getValue());
                            failedMsg.setPayload(envelopeJson);
                            return dlqService.enqueue(failedMsg, error)
                                    .then(ackRecord(streamKey, recordId)); // ACK to prevent infinite redelivery
                        });

            } catch (JsonProcessingException e) {
                log.error("[DURABLE-CONSUMER] Deserialization failed for recordId={}: {}", recordId, e.getMessage());
                return ackRecord(streamKey, recordId); // skip malformed entries
            }
        });
    }

    private Mono<Void> ackRecord(String streamKey, RecordId recordId) {
        return redisTemplate.opsForStream()
                .acknowledge(streamKey, CONSUMER_GROUP, recordId)
                .doOnSuccess(n -> log.trace("[DURABLE-CONSUMER] ACKed recordId={}", recordId))
                .then();
    }

    private Mono<Void> ensureConsumerGroup(String streamKey) {
        return redisTemplate.opsForStream()
                .createGroup(streamKey, ReadOffset.from("0"), CONSUMER_GROUP)
                .then()
                .onErrorResume(e -> {
                    if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                        return Mono.empty(); // already exists — fine
                    }
                    return Mono.error(e);
                });
    }

    private List<String> resolveStreamKeys() {
        if (streamKeysConfig == null || streamKeysConfig.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(streamKeysConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public long getProcessedCount() { return processedCount.get(); }
    public long getFailedCount()    { return failedCount.get(); }
}
