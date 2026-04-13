package com.cheetah.racer.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link RacerMessage} JSON produced by older versions of Racer
 * (which stored {@code priority} as a plain string: {@code "HIGH"}, {@code "NORMAL"},
 * {@code "LOW"}) is correctly deserialized into the new {@link PriorityLevel} enum.
 *
 * <p>This covers the wire-compatibility guarantee: the on-wire format is unchanged
 * (Jackson serializes the enum as its {@link Enum#name()}, which matches the old strings);
 * and the new {@link PriorityLevel#fromString(String)} {@code @JsonCreator} handles
 * deserialization from both old and new producers.
 */
class RacerMessageLegacyDeserializationTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    /** Minimal JSON template representing a legacy message. */
    private static String legacyJson(String priorityValue) {
        return "{\"id\":\"msg-1\",\"channel\":\"racer:orders\",\"payload\":\"data\","
                + "\"sender\":\"legacy-svc\","
                + "\"priority\":\"" + priorityValue + "\","
                + "\"retryCount\":0,\"routed\":false}";
    }

    @Test
    void legacyHighString_deserializesToHighEnum() throws Exception {
        RacerMessage msg = MAPPER.readValue(legacyJson("HIGH"), RacerMessage.class);

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.HIGH);
    }

    @Test
    void legacyNormalString_deserializesToNormalEnum() throws Exception {
        RacerMessage msg = MAPPER.readValue(legacyJson("NORMAL"), RacerMessage.class);

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.NORMAL);
    }

    @Test
    void legacyLowString_deserializesToLowEnum() throws Exception {
        RacerMessage msg = MAPPER.readValue(legacyJson("LOW"), RacerMessage.class);

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.LOW);
    }

    @Test
    void legacyLowercaseString_caseInsensitiveDeserialization() throws Exception {
        RacerMessage msg = MAPPER.readValue(legacyJson("high"), RacerMessage.class);

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.HIGH);
    }

    @Test
    void legacyUnknownString_fallsBackToNormal() throws Exception {
        RacerMessage msg = MAPPER.readValue(legacyJson("CRITICAL"), RacerMessage.class);

        assertThat(msg.getPriority()).isEqualTo(PriorityLevel.NORMAL);
    }

    @Test
    void newMessageSerializedAndDeserializedRoundTrips() throws Exception {
        RacerMessage original = RacerMessage.create("racer:orders", "payload", "svc", PriorityLevel.HIGH);
        String json = MAPPER.writeValueAsString(original);

        RacerMessage deserialized = MAPPER.readValue(json, RacerMessage.class);

        assertThat(deserialized.getPriority()).isEqualTo(PriorityLevel.HIGH);
        assertThat(deserialized.getChannel()).isEqualTo(original.getChannel());
        assertThat(deserialized.getSender()).isEqualTo(original.getSender());
    }
}
