package com.cheetah.racer.client.service;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.model.RacerReply;
import com.cheetah.racer.common.model.RacerRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side Pub/Sub request-reply responder.
 *
 * Flow:
 *  1. Listens on MESSAGE_CHANNEL for messages that are {@link RacerRequest} (have a replyTo field).
 *  2. Processes the request (delegates to business logic).
 *  3. Publishes a {@link RacerReply} back to the request's replyTo channel.
 *
 * Regular fire-and-forget {@link com.cheetah.racer.common.model.RacerMessage} traffic
 * is still handled by {@link ConsumerSubscriber}; this service only handles request-reply.
 */
@Slf4j
@Service
public class PubSubResponderService {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private Disposable subscription;
    private final AtomicLong repliedCount = new AtomicLong(0);

    public PubSubResponderService(
            ReactiveRedisMessageListenerContainer listenerContainer,
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper) {
        this.listenerContainer = listenerContainer;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        // Subscribe to the same message channel; requests are distinguished
        // from fire-and-forget messages by the presence of a "replyTo" field.
        subscription = listenerContainer
                .receive(ChannelTopic.of(RedisChannels.MESSAGE_CHANNEL))
                .flatMap(msg -> handlePossibleRequest(msg.getMessage()))
                .subscribe();

        log.info("[PUBSUB-RESPONDER] Listening for request-reply messages on {}", RedisChannels.MESSAGE_CHANNEL);
    }

    @PreDestroy
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        log.info("[PUBSUB-RESPONDER] Stopped. Total replies sent: {}", repliedCount.get());
    }

    /**
     * Attempt to deserialize as RacerRequest. If it has a replyTo field, treat it
     * as a request-reply; otherwise ignore (it's a regular fire-and-forget message
     * handled by ConsumerSubscriber).
     */
    private Mono<Void> handlePossibleRequest(String json) {
        return Mono.defer(() -> {
            try {
                RacerRequest request = objectMapper.readValue(json, RacerRequest.class);

                // Only process if it's a genuine request-reply (has replyTo set)
                if (request.getReplyTo() == null || request.getCorrelationId() == null) {
                    return Mono.empty(); // not a request-reply message
                }

                log.info("[PUBSUB-RESPONDER] Received request correlationId={} payload='{}'",
                        request.getCorrelationId(), request.getPayload());

                return processAndReply(request);

            } catch (JsonProcessingException e) {
                // Not a RacerRequest — silently ignore (it's probably a RacerMessage)
                return Mono.empty();
            }
        });
    }

    /**
     * Process the request and publish a reply.
     */
    private Mono<Void> processAndReply(RacerRequest request) {
        return Mono.defer(() -> {
            RacerReply reply;
            try {
                // ── Business logic goes here ──
                String result = processRequest(request);
                reply = RacerReply.success(request.getCorrelationId(), result, "racer-client");
            } catch (Exception e) {
                log.error("[PUBSUB-RESPONDER] Processing failed for correlationId={}: {}",
                        request.getCorrelationId(), e.getMessage());
                reply = RacerReply.failure(request.getCorrelationId(), e.getMessage(), "racer-client");
            }

            return publishReply(request.getReplyTo(), reply);
        });
    }

    /**
     * Simulated business logic for request processing.
     * Replace with real logic as needed.
     */
    private String processRequest(RacerRequest request) {
        // Simulate failure for payloads containing "error"
        if (request.getPayload() != null && request.getPayload().toLowerCase().contains("error")) {
            throw new RuntimeException("Simulated processing error for request: " + request.getCorrelationId());
        }

        return "Processed: " + request.getPayload() + " [echoed by racer-client]";
    }

    private Mono<Void> publishReply(String replyChannel, RacerReply reply) {
        try {
            String json = objectMapper.writeValueAsString(reply);
            return redisTemplate.convertAndSend(replyChannel, json)
                    .doOnSuccess(n -> {
                        repliedCount.incrementAndGet();
                        log.info("[PUBSUB-RESPONDER] Sent reply correlationId={} to channel={} success={}",
                                reply.getCorrelationId(), replyChannel, reply.isSuccess());
                    })
                    .then();
        } catch (JsonProcessingException e) {
            log.error("[PUBSUB-RESPONDER] Failed to serialize reply", e);
            return Mono.error(e);
        }
    }

    public long getRepliedCount() {
        return repliedCount.get();
    }
}
