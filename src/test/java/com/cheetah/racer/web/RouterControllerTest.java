package com.cheetah.racer.web;

import com.cheetah.racer.router.RacerRouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RouterController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RouterControllerTest {

    @Mock
    RacerRouterService racerRouterService;

    RouterController controller;

    @BeforeEach
    void setUp() {
        controller = new RouterController(racerRouterService);
    }

    // ── GET /api/router/rules ────────────────────────────────────────────────

    @Test
    void getRules_returnsRuleDescriptions() {
        List<String> descriptions = List.of(
                "[annotation] source=PAYLOAD field='type' matches='order.*' → alias='orders' action=ROUTE",
                "[functional] router='CustomRouter' rules=3"
        );
        when(racerRouterService.getRuleDescriptions()).thenReturn(descriptions);

        ResponseEntity<Map<String, Object>> response = controller.getRules();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("ruleCount", 2);
        assertThat(body).containsEntry("rules", descriptions);
    }

    @Test
    void getRules_empty_returnsEmptyList() {
        when(racerRouterService.getRuleDescriptions()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.getRules();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("ruleCount", 0);
        assertThat(body).containsEntry("rules", List.of());
    }

    // ── POST /api/router/test ────────────────────────────────────────────────

    @Test
    void testRouting_matchFound_returnsAlias() {
        Map<String, Object> payload = Map.of("type", "order-create");
        when(racerRouterService.dryRun(any())).thenReturn("orders");

        ResponseEntity<Map<String, Object>> response = controller.testRouting(payload);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("matched", "orders");
    }

    @Test
    void testRouting_noMatch_returnsNoMatchMessage() {
        Map<String, Object> payload = Map.of("type", "unknown");
        when(racerRouterService.dryRun(any())).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.testRouting(payload);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("matched", "null");
    }
}
