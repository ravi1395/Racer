package com.cheetah.racer.publisher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.test.StepVerifier;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MessageEnvelopeBuilder}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageEnvelopeBuilderTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        // Restore the default fast-UUID generator before each test so that
        // setIdGenerator() tests do not bleed into unrelated assertions.
        MessageEnvelopeBuilder.setIdGenerator(MessageEnvelopeBuilder.fastUuidGenerator());
    }

    @AfterEach
    void tearDown() {
        // Always restore default so the static field is clean for the next test class.
        MessageEnvelopeBuilder.setIdGenerator(MessageEnvelopeBuilder.fastUuidGenerator());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Map<String, Object> parse(String json) throws Exception {
        return objectMapper.readValue(json, MAP_TYPE);
    }

    // ── build (standard envelope) ─────────────────────────────────────────────

    @Test
    void build_standardEnvelope_containsAllFields() {
        StepVerifier.create(MessageEnvelopeBuilder.build(objectMapper, "my-channel", "svc-a", "hello"))
                .assertNext(json -> {
                    try {
                        Map<String, Object> env = parse(json);
                        assertThat(env).containsKeys("id", "channel", "sender", "timestamp", "payload");
                        assertThat(env.get("channel")).isEqualTo("my-channel");
                        assertThat(env.get("sender")).isEqualTo("svc-a");
                        assertThat(env.get("payload")).isEqualTo("hello");
                        assertThat((String) env.get("id")).isNotBlank();
                        assertThat((String) env.get("timestamp")).isNotBlank();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void build_nullPayload_serializesAsNull() {
        String json = MessageEnvelopeBuilder.build(objectMapper, "ch", "s", null).block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env.get("payload")).isEqualTo("null");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void build_stringPayload_usedDirectly() {
        String json = MessageEnvelopeBuilder.build(objectMapper, "ch", "s", "raw-string").block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env.get("payload")).isEqualTo("raw-string");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void build_objectPayload_serializedAsJson() {
        Map<String, String> payload = Map.of("key", "value");
        String json = MessageEnvelopeBuilder.build(objectMapper, "ch", "s", payload).block();
        try {
            Map<String, Object> env = parse(json);
            // Object payloads are serialized to a JSON string (not nested object)
            String payloadStr = (String) env.get("payload");
            assertThat(payloadStr).contains("\"key\"").contains("\"value\"");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void build_routedTrue_includesRoutedField() {
        String json = MessageEnvelopeBuilder.build(objectMapper, "ch", "s", "data", true).block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env.get("routed")).isEqualTo(true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void build_routedFalse_omitsRoutedField() {
        String json = MessageEnvelopeBuilder.build(objectMapper, "ch", "s", "data", false).block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env).doesNotContainKey("routed");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void build_customMessageId_usesProvided() {
        String json = MessageEnvelopeBuilder.build(objectMapper, "ch", "s", "data", false, "my-id-123").block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env.get("id")).isEqualTo("my-id-123");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void build_nullMessageId_generatesUUID() {
        String json = MessageEnvelopeBuilder.build(objectMapper, "ch", "s", "data", false, null).block();
        try {
            Map<String, Object> env = parse(json);
            String id = (String) env.get("id");
            assertThat(id).isNotBlank();
            // Should be a valid UUID format
            assertThat(id).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── buildWithPriority ─────────────────────────────────────────────────────

    @Test
    void buildWithPriority_includesPriorityField() {
        String json = MessageEnvelopeBuilder.buildWithPriority(objectMapper, "ch", "s", "HIGH", "data").block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env).containsKeys("id", "channel", "sender", "timestamp", "priority", "payload");
            assertThat(env.get("priority")).isEqualTo("HIGH");
            assertThat(env.get("channel")).isEqualTo("ch");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── buildStream ───────────────────────────────────────────────────────────

    @Test
    void buildStream_omitsChannelField() {
        String json = MessageEnvelopeBuilder.buildStream(objectMapper, "sender", "data").block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env).doesNotContainKey("channel");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void buildStream_containsSenderAndPayload() {
        String json = MessageEnvelopeBuilder.buildStream(objectMapper, "my-sender", "my-payload").block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env).containsKeys("id", "sender", "timestamp", "payload");
            assertThat(env.get("sender")).isEqualTo("my-sender");
            assertThat(env.get("payload")).isEqualTo("my-payload");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── buildWithTrace ────────────────────────────────────────────────────────

    @Test
    void buildWithTrace_includesTraceparent() {
        String traceparent = "00-abc123-def456-01";
        String json = MessageEnvelopeBuilder.buildWithTrace(
                objectMapper, "ch", "s", "data", false, null, traceparent).block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env.get("traceparent")).isEqualTo(traceparent);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void buildWithTrace_nullTraceparent_omitsField() {
        String json = MessageEnvelopeBuilder.buildWithTrace(
                objectMapper, "ch", "s", "data", false, null, null).block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env).doesNotContainKey("traceparent");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void buildWithTrace_blankTraceparent_omitsField() {
        String json = MessageEnvelopeBuilder.buildWithTrace(
                objectMapper, "ch", "s", "data", false, null, "   ").block();
        try {
            Map<String, Object> env = parse(json);
            assertThat(env).doesNotContainKey("traceparent");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── IdGenerator — fast UUID strategy (Option A) ───────────────────────────

    @Test
    void fastUuidGenerator_producesValidUuidFormat() {
        IdGenerator gen = MessageEnvelopeBuilder.fastUuidGenerator();
        String id = gen.generate();
        // Standard UUID pattern: 8-4-4-4-12 lowercase hex
        assertThat(id).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void fastUuidGenerator_producesUniqueIds() {
        IdGenerator gen = MessageEnvelopeBuilder.fastUuidGenerator();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(gen.generate());
        }
        // All 1000 generated IDs must be unique
        assertThat(ids).hasSize(1000);
    }

    @Test
    void setIdGenerator_customGeneratorUsedInEnvelope() throws Exception {
        // Replace the active generator with a deterministic stub
        AtomicInteger counter = new AtomicInteger(0);
        MessageEnvelopeBuilder.setIdGenerator(() -> "test-id-" + counter.incrementAndGet());

        String json = MessageEnvelopeBuilder.build(objectMapper, "ch", "s", "data").block();
        Map<String, Object> env = parse(json);

        assertThat(env.get("id")).isEqualTo("test-id-1");
    }

    @Test
    void setIdGenerator_appliedAcrossAllBuildMethods() throws Exception {
        MessageEnvelopeBuilder.setIdGenerator(() -> "fixed-id");

        String pubSubJson  = MessageEnvelopeBuilder.build(objectMapper, "ch", "s", "data").block();
        String priorityJson = MessageEnvelopeBuilder.buildWithPriority(objectMapper, "ch", "s", "HIGH", "data").block();
        String streamJson   = MessageEnvelopeBuilder.buildStream(objectMapper, "s", "data").block();
        String traceJson    = MessageEnvelopeBuilder.buildWithTrace(objectMapper, "ch", "s", "data", false, null, null).block();

        assertThat(parse(pubSubJson).get("id")).isEqualTo("fixed-id");
        assertThat(parse(priorityJson).get("id")).isEqualTo("fixed-id");
        assertThat(parse(streamJson).get("id")).isEqualTo("fixed-id");
        assertThat(parse(traceJson).get("id")).isEqualTo("fixed-id");
    }

    @Test
    void build_explicitMessageId_takesPrecedenceOverGenerator() throws Exception {
        // Even when a custom generator is set, an explicit messageId must not be overridden
        MessageEnvelopeBuilder.setIdGenerator(() -> "generated-id");

        String json = MessageEnvelopeBuilder.build(objectMapper, "ch", "s", "data", false, "explicit-id").block();
        assertThat(parse(json).get("id")).isEqualTo("explicit-id");
    }
}
