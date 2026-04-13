package com.cheetah.racer.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link InMemoryRacerPublisher}.
 *
 * <p>Verifies publish capture, assertion helpers, thread safety, and the
 * {@link InMemoryRacerPublisher#reset()} lifecycle method without any Redis
 * or Spring context involvement.
 */
class InMemoryRacerPublisherTest {

    private static final String CHANNEL = "racer:orders";
    private static final String ALIAS   = "orders";

    private InMemoryRacerPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new InMemoryRacerPublisher(CHANNEL, ALIAS, new ObjectMapper());
    }

    // ── publishAsync ─────────────────────────────────────────────────────────

    @Test
    void publishAsync_withoutSender_capturesPayloadAndCompletesWithOne() {
        StepVerifier.create(publisher.publishAsync("hello"))
                .expectNext(1L)
                .verifyComplete();

        assertThat(publisher.getMessages()).hasSize(1);
        InMemoryRacerPublisher.CapturedMessage msg = publisher.getMessages().get(0);
        assertThat(msg.payload()).isEqualTo("hello");
        assertThat(msg.sender()).isNull();
        assertThat(msg.capturedAt()).isNotNull();
    }

    @Test
    void publishAsync_withSender_capturesSenderOnMessage() {
        StepVerifier.create(publisher.publishAsync("payload", "order-service"))
                .expectNext(1L)
                .verifyComplete();

        assertThat(publisher.getMessages().get(0).sender()).isEqualTo("order-service");
    }

    @Test
    void publishAsync_multiplePublishes_appendsInOrder() {
        publisher.publishAsync("first").block();
        publisher.publishAsync("second").block();
        publisher.publishAsync("third").block();

        List<InMemoryRacerPublisher.CapturedMessage> msgs = publisher.getMessages();
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(0).payload()).isEqualTo("first");
        assertThat(msgs.get(1).payload()).isEqualTo("second");
        assertThat(msgs.get(2).payload()).isEqualTo("third");
    }

    // ── publishSync ──────────────────────────────────────────────────────────

    @Test
    void publishSync_capturesMessageAndReturnsOne() {
        Long result = publisher.publishSync("sync-payload");

        assertThat(result).isEqualTo(1L);
        assertThat(publisher.getMessages()).hasSize(1);
        assertThat(publisher.getMessages().get(0).payload()).isEqualTo("sync-payload");
        assertThat(publisher.getMessages().get(0).sender()).isNull();
    }

    // ── assertMessageCount ────────────────────────────────────────────────────

    @Test
    void assertMessageCount_doesNotThrowWhenCountMatches() {
        publisher.publishSync("a");
        publisher.publishSync("b");

        assertThatCode(() -> publisher.assertMessageCount(2)).doesNotThrowAnyException();
    }

    @Test
    void assertMessageCount_throwsAssertionErrorWithContextWhenMismatch() {
        publisher.publishSync("a");

        assertThatThrownBy(() -> publisher.assertMessageCount(3))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ALIAS)
                .hasMessageContaining("expected 3")
                .hasMessageContaining("found 1");
    }

    @Test
    void assertMessageCount_returnsSelfForFluentChaining() {
        publisher.publishSync("x");

        InMemoryRacerPublisher returned = publisher.assertMessageCount(1);

        assertThat(returned).isSameAs(publisher);
    }

    // ── assertPayload ─────────────────────────────────────────────────────────

    @Test
    void assertPayload_passesWhenJacksonJsonMatches() {
        // Uses JSON string comparison — equal content in different instances should pass.
        record Order(String id, int qty) {}
        publisher.publishSync(new Order("ORD-1", 5));

        assertThatCode(() -> publisher.assertPayload(0, new Order("ORD-1", 5)))
                .doesNotThrowAnyException();
    }

    @Test
    void assertPayload_throwsAssertionErrorOnContentMismatch() {
        record Order(String id) {}
        publisher.publishSync(new Order("actual-id"));

        assertThatThrownBy(() -> publisher.assertPayload(0, new Order("expected-id")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("actual-id")
                .hasMessageContaining("expected-id");
    }

    @Test
    void assertPayload_throwsAssertionErrorForOutOfRangeIndex() {
        // No messages published — index 0 is out of range.
        // InMemoryRacerPublisher wraps IndexOutOfBoundsException in AssertionError
        // with a channel-qualified message so test output is readable.
        assertThatThrownBy(() -> publisher.assertPayload(0, "anything"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(ALIAS);
    }

    @Test
    void assertPayload_returnsSelfForFluentChaining() {
        publisher.publishSync("test");

        InMemoryRacerPublisher returned = publisher.assertPayload(0, "test");

        assertThat(returned).isSameAs(publisher);
    }

    @Test
    void assertPayload_fluent_chainWithAssertMessageCount() {
        publisher.publishSync("one");
        publisher.publishSync("two");

        // Full fluent chain should not throw.
        assertThatCode(() ->
                publisher.assertMessageCount(2)
                         .assertPayload(0, "one")
                         .assertPayload(1, "two"))
                .doesNotThrowAnyException();
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    void reset_clearsAllCapturedMessages() {
        publisher.publishSync("a");
        publisher.publishSync("b");

        publisher.reset();

        assertThat(publisher.getMessages()).isEmpty();
    }

    @Test
    void reset_allowsCounterToRestartFromZero() {
        publisher.publishSync("before-reset");
        publisher.reset();
        publisher.publishSync("after-reset");

        publisher.assertMessageCount(1).assertPayload(0, "after-reset");
    }

    // ── accessor methods ──────────────────────────────────────────────────────

    @Test
    void getChannelName_returnsConstructorValue() {
        assertThat(publisher.getChannelName()).isEqualTo(CHANNEL);
    }

    @Test
    void getChannelAlias_returnsConstructorValue() {
        assertThat(publisher.getChannelAlias()).isEqualTo(ALIAS);
    }

    @Test
    void getMessages_returnsUnmodifiableView() {
        publisher.publishSync("msg");
        List<InMemoryRacerPublisher.CapturedMessage> view = publisher.getMessages();

        assertThatThrownBy(() -> view.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── thread safety ─────────────────────────────────────────────────────────

    @Test
    void concurrentPublishAsync_allMessagesCapturedWithoutDataRace() throws InterruptedException {
        int threadCount       = 20;
        int publishesPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);
        ExecutorService pool      = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int idx = t;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < publishesPerThread; i++) {
                        // block() is used so each publish completes before the next,
                        // but threads still interleave with each other.
                        publisher.publishAsync("t" + idx + "-m" + i).block();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS))
                .as("All threads completed within 10 s")
                .isTrue();
        pool.shutdown();

        assertThat(publisher.getMessages()).hasSize(threadCount * publishesPerThread);
    }
}
