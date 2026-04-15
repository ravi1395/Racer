package com.cheetah.racer.publisher;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Value;

/**
 * Typed POJO representing the standard Racer wire envelope.
 *
 * <p>Replaces the previous {@code LinkedHashMap<String, Object>} approach in
 * {@link MessageEnvelopeBuilder}. Jackson can serialize a POJO with known field
 * types significantly faster than a Map, because it uses compile-time field type
 * information to select serializers at initialization rather than inspecting each
 * value's runtime class per message.
 *
 * <p>Optional fields ({@code channel}, {@code routed}, {@code traceparent},
 * {@code priority}) are annotated with {@code @JsonInclude(NON_NULL)} so they are
 * omitted from the JSON output when not set, preserving byte-for-byte wire
 * compatibility with the previous Map-based envelope across all builder overloads.
 *
 * <p>The {@code @JsonPropertyOrder} annotation pins field order in the JSON
 * output to match the insertion order used by the legacy LinkedHashMap builders.
 */
@Value
@JsonPropertyOrder({"id", "channel", "sender", "timestamp", "payload", "routed", "traceparent", "priority"})
class RacerEnvelope {

    /** Unique message identifier (UUID-format string or caller-supplied key). */
    String id;

    /**
     * Channel / topic name.  Absent for stream envelopes ({@link MessageEnvelopeBuilder#buildStream}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String channel;

    /** Originator service identifier. */
    String sender;

    /** ISO-8601 instant at which the envelope was created. */
    String timestamp;

    /** Serialized payload — always a JSON string, never a nested JSON object. */
    String payload;

    /**
     * Set to {@code true} when the message was forwarded by the router.
     * Omitted (null) for non-routed messages so the field is absent in the JSON output.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Boolean routed;

    /**
     * W3C {@code traceparent} header value for distributed tracing.
     * Omitted when tracing is disabled or not applicable.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String traceparent;

    /**
     * Priority level string (e.g. {@code "HIGH"}, {@code "NORMAL"}, {@code "LOW"}).
     * Omitted for non-priority envelopes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String priority;
}
