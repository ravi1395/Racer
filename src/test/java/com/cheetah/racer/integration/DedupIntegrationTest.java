package com.cheetah.racer.integration;

import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.model.RacerMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Phase 3.1 message deduplication feature.
 *
 * <p>Requires a running Redis (provided by {@link RacerIntegrationTestBase}) and
 * {@code racer.dedup.enabled=true} to activate the {@link com.cheetah.racer.dedup.RacerDedupService}
 * bean and wire it into {@link com.cheetah.racer.listener.RacerListenerRegistrar}.
 */
@TestPropertySource(properties = "racer.dedup.enabled=true")
@Import(DedupIntegrationTest.ReceiverConfig.class)
class DedupIntegrationTest extends RacerIntegrationTestBase {

    static final String CHANNEL_DEDUP    = "racer:it:dedup";
    static final String CHANNEL_NO_DEDUP = "racer:it:nodedup";

    // ── Test receiver ────────────────────────────────────────────────────────

    static class TestReceiver {
        final List<RacerMessage> dedupMessages   = new ArrayList<>();
        final List<RacerMessage> nonDedupMessages = new ArrayList<>();
        final AtomicReference<CountDownLatch> dedupLatch    = new AtomicReference<>();
        final AtomicReference<CountDownLatch> nonDedupLatch = new AtomicReference<>();

        /** dedup=true: duplicate IDs should be suppressed. */
        @RacerListener(channel = CHANNEL_DEDUP, dedup = true)
        public void onDedup(RacerMessage msg) {
            dedupMessages.add(msg);
            CountDownLatch l = dedupLatch.get();
            if (l != null) l.countDown();
        }

        /** dedup=false (default): all messages delivered regardless of duplicate IDs. */
        @RacerListener(channel = CHANNEL_NO_DEDUP)
        public void onNoDedup(RacerMessage msg) {
            nonDedupMessages.add(msg);
            CountDownLatch l = nonDedupLatch.get();
            if (l != null) l.countDown();
        }
    }

    @TestConfiguration
    static class ReceiverConfig {
        @Bean
        TestReceiver dedupReceiver() { return new TestReceiver(); }
    }

    @Autowired
    private TestReceiver receiver;

    @BeforeEach
    void reset() {
        receiver.dedupMessages.clear();
        receiver.nonDedupMessages.clear();
    }

    @AfterEach
    void cleanup() {
        // Wipe all dedup keys created during this test class run
        reactiveStringRedisTemplate.delete(
                reactiveStringRedisTemplate.keys("racer:dedup:*").blockLast()).block();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    /**
     * A message published once must be delivered exactly once.
     */
    @Test
    void dedup_firstMessage_isDelivered() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        receiver.dedupLatch.set(latch);

        RacerMessage msg = message(CHANNEL_DEDUP, "first-time-payload");
        publishPubSub(CHANNEL_DEDUP, msg);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receiver.dedupMessages).hasSize(1);
        assertThat(receiver.dedupMessages.get(0).getId()).isEqualTo(msg.getId());
    }

    /**
     * Publishing the same message (same ID) twice must result in exactly one delivery.
     * The second arrival carries the same ID — dedup should suppress it.
     */
    @Test
    void dedup_duplicateId_onlyFirstDelivered() throws Exception {
        // First delivery
        CountDownLatch firstLatch = new CountDownLatch(1);
        receiver.dedupLatch.set(firstLatch);

        RacerMessage msg = message(CHANNEL_DEDUP, "to-be-deduped");
        publishPubSub(CHANNEL_DEDUP, msg);

        assertThat(firstLatch.await(5, TimeUnit.SECONDS)).as("first delivery").isTrue();

        // Now publish exactly the same object (same ID) again
        // Use a short latch with a short timeout — we expect it NOT to count down
        CountDownLatch secondLatch = new CountDownLatch(1);
        receiver.dedupLatch.set(secondLatch);
        publishPubSub(CHANNEL_DEDUP, msg);

        boolean duplicateDelivered = secondLatch.await(1, TimeUnit.SECONDS);

        assertThat(duplicateDelivered).as("duplicate should be suppressed").isFalse();
        assertThat(receiver.dedupMessages).hasSize(1);
    }

    /**
     * Two messages with different IDs on the same dedup-enabled channel must both be delivered.
     */
    @Test
    void dedup_differentIds_bothDelivered() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        receiver.dedupLatch.set(latch);

        publishPubSub(CHANNEL_DEDUP, message(CHANNEL_DEDUP, "payload-a"));
        publishPubSub(CHANNEL_DEDUP, message(CHANNEL_DEDUP, "payload-b"));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receiver.dedupMessages).hasSize(2);
    }

    /**
     * A listener with {@code dedup=false} must receive duplicate messages normally.
     */
    @Test
    void noDedup_duplicateId_bothDelivered() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        receiver.nonDedupLatch.set(latch);

        RacerMessage msg = message(CHANNEL_NO_DEDUP, "not-deduped");
        publishPubSub(CHANNEL_NO_DEDUP, msg);
        publishPubSub(CHANNEL_NO_DEDUP, msg);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(receiver.nonDedupMessages).hasSize(2);
    }
}
