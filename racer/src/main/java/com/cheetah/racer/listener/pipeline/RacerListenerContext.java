package com.cheetah.racer.listener.pipeline;

import java.lang.reflect.Method;

import org.springframework.lang.Nullable;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable context carried through each stage of a {@link DispatchPipeline}.
 *
 * <p>
 * Stages read this context to access metadata about the current dispatch
 * (listener ID, channel, handler method) without needing additional parameters.
 * The optional {@link #parsedPayload} carries a pre-parsed JSON tree of the
 * message payload so that multiple pipeline stages can share a single parse
 * instead of each independently deserializing the raw string.
 *
 * @param listenerId    resolved listener identifier (from
 *                      {@code @RacerListener#id()})
 * @param channel       the Redis Pub/Sub or Stream channel the message arrived
 *                      on
 * @param method        the {@code @RacerListener}-annotated handler method
 * @param parsedPayload pre-parsed JSON tree of the message payload;
 *                      {@code null} for non-JSON or unresolvable payloads
 */
public record RacerListenerContext(String listenerId, String channel, Method method,
        @Nullable JsonNode parsedPayload) {

    /**
     * Convenience constructor for contexts where no pre-parsed payload is
     * available (registration-time and test contexts).
     *
     * @param listenerId resolved listener identifier
     * @param channel    the Redis channel
     * @param method     the handler method
     */
    public RacerListenerContext(String listenerId, String channel, Method method) {
        this(listenerId, channel, method, null);
    }
}
