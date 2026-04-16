package com.cheetah.racer.publisher;

import java.util.Map;

import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Publishes messages to Redis Streams, providing durable at-least-once
 * delivery.
 *
 * <p>
 * Unlike Pub/Sub (which drops messages if no subscriber is connected),
 * a Redis Stream retains entries until they are acknowledged by a consumer
 * group.
 * This publisher is used by
 * {@link com.cheetah.racer.aspect.PublishResultAspect}
 * when {@code @PublishResult(durable = true)}.
 *
 * <h3>Entry format</h3>
 * Each stream entry contains a single field {@code "data"} whose value is a
 * JSON object:
 * 
 * <pre>
 * {
 *   "id":        "&lt;uuid&gt;",
 *   "sender":    "my-service",
 *   "timestamp": "2026-03-01T12:00:00Z",
 *   "payload":   { ...original object... }
 * }
 * </pre>
 */
@Slf4j
public class RacerStreamPublisher {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final long streamMaxLen;
    /** Pre-computed XADD options — avoids per-publish allocation (#9). */
    private final RedisStreamCommands.XAddOptions xAddOptions;

    public RacerStreamPublisher(ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, 10_000L);
    }

    public RacerStreamPublisher(ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            long streamMaxLen) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.streamMaxLen = streamMaxLen;
        this.xAddOptions = streamMaxLen > 0
                ? RedisStreamCommands.XAddOptions.maxlen(streamMaxLen).approximateTrimming(true)
                : RedisStreamCommands.XAddOptions.none();
    }

    /**
     * Publishes {@code payload} as a new entry on the given Redis Stream key.
     *
     * @param streamKey the Redis Stream key (e.g. {@code "racer:orders:stream"})
     * @param payload   the object to serialize and write to the stream
     * @param sender    sender identifier embedded in the stream entry envelope
     * @return Mono of the assigned stream entry {@link RecordId}
     */
    public Mono<RecordId> publishToStream(String streamKey, Object payload, String sender) {
        return MessageEnvelopeBuilder.buildStream(objectMapper,
                sender != null ? sender : "system", payload)
                .flatMap(json -> {
                    MapRecord<String, String, String> record = MapRecord.create(
                            streamKey, Map.of("data", json));
                    return redisTemplate.opsForStream().add(record, xAddOptions);
                })
                .doOnSuccess(id -> log.debug("[racer-stream] Published to '{}' → entry {}", streamKey, id))
                .doOnError(ex -> log.error("[racer-stream] Failed to publish to '{}': {}", streamKey, ex.getMessage()));
    }
}
