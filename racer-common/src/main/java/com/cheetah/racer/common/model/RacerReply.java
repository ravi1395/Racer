package com.cheetah.racer.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * A reply message sent back in response to a {@link RacerRequest}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RacerReply implements Serializable {

    /** Must match the correlationId of the originating request */
    private String correlationId;

    /** The reply payload */
    private String payload;

    /** Who produced the reply */
    private String responder;

    /** Whether the request was processed successfully */
    private boolean success;

    /** Error message if processing failed */
    private String errorMessage;

    /** When the reply was created */
    private Instant timestamp;

    public static RacerReply success(String correlationId, String payload, String responder) {
        return RacerReply.builder()
                .correlationId(correlationId)
                .payload(payload)
                .responder(responder)
                .success(true)
                .timestamp(Instant.now())
                .build();
    }

    public static RacerReply failure(String correlationId, String errorMessage, String responder) {
        return RacerReply.builder()
                .correlationId(correlationId)
                .errorMessage(errorMessage)
                .responder(responder)
                .success(false)
                .timestamp(Instant.now())
                .build();
    }
}
