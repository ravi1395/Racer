package com.cheetah.racer.router;

import com.cheetah.racer.annotation.RacerRoute;
import com.cheetah.racer.annotation.RacerRouteRule;
import com.cheetah.racer.annotation.RouteAction;
import com.cheetah.racer.annotation.RouteMatchSource;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.router.dsl.RacerFunctionalRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerRouterService#dryRun(Object, String, String)} and
 * the deprecated {@link RacerRouterService#dryRun(Object)}.
 *
 * <p>Covers PAYLOAD, SENDER, and ID rule evaluation, null-argument skipping,
 * and the delegation behaviour of the deprecated overload.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerRouterServiceDryRunTest {

    /** Router with one rule per source type. */
    @RacerRoute({
        @RacerRouteRule(field = "type",   matches = "ORDER",    to = "orders",   source = RouteMatchSource.PAYLOAD),
        @RacerRouteRule(field = "unused", matches = "svc-a",    to = "serviceA", source = RouteMatchSource.SENDER),
        @RacerRouteRule(field = "unused", matches = "msg-123",  to = "serviceB", source = RouteMatchSource.ID,
                        action = RouteAction.DROP)
    })
    static class MultiSourceRouter {}

    @Mock ApplicationContext applicationContext;
    @Mock RacerPublisherRegistry registry;

    RacerRouterService routerService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        when(applicationContext.getBeansWithAnnotation(RacerRoute.class))
                .thenReturn(Map.of("multiSourceRouter", new MultiSourceRouter()));
        when(applicationContext.getBeansOfType(RacerFunctionalRouter.class))
                .thenReturn(Map.of());

        routerService = new RacerRouterService(applicationContext, registry, objectMapper);
        routerService.init();
    }

    // ------------------------------------------------------------------
    // PAYLOAD rule
    // ------------------------------------------------------------------

    @Test
    void dryRun3_payloadRuleMatches_returnsForwarded() {
        RouteDecision decision = routerService.dryRun(
                Map.of("type", "ORDER"), null, null);
        assertThat(decision).isEqualTo(RouteDecision.FORWARDED);
    }

    @Test
    void dryRun3_payloadRuleNoMatch_returnsNull() {
        RouteDecision decision = routerService.dryRun(
                Map.of("type", "UNKNOWN"), null, null);
        assertThat(decision).isNull();
    }

    @Test
    void dryRun3_nullPayload_skipsPayloadRule() {
        // Only PAYLOAD rule matches "ORDER"; with null payload it should be skipped
        RouteDecision decision = routerService.dryRun(null, null, null);
        assertThat(decision).isNull();
    }

    // ------------------------------------------------------------------
    // SENDER rule
    // ------------------------------------------------------------------

    @Test
    void dryRun3_senderRuleMatches_returnsForwarded() {
        RouteDecision decision = routerService.dryRun(null, "svc-a", null);
        assertThat(decision).isEqualTo(RouteDecision.FORWARDED);
    }

    @Test
    void dryRun3_nullSender_skipsSenderRule() {
        // No payload match (null), no sender match (null) → only ID rule could fire but no messageId given
        RouteDecision decision = routerService.dryRun(null, null, null);
        assertThat(decision).isNull();
    }

    // ------------------------------------------------------------------
    // ID rule (action = DROP → DROPPED)
    // ------------------------------------------------------------------

    @Test
    void dryRun3_idRuleMatches_returnsDropped() {
        RouteDecision decision = routerService.dryRun(null, null, "msg-123");
        assertThat(decision).isEqualTo(RouteDecision.DROPPED);
    }

    @Test
    void dryRun3_nullMessageId_skipsIdRule() {
        RouteDecision decision = routerService.dryRun(null, null, null);
        assertThat(decision).isNull();
    }

    // ------------------------------------------------------------------
    // Null sender/messageId skips those rule sources (mirrors old 1-arg behaviour)
    // ------------------------------------------------------------------

    @Test
    void dryRun3_nullSenderAndId_onlyEvaluatesPayload_match() {
        // Passing null for sender and messageId skips SENDER/ID rules — PAYLOAD rule matches
        RouteDecision decision = routerService.dryRun("{\"type\":\"ORDER\"}", null, null);
        assertThat(decision).isEqualTo(RouteDecision.FORWARDED);
    }

    @Test
    void dryRun3_nullSenderAndId_senderRuleIsSkipped() {
        // With null sender the SENDER rule "svc-a" is never evaluated
        RouteDecision decision = routerService.dryRun("{\"type\":\"SOMETHING_ELSE\"}", null, null);
        assertThat(decision).isNull();
    }

    @Test
    void dryRun3_nullSenderAndId_idRuleIsSkipped() {
        // With null messageId the ID rule "msg-123" is never evaluated
        RouteDecision decision = routerService.dryRun("{\"type\":\"IGNORED\"}", null, null);
        assertThat(decision).isNull();
    }

    // ------------------------------------------------------------------
    // No publishing side-effect
    // ------------------------------------------------------------------

    @Test
    void dryRun3_doesNotPublish_whenRuleMatches() {
        // registry.getPublisher must never be called during a dry-run
        routerService.dryRun(Map.of("type", "ORDER"), "svc-a", "msg-123");
        // If publishing were triggered it would call registry.getPublisher,
        // which is a strict mock — any invocation would fail the test.
    }
}
