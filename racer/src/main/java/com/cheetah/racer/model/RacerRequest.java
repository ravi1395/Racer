package com.cheetah.racer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

import com.cheetah.racer.publisher.MessageEnvelopeBuilder;

/**
 * A request message that expects a reply.
 * Carries a correlationId so the reply can be matched back to this request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RacerRequest implements Serializable {

    /** Unique correlation id — links a request to its reply */
    private String correlationId;

    /** The channel or stream the request was sent through */
    private String channel;

    /** Free-form payload */
    private String payload;

    /** Who sent the request */
    private String sender;

    /** When the request was created */
    private Instant timestamp;

    /**
     * Where the reply should be sent (Pub/Sub reply channel or response stream key)
     */
    private String replyTo;

    public static RacerRequest create(String payload, String sender) {
        String cid = MessageEnvelopeBuilder.generateId();
        return RacerRequest.builder()
                .correlationId(cid)
                .payload(payload)
                .sender(sender)
                .timestamp(Instant.now())
                .build();
    }
}
