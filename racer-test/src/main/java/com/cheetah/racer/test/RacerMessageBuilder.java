package com.cheetah.racer.test;

import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.UUID;

/**
 * Fluent builder for constructing {@link RacerMessage} instances in tests.
 *
 * <p>Handles JSON serialization of the payload object automatically.  The produced
 * message has its {@code payload} field set to the JSON string, exactly as a real
 * published message would after being serialized by
 * {@link com.cheetah.racer.publisher.MessageEnvelopeBuilder}.
 *
 * <p>This is the correct way to build messages for use with {@link RacerTestHarness}
 * because the listener processing pipeline expects the payload to already be a JSON
 * string when argument resolution tries to deserialize it into a typed parameter.
 *
 * <h3>Example</h3>
 * <pre>
 * RacerMessage msg = RacerMessageBuilder.forChannel("racer:orders")
 *         .payload(new Order(1, "Widget"))
 *         .sender("test-harness")
 *         .priority("HIGH")
 *         .build();
 *
 * harness.fireAt("orderService.handleOrder", msg).block();
 * </pre>
 *
 * @see RacerTestHarness
 * @see InMemoryRacerPublisherRegistry
 */
public class RacerMessageBuilder {

    /**
     * Shared default Jackson mapper, pre-configured with JSR-310 support so that
     * {@link java.time.Instant} and other Java time types serialize correctly.
     * Users can override this per-builder via {@link #objectMapper(ObjectMapper)}.
     */
    private static final ObjectMapper DEFAULT_MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    /** The Redis channel name or alias (e.g. {@code "racer:orders"} or {@code "orders"}). */
    private final String channel;

    /** The payload object; serialized to JSON at {@link #build()} time. */
    private Object payload;

    /**
     * Sender label embedded in the message envelope.
     * Defaults to {@code "test-harness"} so tests produce recognizable envelope metadata.
     */
    private String sender = "test-harness";

    /**
     * Priority level string.
     * Defaults to {@code "NORMAL"}.  Valid values: {@code "HIGH"}, {@code "NORMAL"},
     * {@code "LOW"} (or any custom level registered in {@code racer.priority.levels}).
     */
    private String priority = "NORMAL";

    /**
     * Explicit message ID.  When {@code null} (the default), a random UUID is generated
     * at {@link #build()} time.  Set a fixed ID to make tests deterministic or to
     * exercise deduplication logic.
     */
    private String messageId;

    /**
     * The Jackson mapper used to serialize {@link #payload} to JSON.
     * Defaults to {@link #DEFAULT_MAPPER}.
     */
    private ObjectMapper mapper = DEFAULT_MAPPER;

    /**
     * Private constructor — use the static factory {@link #forChannel(String)}.
     *
     * @param channel the Redis channel name or alias
     */
    private RacerMessageBuilder(String channel) {
        this.channel = channel;
    }

    /**
     * Creates a builder targeting the given channel.
     *
     * @param channel the Redis channel name (e.g. {@code "racer:orders"}) or the alias
     *                value (e.g. {@code "orders"}) — use the resolved channel name to
     *                match what a real message envelope would contain
     * @return a new builder instance
     */
    public static RacerMessageBuilder forChannel(String channel) {
        return new RacerMessageBuilder(channel);
    }

    /**
     * Sets the message payload.
     *
     * <p>The object is serialized to a JSON string by {@link #build()}.  Pass any
     * Jackson-serializable object; primitives, strings, POJOs, and collections are all
     * supported.
     *
     * @param payload any Jackson-serializable object; may not be {@code null} unless the
     *                listener explicitly handles null payloads
     * @return this builder
     */
    public RacerMessageBuilder payload(Object payload) {
        this.payload = payload;
        return this;
    }

    /**
     * Sets the sender label embedded in the message envelope.
     *
     * @param sender the sender identifier (free-form string); defaults to
     *               {@code "test-harness"} when not set
     * @return this builder
     */
    public RacerMessageBuilder sender(String sender) {
        this.sender = sender;
        return this;
    }

    /**
     * Sets the priority level.
     *
     * @param priority the priority string, e.g. {@code "HIGH"}, {@code "NORMAL"},
     *                 {@code "LOW"}; defaults to {@code "NORMAL"} when not set
     * @return this builder
     */
    public RacerMessageBuilder priority(String priority) {
        this.priority = priority;
        return this;
    }

    /**
     * Sets an explicit message ID.
     *
     * <p>Use this to produce deterministic messages for deduplication tests or to
     * make assertion error messages easier to read.  When not set, a random UUID is
     * generated at {@link #build()} time.
     *
     * @param messageId stable business key or fixed UUID; pass {@code null} to revert
     *                  to auto-generated UUID behaviour
     * @return this builder
     */
    public RacerMessageBuilder messageId(String messageId) {
        this.messageId = messageId;
        return this;
    }

    /**
     * Overrides the Jackson {@link ObjectMapper} used to serialize the payload.
     *
     * <p>Use this when the payload type requires custom serializer configuration (e.g.
     * a mapper with visibility rules, custom modules, or a specific date format).
     *
     * @param mapper Jackson mapper for payload serialization; must not be {@code null}
     * @return this builder
     */
    public RacerMessageBuilder objectMapper(ObjectMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("[RACER-TEST] objectMapper must not be null.");
        }
        this.mapper = mapper;
        return this;
    }

    /**
     * Builds a {@link RacerMessage} with the configured values.
     *
     * <p>The {@link #payload(Object)} is serialized to a JSON string via Jackson.
     * If {@link #messageId(String)} was not set, a random UUID is assigned.
     *
     * @return the fully constructed message
     * @throws IllegalStateException if the payload cannot be serialized to JSON
     */
    public RacerMessage build() {
        // Serialize payload to JSON; null payload becomes the JSON string "null"
        String payloadJson;
        try {
            payloadJson = payload != null ? mapper.writeValueAsString(payload) : "null";
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[RACER-TEST] Failed to serialize payload for channel '" + channel + "': "
                    + e.getMessage(), e);
        }

        return RacerMessage.builder()
                .id(messageId != null ? messageId : UUID.randomUUID().toString())
                .channel(channel)
                .payload(payloadJson)
                .sender(sender)
                .priority(priority)
                .build();
    }
}
