package com.cheetah.racer.test;

import com.cheetah.racer.model.PriorityLevel;
import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link RacerMessageBuilder}.
 *
 * <p>Verifies builder fluency, default values, JSON serialization of payloads, and edge
 * cases such as null payload and custom {@link ObjectMapper}. No Spring context is needed.
 */
class RacerMessageBuilderTest {

    // ── Channel ───────────────────────────────────────────────────────────────

    @Test
    void forChannel_setsChannelOnBuiltMessage() {
        RacerMessage msg = RacerMessageBuilder.forChannel("racer:orders").build();

        assertThat(msg.getChannel()).isEqualTo("racer:orders");
    }

    // ── Defaults ──────────────────────────────────────────────────────────────

    @Test
    void build_defaultSenderIsTestHarness() {
        RacerMessage msg = RacerMessageBuilder.forChannel("ch").build();

        assertThat(msg.getSender()).isEqualTo("test-harness");
    }

    @Test
    void build_defaultPriorityIsNormal() {
        RacerMessage msg = RacerMessageBuilder.forChannel("ch").build();

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.NORMAL);
    }

    @Test
    void build_withoutExplicitId_generatesUniqueUuid() {
        RacerMessage msg1 = RacerMessageBuilder.forChannel("ch").build();
        RacerMessage msg2 = RacerMessageBuilder.forChannel("ch").build();

        assertThat(msg1.getId()).isNotNull();
        assertThat(msg2.getId()).isNotNull();
        assertThat(msg1.getId()).isNotEqualTo(msg2.getId());
    }

    @Test
    void build_withoutExplicitPayload_producesJsonNull() {
        // payload() not called — field stays null and build() emits "null".
        RacerMessage msg = RacerMessageBuilder.forChannel("ch").build();

        assertThat(msg.getPayload()).isEqualTo("null");
    }

    // ── Fluent setters ────────────────────────────────────────────────────────

    @Test
    void messageId_setsFixedId() {
        RacerMessage msg = RacerMessageBuilder.forChannel("ch")
                .messageId("fixed-id-123")
                .build();

        assertThat(msg.getId()).isEqualTo("fixed-id-123");
    }

    @Test
    void sender_overridesDefaultSender() {
        RacerMessage msg = RacerMessageBuilder.forChannel("ch")
                .sender("inventory-service")
                .build();

        assertThat(msg.getSender()).isEqualTo("inventory-service");
    }

    @Test
    void priority_overridesDefaultPriority() {
        RacerMessage msg = RacerMessageBuilder.forChannel("ch")
                .priority(PriorityLevel.HIGH)
                .build();

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.HIGH);
    }

    // ── Payload serialization ─────────────────────────────────────────────────

    @Test
    void payload_stringSerializesToQuotedJson() {
        // JSON serialization wraps plain strings in quotes.
        RacerMessage msg = RacerMessageBuilder.forChannel("ch")
                .payload("hello world")
                .build();

        assertThat(msg.getPayload()).isEqualTo("\"hello world\"");
    }

    @Test
    void payload_integerSerializesToJsonNumber() {
        RacerMessage msg = RacerMessageBuilder.forChannel("ch")
                .payload(42)
                .build();

        assertThat(msg.getPayload()).isEqualTo("42");
    }

    @Test
    void payload_pojoSerializesToJsonObject() throws Exception {
        record Order(int id, String name) {}
        RacerMessage msg = RacerMessageBuilder.forChannel("ch")
                .payload(new Order(1, "Widget"))
                .build();

        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(msg.getPayload()).get("id").asInt()).isEqualTo(1);
        assertThat(mapper.readTree(msg.getPayload()).get("name").asText()).isEqualTo("Widget");
    }

    @Test
    void payload_explicitNull_serializesToJsonNull() {
        RacerMessage msg = RacerMessageBuilder.forChannel("ch")
                .payload(null)
                .build();

        assertThat(msg.getPayload()).isEqualTo("null");
    }

    // ── Custom ObjectMapper ────────────────────────────────────────────────────

    @Test
    void objectMapper_customMapperIsUsedForSerialization() {
        // Pass a plain ObjectMapper (no special modules) — strings should still serialize.
        ObjectMapper customMapper = new ObjectMapper();
        RacerMessage msg = RacerMessageBuilder.forChannel("ch")
                .payload("value")
                .objectMapper(customMapper)
                .build();

        assertThat(msg.getPayload()).isEqualTo("\"value\"");
    }

    @Test
    void objectMapper_nullMapperThrowsIllegalArgumentException() {
        assertThatThrownBy(() ->
                RacerMessageBuilder.forChannel("ch").objectMapper(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("objectMapper must not be null");
    }

    // ── Builder reuse ─────────────────────────────────────────────────────────

    @Test
    void build_calledMultipleTimes_producesConsistentResults() {
        // Calling build() twice on the same builder should produce identical messages
        // (aside from auto-generated fields — here we pin the ID).
        RacerMessageBuilder builder = RacerMessageBuilder.forChannel("racer:orders")
                .sender("svc")
                .priority(PriorityLevel.LOW)
                .messageId("stable-id")
                .payload("data");

        RacerMessage msg1 = builder.build();
        RacerMessage msg2 = builder.build();

        assertThat(msg1.getChannel()).isEqualTo(msg2.getChannel());
        assertThat(msg1.getSender()).isEqualTo(msg2.getSender());
        assertThat(msg1.getPriority()).isEqualTo(msg2.getPriority());
        assertThat(msg1.getId()).isEqualTo(msg2.getId());
        assertThat(msg1.getPayload()).isEqualTo(msg2.getPayload());
    }

    // ── Full message shape ─────────────────────────────────────────────────────

    @Test
    void build_allFieldsSetCorrectly() throws Exception {
        record Item(String name) {}
        RacerMessage msg = RacerMessageBuilder.forChannel("racer:items")
                .payload(new Item("Table"))
                .sender("warehouse")
                .priority(PriorityLevel.LOW)
                .messageId("item-msg-001")
                .build();

        assertThat(msg.getChannel()).isEqualTo("racer:items");
        assertThat(msg.getSender()).isEqualTo("warehouse");
        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.LOW);
        assertThat(msg.getId()).isEqualTo("item-msg-001");

        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(msg.getPayload()).get("name").asText()).isEqualTo("Table");
    }
}
