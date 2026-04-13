package com.cheetah.racer.router.dsl;

import com.cheetah.racer.model.RacerMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RoutePredicates}.
 */
@ExtendWith(MockitoExtension.class)
class RoutePredicatesTest {

    // ── helpers ────────────────────────────────────────────────────────────

    private static RacerMessage msg() {
        return RacerMessage.builder()
                .id("msg-1")
                .channel("orders")
                .payload("{\"type\":\"EMAIL\",\"priority\":\"HIGH\"}")
                .sender("checkout-service")
                .build();
    }

    // ── fieldEquals ───────────────────────────────────────────────────────

    @Test
    void fieldEquals_match() {
        assertThat(RoutePredicates.fieldEquals("type", "EMAIL").test(msg())).isTrue();
    }

    @Test
    void fieldEquals_noMatch() {
        assertThat(RoutePredicates.fieldEquals("type", "SMS").test(msg())).isFalse();
    }

    @Test
    void fieldEquals_missingField() {
        assertThat(RoutePredicates.fieldEquals("nonexistent", "X").test(msg())).isFalse();
    }

    @Test
    void fieldEquals_nullPayload() {
        RacerMessage noPayload = RacerMessage.builder().id("msg-2").build();
        assertThat(RoutePredicates.fieldEquals("type", "EMAIL").test(noPayload)).isFalse();
    }

    @Test
    void fieldEquals_malformedJson() {
        RacerMessage bad = RacerMessage.builder().id("msg-3").payload("not-json").build();
        assertThat(RoutePredicates.fieldEquals("type", "EMAIL").test(bad)).isFalse();
    }

    // ── fieldMatches ──────────────────────────────────────────────────────

    @Test
    void fieldMatches_match() {
        assertThat(RoutePredicates.fieldMatches("type", "EMA.*").test(msg())).isTrue();
    }

    @Test
    void fieldMatches_noMatch() {
        assertThat(RoutePredicates.fieldMatches("type", "^SMS$").test(msg())).isFalse();
    }

    @Test
    void fieldMatches_nullFieldValue() {
        assertThat(RoutePredicates.fieldMatches("missing", ".*").test(msg())).isFalse();
    }

    // ── senderEquals ──────────────────────────────────────────────────────

    @Test
    void senderEquals_match() {
        assertThat(RoutePredicates.senderEquals("checkout-service").test(msg())).isTrue();
    }

    @Test
    void senderEquals_noMatch() {
        assertThat(RoutePredicates.senderEquals("other-service").test(msg())).isFalse();
    }

    @Test
    void senderEquals_nullSender() {
        RacerMessage noSender = RacerMessage.builder().id("msg-4").build();
        assertThat(RoutePredicates.senderEquals("any").test(noSender)).isFalse();
    }

    // ── senderMatches ─────────────────────────────────────────────────────

    @Test
    void senderMatches_match() {
        assertThat(RoutePredicates.senderMatches("checkout-.*").test(msg())).isTrue();
    }

    @Test
    void senderMatches_noMatch() {
        assertThat(RoutePredicates.senderMatches("^payment.*").test(msg())).isFalse();
    }

    // ── idEquals ──────────────────────────────────────────────────────────

    @Test
    void idEquals_match() {
        assertThat(RoutePredicates.idEquals("msg-1").test(msg())).isTrue();
    }

    @Test
    void idEquals_noMatch() {
        assertThat(RoutePredicates.idEquals("msg-999").test(msg())).isFalse();
    }

    // ── idMatches ─────────────────────────────────────────────────────────

    @Test
    void idMatches_match() {
        assertThat(RoutePredicates.idMatches("msg-\\d+").test(msg())).isTrue();
    }

    @Test
    void idMatches_noMatch() {
        assertThat(RoutePredicates.idMatches("^order-.*").test(msg())).isFalse();
    }

    // ── any ───────────────────────────────────────────────────────────────

    @Test
    void any_alwaysTrue() {
        assertThat(RoutePredicates.any().test(msg())).isTrue();
        assertThat(RoutePredicates.any().test(RacerMessage.builder().build())).isTrue();
    }

    // ── RoutePredicate composition ────────────────────────────────────────

    @Test
    void and_bothMatch() {
        RoutePredicate combined = RoutePredicates.fieldEquals("type", "EMAIL")
                .and(RoutePredicates.senderEquals("checkout-service"));
        assertThat(combined.test(msg())).isTrue();
    }

    @Test
    void and_oneFails() {
        RoutePredicate combined = RoutePredicates.fieldEquals("type", "EMAIL")
                .and(RoutePredicates.senderEquals("other"));
        assertThat(combined.test(msg())).isFalse();
    }

    @Test
    void or_oneMatches() {
        RoutePredicate combined = RoutePredicates.fieldEquals("type", "SMS")
                .or(RoutePredicates.senderEquals("checkout-service"));
        assertThat(combined.test(msg())).isTrue();
    }

    @Test
    void or_noneMatch() {
        RoutePredicate combined = RoutePredicates.fieldEquals("type", "SMS")
                .or(RoutePredicates.senderEquals("other"));
        assertThat(combined.test(msg())).isFalse();
    }

    @Test
    void negate_inverts() {
        RoutePredicate negated = RoutePredicates.fieldEquals("type", "EMAIL").negate();
        assertThat(negated.test(msg())).isFalse();
    }

    @Test
    void negate_invertsFalseToTrue() {
        RoutePredicate negated = RoutePredicates.fieldEquals("type", "SMS").negate();
        assertThat(negated.test(msg())).isTrue();
    }
}
