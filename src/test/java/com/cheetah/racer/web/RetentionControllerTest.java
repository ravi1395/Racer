package com.cheetah.racer.web;

import com.cheetah.racer.service.RacerRetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetentionController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetentionControllerTest {

    @Mock
    RacerRetentionService retentionService;

    RetentionController controller;

    @BeforeEach
    void setUp() {
        controller = new RetentionController(retentionService);
    }

    // ── GET /api/retention/config ────────────────────────────────────────────

    @Test
    void getConfig_returnsConfigMap() {
        Map<String, Object> config = Map.of(
                "streamMaxLen", 10000L,
                "dlqMaxAgeHours", 72L,
                "totalStreamTrimmed", 500L,
                "totalDlqPruned", 15L
        );
        when(retentionService.getConfig()).thenReturn(config);

        ResponseEntity<Map<String, Object>> response = controller.getConfig();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("streamMaxLen", 10000L);
        assertThat(body).containsEntry("dlqMaxAgeHours", 72L);
        assertThat(body).containsEntry("totalStreamTrimmed", 500L);
        assertThat(body).containsEntry("totalDlqPruned", 15L);
    }

    // ── POST /api/retention/trim ─────────────────────────────────────────────

    @Test
    void trim_executesAndReturnsResults() {
        when(retentionService.trimStreams()).thenReturn(Mono.empty());
        when(retentionService.pruneDlq()).thenReturn(Mono.just(8L));

        StepVerifier.create(controller.trim())
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    Map<String, Object> body = response.getBody();
                    assertThat(body).containsEntry("status", "trimmed");
                    assertThat(body).containsEntry("dlqPruned", 8L);
                })
                .verifyComplete();
    }
}
