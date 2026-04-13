package com.cheetah.racer.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DeadLetterMessage#from(RacerMessage, Throwable)}.
 *
 * <p>Covers v1.4.0 item 1.2: the {@code errorMessage} field must carry the full
 * exception message string so DLQ consumers can diagnose failures immediately.
 * When {@code ex.getMessage()} is {@code null} (e.g. bare {@link NullPointerException}),
 * the field falls back to the simple class name rather than storing {@code null}.
 */
class DeadLetterMessageReasonTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RacerMessage sampleMessage() {
        return RacerMessage.create("racer:orders", "{not:json}", "test-sender");
    }

    // ── errorMessage field ────────────────────────────────────────────────────

    @Test
    void from_capturesFullExceptionMessage() {
        Exception ex = new RuntimeException("JSON parse error: unexpected '}' at position 8");
        DeadLetterMessage dlm = DeadLetterMessage.from(sampleMessage(), ex);

        assertThat(dlm.getErrorMessage())
                .isEqualTo("JSON parse error: unexpected '}' at position 8");
    }

    @Test
    void from_withNullExceptionMessage_fallsBackToSimpleClassName() {
        // NullPointerException created without a message has getMessage() == null
        NullPointerException ex = new NullPointerException();
        DeadLetterMessage dlm = DeadLetterMessage.from(sampleMessage(), ex);

        // Must not store null — falls back to the simple class name for diagnosability
        assertThat(dlm.getErrorMessage()).isEqualTo("NullPointerException");
    }

    @Test
    void from_withBlankExceptionMessage_capturesItVerbatim() {
        // An exception with an empty string message should be stored as-is
        Exception ex = new IllegalStateException("");
        DeadLetterMessage dlm = DeadLetterMessage.from(sampleMessage(), ex);

        assertThat(dlm.getErrorMessage()).isEmpty();
    }

    // ── exceptionClass field ──────────────────────────────────────────────────

    @Test
    void from_capturesFullyQualifiedExceptionClassName() {
        Exception ex = new IllegalArgumentException("bad input");
        DeadLetterMessage dlm = DeadLetterMessage.from(sampleMessage(), ex);

        assertThat(dlm.getExceptionClass())
                .isEqualTo(IllegalArgumentException.class.getName());
    }

    // ── message identity fields ───────────────────────────────────────────────

    @Test
    void from_preservesOriginalMessageReference() {
        RacerMessage msg = sampleMessage();
        DeadLetterMessage dlm = DeadLetterMessage.from(msg, new RuntimeException("fail"));

        assertThat(dlm.getOriginalMessage()).isSameAs(msg);
        assertThat(dlm.getId()).isEqualTo(msg.getId());
    }

    @Test
    void from_setsAttemptCountAsRetryCountPlusOne() {
        RacerMessage msg = RacerMessage.builder()
                .id("test-id")
                .channel("racer:orders")
                .payload("{}")
                .retryCount(2)
                .build();

        DeadLetterMessage dlm = DeadLetterMessage.from(msg, new RuntimeException("fail"));

        // attemptCount = retryCount (2 previous attempts) + 1 (this attempt)
        assertThat(dlm.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void from_populatesFailedAtTimestamp() {
        DeadLetterMessage dlm = DeadLetterMessage.from(sampleMessage(), new RuntimeException("x"));

        assertThat(dlm.getFailedAt()).isNotNull();
    }
}
