package com.cheetah.racer.client.service;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.model.RacerReply;
import com.cheetah.racer.common.model.RacerRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side Redis Streams request-reply responder.
 *
 * Flow:
 *  1. Reads entries from {@code racer:stream:requests} using a consumer group.
 *  2. Deserializes the {@link RacerRequest}.
 *  3. Processes the request (business logic).
 *  4. Writes a {@link RacerReply} to the response stream specified in {@code replyTo}.
 *  5. ACKs the stream entry.
 */
@Slf4j
@Service
public class StreamResponderService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private Disposable pollingSubscription;
    private final AtomicLong processedCount = new AtomicLong(0);

    private static final String CONSUMER_NAME = "client-1";

    public StreamResponderService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        ensureConsumerGroup()
                .doOnSuccess(v -> {
                    log.info("[STREAM-RESPONDER] Consumer group '{}' ready on stream '{}'",
                            RedisChannels.STREAM_CONSUMER_GROUP, RedisChannels.REQUEST_STREAM);
                    startPolling();
                })
                .doOnError(e -> log.error("[STREAM-RESPONDER] Failed to create consumer group", e))
                .subscribe();
    }

    @PreDestroy
    public void stop() {
        if (pollingSubscription != null && !pollingSubscription.isDisposed()) {
            pollingSubscription.dispose();
        }
        log.info("[STREAM-RESPONDER] Stopped. Processed {} stream requests", processedCount.get());
    }

    /**
     * Create the consumer group if it doesn't exist.
     * Uses MKSTREAM to auto-create the stream if absent.
     */
    private Mono<Void> ensureConsumerGroup() {
        return redisTemplate.opsForStream()
                .createGroup(RedisChannels.REQUEST_STREAM, ReadOffset.from("0"),
                        RedisChannels.STREAM_CONSUMER_GROUP)
                .then()
                .onErrorResume(e -> {
                    // "BUSYGROUP" = group already exists — that's fine
                    if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                        return Mono.empty();
                    }
                    return Mono.error(e);
                });
    }

    /**
     * Continuously poll the request stream for new entries.
     */
    private void startPolling() {
        pollingSubscription = pollOnce()
                .repeatWhen(flux -> flux.delayElements(Duration.ofMillis(100)))
                .subscribe();
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> pollOnce() {
        return redisTemplate.opsForStream()
                .read(Consumer.from(RedisChannels.STREAM_CONSUMER_GROUP, CONSUMER_NAME),
                        StreamOffset.create(RedisChannels.REQUEST_STREAM, ReadOffset.lastConsumed()))
                .next()
                .flatMap(record -> {
                    Map<String, String> body = (Map<String, String>) (Map<?, ?>) record.getValue();
                    String payloadJson = body.get("payload");
                    String correlationId = body.get("correlationId");
                    String replyTo = body.get("replyTo");

                    log.info("[STREAM-RESPONDER] Received stream request correlationId={}", correlationId);

                    return processStreamRequest(payloadJson, replyTo)
                            .then(ackRecord(record))
                            .doOnSuccess(v -> processedCount.incrementAndGet());
                })
                .then(); // empty if no records available — loop continues
    }

    private Mono<Void> processStreamRequest(String payloadJson, String replyTo) {
        return Mono.defer(() -> {
            try {
                RacerRequest request = objectMapper.readValue(payloadJson, RacerRequest.class);
                RacerReply reply;

                try {
                    String result = processRequest(request);
                    reply = RacerReply.success(request.getCorrelationId(), result, "racer-client-stream");
                } catch (Exception ex) {
                    log.error("[STREAM-RESPONDER] Processing failed for correlationId={}: {}",
                            request.getCorrelationId(), ex.getMessage());
                    reply = RacerReply.failure(request.getCorrelationId(), ex.getMessage(), "racer-client-stream");
                }

                return writeReplyToStream(replyTo, reply);

            } catch (JsonProcessingException e) {
                log.error("[STREAM-RESPONDER] Failed to deserialize request", e);
                return Mono.empty();
            }
        });
    }

    /**
     * Business logic — replace with real processing.
     */
    private String processRequest(RacerRequest request) {
        if (request.getPayload() != null && request.getPayload().toLowerCase().contains("error")) {
            throw new RuntimeException("Simulated stream processing error for: " + request.getCorrelationId());
        }
        return "Stream-processed: " + request.getPayload() + " [by racer-client-stream]";
    }

    private Mono<Void> writeReplyToStream(String responseStreamKey, RacerReply reply) {
        try {
            String json = objectMapper.writeValueAsString(reply);
            MapRecord<String, String, String> record = MapRecord.create(
                    responseStreamKey,
                    Map.of(
                            "correlationId", reply.getCorrelationId(),
                            "payload", json
                    )
            );
            return redisTemplate.opsForStream()
                    .add(record)
                    .doOnSuccess(id -> log.info("[STREAM-RESPONDER] Reply written to {} recordId={}",
                            responseStreamKey, id))
                    .then();
        } catch (JsonProcessingException e) {
            log.error("[STREAM-RESPONDER] Failed to serialize reply", e);
            return Mono.error(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Mono<Void> ackRecord(MapRecord<String, Object, Object> record) {
        return redisTemplate.opsForStream()
                .acknowledge(RedisChannels.REQUEST_STREAM,
                        RedisChannels.STREAM_CONSUMER_GROUP,
                        record.getId().getValue())
                .then();
    }

    public long getProcessedCount() {
        return processedCount.get();
    }
}
