package com.cheetah.racer.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link RacerMessage#getPriority()} is type-safe ({@link PriorityLevel})
 * and that JSON round-trips preserve the priority correctly.
 */
class RacerMessagePriorityTypeTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    // ------------------------------------------------------------------
    // Type safety — builder
    // ------------------------------------------------------------------

    @Test
    void builder_defaultPriority_isNormalEnum() {
        RacerMessage msg = RacerMessage.builder()
                .id("1")
                .channel("ch")
                .build();

        assertThat(msg.getPriority()).isInstanceOf(PriorityLevel.class);
        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.NORMAL);
    }

    @Test
    void builder_highPriority_returnsPriorityLevelHigh() {
        RacerMessage msg = RacerMessage.builder()
                .id("2")
                .priority(PriorityLevel.HIGH)
                .build();

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.HIGH);
    }

    @Test
    void builder_lowPriority_returnsPriorityLevelLow() {
        RacerMessage msg = RacerMessage.builder()
                .id("3")
                .priority(PriorityLevel.LOW)
                .build();

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.LOW);
    }

    // ------------------------------------------------------------------
    // Factory method
    // ------------------------------------------------------------------

    @Test
    void create_withPriorityLevel_storesEnum() {
        RacerMessage msg = RacerMessage.create("racer:orders", "payload", "svc", PriorityLevel.HIGH);

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.HIGH);
    }

    @Test
    void create_withNullPriority_defaultsToNormal() {
        RacerMessage msg = RacerMessage.create("ch", "p", "s", null);

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.NORMAL);
    }

    // ------------------------------------------------------------------
    // JSON serialization — enum writes as name string
    // ------------------------------------------------------------------

    @Test
    void serialize_prioritySerializesAsName() throws Exception {
        RacerMessage msg = RacerMessage.create("ch", "p", "s", PriorityLevel.HIGH);
        String json = MAPPER.writeValueAsString(msg);

        assertThat(json).contains("\"priority\":\"HIGH\"");
    }

    @Test
    void serialize_lowPrioritySerializesAsLow() throws Exception {
        RacerMessage msg = RacerMessage.create("ch", "p", "s", PriorityLevel.LOW);
        String json = MAPPER.writeValueAsString(msg);

        assertThat(json).contains("\"priority\":\"LOW\"");
    }

    // ------------------------------------------------------------------
    // JSON deserialization — string to enum
    // ------------------------------------------------------------------

    @Test
    void deserialize_highPriorityStringMapsToEnum() throws Exception {
        RacerMessage original = RacerMessage.create("ch", "p", "s", PriorityLevel.HIGH);
        String json = MAPPER.writeValueAsString(original);

        RacerMessage deserialized = MAPPER.readValue(json, RacerMessage.class);

        assertThat(deserialized.getPriority()).isEqualTo(PriorityLevel.HIGH);
    }

    @Test
    void deserialize_normalPriorityStringMapsToEnum() throws Exception {
        RacerMessage original = RacerMessage.create("ch", "p", "s", PriorityLevel.NORMAL);
        String json = MAPPER.writeValueAsString(original);

        RacerMessage deserialized = MAPPER.readValue(json, RacerMessage.class);

        assertThat(deserialized.getPriority()).isEqualTo(PriorityLevel.NORMAL);
    }

    @Test
    void deserialize_lowPriorityStringMapsToEnum() throws Exception {
        RacerMessage original = RacerMessage.create("ch", "p", "s", PriorityLevel.LOW);
        String json = MAPPER.writeValueAsString(original);

        RacerMessage deserialized = MAPPER.readValue(json, RacerMessage.class);

        assertThat(deserialized.getPriority()).isEqualTo(PriorityLevel.LOW);
    }
}
