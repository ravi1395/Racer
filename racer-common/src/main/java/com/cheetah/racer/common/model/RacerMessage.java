package com.cheetah.racer.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a message exchanged via Redis Pub/Sub.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RacerMessage implements Serializable {

    private String id;
    private String channel;
    private String payload;
    private String sender;
    private Instant timestamp;
    private int retryCount;

    /**
     * Factory method to create a new message with auto-generated id and timestamp.
     */
    public static RacerMessage create(String channel, String payload, String sender) {
        return RacerMessage.builder()
                .id(UUID.randomUUID().toString())
                .channel(channel)
                .payload(payload)
                .sender(sender)
                .timestamp(Instant.now())
                .retryCount(0)
                .build();
    }
}
