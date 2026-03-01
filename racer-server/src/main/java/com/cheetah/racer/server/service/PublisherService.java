package com.cheetah.racer.server.service;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.model.RacerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service responsible for publishing messages to Redis Pub/Sub channels.
 * Supports both synchronous (blocking) and asynchronous (non-blocking) publish.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublisherService {

    private final ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Publish a message asynchronously (non-blocking, returns immediately).
     * Returns a Mono that completes when the message is published.
     */
    public Mono<Long> publishAsync(String channel, String payload, String sender) {
        RacerMessage message = RacerMessage.create(channel, payload, sender);
        return serializeAndPublish(channel, message)
                .doOnSuccess(count -> log.info("[ASYNC] Published message id={} to channel={}, subscribers={}",
                        message.getId(), channel, count))
                .doOnError(e -> log.error("[ASYNC] Failed to publish message to channel={}", channel, e));
    }

    /**
     * Publish a message synchronously (blocks until published).
     * Uses .block() to wait for the reactive chain to complete.
     */
    public Long publishSync(String channel, String payload, String sender) {
        RacerMessage message = RacerMessage.create(channel, payload, sender);
        Long subscriberCount = serializeAndPublish(channel, message).block();
        log.info("[SYNC] Published message id={} to channel={}, subscribers={}",
                message.getId(), channel, subscriberCount);
        return subscriberCount;
    }

    /**
     * Publish to the default MESSAGE_CHANNEL asynchronously.
     */
    public Mono<Long> publishToDefaultChannel(String payload, String sender) {
        return publishAsync(RedisChannels.MESSAGE_CHANNEL, payload, sender);
    }

    /**
     * Publish a pre-built RacerMessage (used for DLQ republishing).
     */
    public Mono<Long> republish(RacerMessage message) {
        return serializeAndPublish(message.getChannel(), message)
                .doOnSuccess(count -> log.info("[REPUBLISH] Message id={} republished to channel={}", 
                        message.getId(), message.getChannel()));
    }

    private Mono<Long> serializeAndPublish(String channel, RacerMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            return reactiveStringRedisTemplate.convertAndSend(channel, json);
        } catch (JsonProcessingException e) {
            return Mono.error(new RuntimeException("Failed to serialize message", e));
        }
    }
}
