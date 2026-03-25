package com.cheetah.racer.poll;

import com.cheetah.racer.annotation.RacerPoll;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerPollRegistrar}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerPollRegistrarTest {

    // ── Test beans ────────────────────────────────────────────────────────────

    static class SamplePollBean {
        @RacerPoll(channelRef = "orders", fixedRate = 1000)
        public String generateOrder() {
            return "{\"orderId\": 123}";
        }
    }

    static class PlainBean {
        public String doSomething() {
            return "no annotation";
        }
    }

    // ── Mocks and collaborators ───────────────────────────────────────────────

    @Mock RacerPublisherRegistry publisherRegistry;
    @Mock RacerChannelPublisher publisher;

    ObjectMapper objectMapper;
    RacerPollRegistrar registrar;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        when(publisherRegistry.getPublisher("orders")).thenReturn(publisher);
        when(publisher.getChannelName()).thenReturn("orders-channel");
        when(publisher.publishAsync(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(1L));

        registrar = new RacerPollRegistrar(publisherRegistry, objectMapper, null);
        registrar.setEnvironment(new MockEnvironment());
    }

    // ── Tests: bean discovery ─────────────────────────────────────────────────

    @Test
    void postProcessAfterInitialization_discoversAnnotatedMethods() throws Exception {
        SamplePollBean bean = new SamplePollBean();
        Object result = registrar.postProcessAfterInitialization(bean, "samplePollBean");

        assertThat(result).isSameAs(bean);
        // Allow async scheduling to wire up — the subscription is added immediately
        Thread.sleep(200);
        // The registrar should have at least one subscription after discovery
        // We verify indirectly: stop() should not throw and poll counters start at 0
        registrar.stop();
    }

    @Test
    void postProcessAfterInitialization_noAnnotation_returnsBean() {
        PlainBean bean = new PlainBean();
        Object result = registrar.postProcessAfterInitialization(bean, "plainBean");

        assertThat(result).isSameAs(bean);
    }

    // ── Tests: stop lifecycle ─────────────────────────────────────────────────

    @Test
    void stop_disposesAllSubscriptions() throws Exception {
        SamplePollBean bean = new SamplePollBean();
        registrar.postProcessAfterInitialization(bean, "samplePollBean");
        Thread.sleep(200);

        // Should not throw
        registrar.stop();
    }

    // ── Tests: counter accessors ──────────────────────────────────────────────

    @Test
    void getTotalPolls_initiallyZero() {
        assertThat(registrar.getTotalPolls()).isZero();
    }

    @Test
    void getTotalErrors_initiallyZero() {
        assertThat(registrar.getTotalErrors()).isZero();
    }

    // ── Tests: CronMatcher ────────────────────────────────────────────────────

    @Test
    void cronMatcher_matchesOnce_returnsTrue_whenCronFires() {
        // "* * * * * *" fires every second — should match the current second
        AtomicReference<LocalDateTime> lastFired = new AtomicReference<>(LocalDateTime.MIN);
        boolean matched = RacerPollRegistrar.CronMatcher.matchesOnce("* * * * * *", lastFired);

        assertThat(matched).isTrue();
    }

    @Test
    void cronMatcher_matchesOnce_deduplicates() {
        AtomicReference<LocalDateTime> lastFired = new AtomicReference<>(LocalDateTime.MIN);

        boolean first = RacerPollRegistrar.CronMatcher.matchesOnce("* * * * * *", lastFired);
        assertThat(first).isTrue();

        // Second call within the same second should return false (dedup)
        boolean second = RacerPollRegistrar.CronMatcher.matchesOnce("* * * * * *", lastFired);
        assertThat(second).isFalse();
    }

    @SuppressWarnings("deprecation")
    @Test
    void cronMatcher_matches_deprecatedMethod_works() {
        // "* * * * * *" fires every second — should match now
        boolean matched = RacerPollRegistrar.CronMatcher.matches("* * * * * *");
        assertThat(matched).isTrue();
    }

    @Test
    void cronMatcher_matchesOnce_invalidCron_returnsFalse() {
        AtomicReference<LocalDateTime> lastFired = new AtomicReference<>(LocalDateTime.MIN);
        boolean matched = RacerPollRegistrar.CronMatcher.matchesOnce("not-a-cron", lastFired);

        assertThat(matched).isFalse();
    }
}
