package com.cheetah.racer.web;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.schema.SchemaViolation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SchemaController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaControllerTest {

    @Mock
    RacerSchemaRegistry schemaRegistry;

    SchemaController controller;

    @BeforeEach
    void setUp() {
        controller = new SchemaController();
    }

    private void injectRegistry(RacerSchemaRegistry registry) throws Exception {
        Field field = SchemaController.class.getDeclaredField("schemaRegistry");
        field.setAccessible(true);
        field.set(controller, registry);
    }

    // ── GET /api/schema — registry null ──────────────────────────────────────

    @Test
    void listSchemas_registryNull_returnsDisabledResponse() {
        StepVerifier.create(controller.listSchemas())
                .assertNext(response -> {
                    Map<String, Object> body = response.getBody();
                    assertThat(body).containsEntry("enabled", false);
                    assertThat(body).containsKey("message");
                })
                .verifyComplete();
    }

    // ── GET /api/schema — with registry ──────────────────────────────────────

    @Test
    void listSchemas_withRegistry_returnsSchemaList() throws Exception {
        injectRegistry(schemaRegistry);

        RacerProperties.SchemaDefinition def = new RacerProperties.SchemaDefinition();
        def.setVersion("1.0");
        def.setDescription("Order schema");
        def.setLocation("classpath:schemas/orders.json");

        when(schemaRegistry.getDefinitions()).thenReturn(Map.of("orders", def));

        StepVerifier.create(controller.listSchemas())
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    Map<String, Object> body = response.getBody();
                    assertThat(body).containsEntry("enabled", true);
                    assertThat(body).containsEntry("count", 1);
                    assertThat(body).containsKey("schemas");
                })
                .verifyComplete();
    }

    // ── GET /api/schema/{alias} — registry null ──────────────────────────────

    @Test
    void getSchema_registryNull_returnsDisabledResponse() {
        StepVerifier.create(controller.getSchema("orders"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) response.getBody();
                    assertThat(body).containsEntry("enabled", false);
                })
                .verifyComplete();
    }

    // ── GET /api/schema/{alias} — found ──────────────────────────────────────

    @Test
    void getSchema_found_returnsSchema() throws Exception {
        injectRegistry(schemaRegistry);
        when(schemaRegistry.getSchemaJson("orders")).thenReturn("{\"type\":\"object\"}");

        StepVerifier.create(controller.getSchema("orders"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) response.getBody();
                    assertThat(body).containsEntry("alias", "orders");
                    assertThat(body).containsEntry("schema", "{\"type\":\"object\"}");
                })
                .verifyComplete();
    }

    // ── GET /api/schema/{alias} — not found ──────────────────────────────────

    @Test
    void getSchema_notFound_returns404() throws Exception {
        injectRegistry(schemaRegistry);
        when(schemaRegistry.getSchemaJson("unknown")).thenReturn(null);

        StepVerifier.create(controller.getSchema("unknown"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(404);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) response.getBody();
                    assertThat(body).containsKey("error");
                })
                .verifyComplete();
    }

    // ── POST /api/schema/validate — registry null ────────────────────────────

    @Test
    void validate_registryNull_returnsDisabledResponse() {
        Map<String, Object> body = Map.of("channel", "orders", "payload", Map.of("id", 1));

        StepVerifier.create(controller.validate(body))
                .assertNext(response -> {
                    Map<String, Object> resp = response.getBody();
                    assertThat(resp).containsEntry("enabled", false);
                })
                .verifyComplete();
    }

    // ── POST /api/schema/validate — missing channel ──────────────────────────

    @Test
    void validate_missingChannel_returnsBadRequest() throws Exception {
        injectRegistry(schemaRegistry);
        Map<String, Object> body = Map.of("payload", Map.of("id", 1));

        StepVerifier.create(controller.validate(body))
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(400);
                    Map<String, Object> resp = response.getBody();
                    assertThat(resp).containsKey("error");
                })
                .verifyComplete();
    }

    // ── POST /api/schema/validate — valid payload ────────────────────────────

    @Test
    void validate_valid_returnsOk() throws Exception {
        injectRegistry(schemaRegistry);
        when(schemaRegistry.hasSchema("orders")).thenReturn(true);
        when(schemaRegistry.validateAdHoc(eq("orders"), any())).thenReturn(List.of());

        Map<String, Object> body = Map.of("channel", "orders", "payload", Map.of("id", 1));

        StepVerifier.create(controller.validate(body))
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    Map<String, Object> resp = response.getBody();
                    assertThat(resp).containsEntry("valid", true);
                    assertThat(resp).containsEntry("channel", "orders");
                })
                .verifyComplete();
    }

    // ── POST /api/schema/validate — invalid payload ──────────────────────────

    @Test
    void validate_invalid_returnsValidationErrors() throws Exception {
        injectRegistry(schemaRegistry);
        when(schemaRegistry.hasSchema("orders")).thenReturn(true);

        SchemaViolation violation = new SchemaViolation("required", "/orderId", "orderId is required");
        when(schemaRegistry.validateAdHoc(eq("orders"), any())).thenReturn(List.of(violation));

        Map<String, Object> body = Map.of("channel", "orders", "payload", Map.of("name", "test"));

        StepVerifier.create(controller.validate(body))
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    Map<String, Object> resp = response.getBody();
                    assertThat(resp).containsEntry("valid", false);
                    assertThat(resp).containsKey("violations");
                })
                .verifyComplete();
    }
}
