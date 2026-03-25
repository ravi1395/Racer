package com.cheetah.racer.web;

import com.cheetah.racer.model.DeadLetterMessage;
import com.cheetah.racer.service.DeadLetterQueueService;
import com.cheetah.racer.service.DlqReprocessorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DlqController}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DlqControllerTest {

    @Mock
    DeadLetterQueueService dlqService;

    @Mock
    DlqReprocessorService reprocessorService;

    DlqController controller;

    @BeforeEach
    void setUp() {
        controller = new DlqController(dlqService, reprocessorService);
    }

    // ── GET /api/dlq/messages ────────────────────────────────────────────────

    @Test
    void getMessages_returnsDlqMessages() {
        DeadLetterMessage msg = DeadLetterMessage.builder()
                .id("msg-1")
                .errorMessage("boom")
                .failedAt(Instant.now())
                .build();
        when(dlqService.peekAll()).thenReturn(Flux.just(msg));

        StepVerifier.create(controller.getMessages())
                .assertNext(m -> assertThat(m.getId()).isEqualTo("msg-1"))
                .verifyComplete();
    }

    // ── GET /api/dlq ─────────────────────────────────────────────────────────

    @Test
    void getRootMessages_returnsRootMessages() {
        DeadLetterMessage msg = DeadLetterMessage.builder()
                .id("root-1")
                .errorMessage("fail")
                .failedAt(Instant.now())
                .build();
        when(dlqService.peekAll()).thenReturn(Flux.just(msg));

        StepVerifier.create(controller.getRootMessages())
                .assertNext(m -> assertThat(m.getId()).isEqualTo("root-1"))
                .verifyComplete();
    }

    // ── GET /api/dlq/size ────────────────────────────────────────────────────

    @Test
    void getSize_returnsSizeMap() {
        when(dlqService.size()).thenReturn(Mono.just(42L));

        StepVerifier.create(controller.getSize())
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    Map<String, Object> body = response.getBody();
                    assertThat(body).containsEntry("dlqSize", 42L);
                })
                .verifyComplete();
    }

    // ── POST /api/dlq/republish/one ──────────────────────────────────────────

    @Test
    void republishOne_callsReprocessor() {
        when(reprocessorService.republishOne()).thenReturn(Mono.just(3L));

        StepVerifier.create(controller.republishOne())
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    Map<String, Object> body = response.getBody();
                    assertThat(body).containsEntry("republished", true);
                    assertThat(body).containsEntry("subscribers", 3L);
                })
                .verifyComplete();
    }

    // ── POST /api/dlq/republish/all ──────────────────────────────────────────

    @Test
    void republishAll_callsReprocessor() {
        when(reprocessorService.republishAll()).thenReturn(Mono.just(5L));

        StepVerifier.create(controller.republishAll())
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    Map<String, Object> body = response.getBody();
                    assertThat(body).containsEntry("republishedCount", 5L);
                })
                .verifyComplete();
    }

    // ── DELETE /api/dlq/clear ────────────────────────────────────────────────

    @Test
    void clear_callsDlqClear() {
        when(dlqService.clear()).thenReturn(Mono.just(true));

        StepVerifier.create(controller.clear())
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    Map<String, Object> body = response.getBody();
                    assertThat(body).containsEntry("cleared", true);
                })
                .verifyComplete();
    }

    // ── GET /api/dlq/stats ───────────────────────────────────────────────────

    @Test
    void getStats_returnsStatsMap() {
        when(dlqService.size()).thenReturn(Mono.just(10L));
        when(reprocessorService.getRepublishedCount()).thenReturn(7L);
        when(reprocessorService.getPermanentlyFailedCount()).thenReturn(2L);

        StepVerifier.create(controller.getStats())
                .assertNext(response -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(200);
                    Map<String, Object> body = response.getBody();
                    assertThat(body).containsEntry("queueSize", 10L);
                    assertThat(body).containsEntry("totalRepublished", 7L);
                    assertThat(body).containsEntry("permanentlyFailed", 2L);
                })
                .verifyComplete();
    }
}
