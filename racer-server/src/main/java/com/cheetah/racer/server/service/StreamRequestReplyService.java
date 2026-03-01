package com.cheetah.racer.server.service;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.metrics.RacerMetrics;
import com.cheetah.racer.common.model.RacerReply;
import com.cheetah.racer.common.model.RacerRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Server-side Streams request-reply.
 *
 * Flow:
 *  1. Server writes a request to {@code racer:stream:requests} with the correlationId.
 *  2. The response stream key is {@code racer:stream:response:<correlationId>}.
 *  3. Server polls the response stream until a reply appears (or timeout).
 *
 * The client reads from the request stream via a consumer group, processes the
 * request, and writes the reply to the response stream.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamRequestReplyService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Nullable
    @Autowired(required = false)
    private RacerMetrics racerMetrics;

    /**
     * Send a request via Redis Streams and wait for a reply on the response stream.
     */
    public Mono<RacerReply> requestReply(String payload, String sender, Duration timeout) {
        RacerRequest request = RacerRequest.create(payload, sender);
        String responseStreamKey = RedisChannels.RESPONSE_STREAM_PREFIX + request.getCorrelationId();
        request.setReplyTo(responseStreamKey);
        request.setChannel(RedisChannels.REQUEST_STREAM);

        io.micrometer.core.instrument.Timer.Sample sample =
                (racerMetrics != null) ? racerMetrics.startRequestReplyTimer() : null;

        log.info("[STREAM-REQ] Sending request correlationId={} responseStream={}",
                request.getCorrelationId(), responseStreamKey);

        return writeRequestToStream(request)
                .then(pollForReply(responseStreamKey, timeout))
                .doOnNext(reply -> {
                    if (sample != null && racerMetrics != null) {
                        racerMetrics.stopRequestReplyTimer(sample, "stream");
                    }
                })
                .doFinally(signal -> {
                    // Clean up the ephemeral response stream
                    redisTemplate.delete(responseStreamKey).subscribe();
                });
    }

    /**
     * Convenience overload with default timeout.
     */
    public Mono<RacerReply> requestReply(String payload, String sender) {
        return requestReply(payload, sender,
                Duration.ofSeconds(RedisChannels.REPLY_TIMEOUT_SECONDS));
    }

    /**
     * Write the request as a Stream entry on the REQUEST_STREAM.
     */
    private Mono<String> writeRequestToStream(RacerRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            MapRecord<String, String, String> record = MapRecord.create(
                    RedisChannels.REQUEST_STREAM,
                    Map.of(
                            "correlationId", request.getCorrelationId(),
                            "replyTo", request.getReplyTo(),
                            "payload", json
                    )
            );
            return redisTemplate.opsForStream()
                    .add(record)
                    .map(Object::toString)
                    .doOnSuccess(id -> log.debug("[STREAM-REQ] Written to stream, recordId={}", id));
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("Failed to serialize request", e));
        }
    }

    /**
     * Poll the response stream for a reply entry.
     * Uses reactive retry with delay until the entry appears or timeout expires.
     */
    private Mono<RacerReply> pollForReply(String responseStreamKey, Duration timeout) {
        Duration pollInterval = Duration.ofMillis(200);
        int maxAttempts = (int) (timeout.toMillis() / pollInterval.toMillis());

        return redisTemplate.opsForStream()
                .read(StreamOffset.fromStart(responseStreamKey))
                .next()
                .flatMap(record -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> body = (Map<String, String>) (Map<?, ?>) record.getValue();
                    String replyJson = body.get("payload");
                    try {
                        RacerReply reply = objectMapper.readValue(replyJson, RacerReply.class);
                        log.info("[STREAM-REQ] Received reply correlationId={} success={}",
                                reply.getCorrelationId(), reply.isSuccess());
                        return Mono.just(reply);
                    } catch (JsonProcessingException e) {
                        return Mono.error(new RuntimeException("Failed to deserialize stream reply", e));
                    }
                })
                .repeatWhenEmpty(maxAttempts, flux -> flux.delayElements(pollInterval))
                .switchIfEmpty(Mono.error(
                        new RuntimeException("Timeout waiting for stream reply on " + responseStreamKey)));
    }
}
