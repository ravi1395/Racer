package com.cheetah.racer.test;

import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.model.RacerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link RacerTestHarness}.
 *
 * <p>Uses {@link RacerTest @RacerTest} to boot a full Spring context with no-op Redis stubs.
 * A simple {@link TestOrderHandler} bean is registered via the nested
 * {@link HarnessTestConfig @TestConfiguration}; its {@code @RacerListener}-annotated method
 * is discovered by {@link com.cheetah.racer.listener.RacerListenerRegistrar} at startup.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>{@link RacerTestHarness#fireAt(String, RacerMessage)} dispatches messages through
 *       the full pipeline to the registered handler.</li>
 *   <li>An unknown listener ID results in an {@link IllegalArgumentException} from the
 *       returned {@link reactor.core.publisher.Mono}.</li>
 *   <li>{@link RacerTestHarness#getListenerRegistrations()} reflects registered listeners.</li>
 *   <li>{@link RacerTestHarness#fireAtStream} returns an error when no stream registrar is
 *       present.</li>
 * </ul>
 */
@RacerTest
class RacerTestHarnessTest {

    // ── Inner TestConfiguration ───────────────────────────────────────────────

    /**
     * Registers the {@link TestOrderHandler} bean so that
     * {@link com.cheetah.racer.listener.RacerListenerRegistrar} detects its
     * {@link RacerListener @RacerListener} method during context startup.
     *
     * <p>Nested {@link TestConfiguration @TestConfiguration} classes are automatically
     * picked up by Spring Boot's test infrastructure for the enclosing test class.
     */
    @TestConfiguration
    static class HarnessTestConfig {

        /**
         * Provides the test order handler as a Spring bean.
         *
         * @return a new {@link TestOrderHandler} instance
         */
        @Bean
        TestOrderHandler testOrderHandler() {
            return new TestOrderHandler();
        }
    }

    // ── Test listener bean ────────────────────────────────────────────────────

    /**
     * Minimal Pub/Sub handler used as a {@link RacerListener} target in tests.
     * Captures every received {@link RacerMessage} for assertion.
     *
     * <p>Registered as a Spring bean via {@link HarnessTestConfig} so that
     * {@code RacerListenerRegistrar} discovers the {@code @RacerListener} method
     * during context initialization.
     */
    static class TestOrderHandler {

        /** Thread-safe list of all messages dispatched to this handler by the test harness. */
        final List<RacerMessage> received = new CopyOnWriteArrayList<>();

        /**
         * Handles an order message injected by the test harness.
         *
         * <p>The explicit {@code id} attribute pins the listener ID so tests can call
         * {@code harness.fireAt("harness.test.handleOrder", msg)} without relying on
         * the auto-generated {@code "<beanName>.<methodName>"} form.
         *
         * @param msg the synthetic {@link RacerMessage} injected by the harness
         */
        @RacerListener(id = "harness.test.handleOrder", channel = "racer:orders")
        public void handleOrder(RacerMessage msg) {
            received.add(msg);
        }
    }

    // ── Injected beans ────────────────────────────────────────────────────────

    @Autowired RacerTestHarness harness;
    @Autowired TestOrderHandler testOrderHandler;

    @BeforeEach
    void resetHandler() {
        testOrderHandler.received.clear();
    }

    // ── fireAt() tests ────────────────────────────────────────────────────────

    @Test
    void fireAt_dispatchesMessageToRegisteredListener() {
        RacerMessage msg = RacerMessageBuilder.forChannel("racer:orders")
                .payload("order-data")
                .messageId("dispatch-id-001")
                .build();

        harness.fireAt("harness.test.handleOrder", msg).block();

        assertThat(testOrderHandler.received).hasSize(1);
        assertThat(testOrderHandler.received.get(0).getId()).isEqualTo("dispatch-id-001");
    }

    @Test
    void fireAt_multipleMessages_allDeliveredInOrder() {
        for (int i = 1; i <= 3; i++) {
            RacerMessage msg = RacerMessageBuilder.forChannel("racer:orders")
                    .messageId("id-" + i)
                    .payload("payload-" + i)
                    .build();
            harness.fireAt("harness.test.handleOrder", msg).block();
        }

        assertThat(testOrderHandler.received).hasSize(3);
        assertThat(testOrderHandler.received.get(0).getId()).isEqualTo("id-1");
        assertThat(testOrderHandler.received.get(2).getId()).isEqualTo("id-3");
    }

    @Test
    void fireAt_withUnknownListenerId_emitsIllegalArgumentError() {
        RacerMessage msg = RacerMessageBuilder.forChannel("racer:orders")
                .payload("test")
                .build();

        StepVerifier.create(harness.fireAt("no.such.listener", msg))
                .expectErrorMatches(err ->
                        err instanceof IllegalArgumentException
                        && err.getMessage().contains("no.such.listener"))
                .verify();
    }

    // ── getListenerRegistrations() ────────────────────────────────────────────

    @Test
    void getListenerRegistrations_containsRegisteredListener() {
        assertThat(harness.getListenerRegistrations())
                .containsKey("harness.test.handleOrder");
    }

    // ── fireAtStream() / hasStreamRegistrar() ─────────────────────────────────

    @Test
    void fireAtStream_withNoRegisteredStreamListeners_emitsIllegalArgumentError() {
        // RacerStreamListenerRegistrar is present but has no @RacerStreamListener methods
        // registered in this test context — expect IllegalArgumentException (stream not found).
        RacerMessage msg = RacerMessageBuilder.forChannel("racer:orders")
                .payload("test")
                .build();

        StepVerifier.create(harness.fireAtStream("orders:stream", "orders-group", msg))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void fireAtStreamListener_withUnknownListenerId_emitsIllegalArgumentError() {
        // RacerStreamListenerRegistrar is present but 'some.stream.listener' is unknown.
        RacerMessage msg = RacerMessageBuilder.forChannel("racer:orders")
                .payload("test")
                .build();

        StepVerifier.create(harness.fireAtStreamListener("some.stream.listener", msg))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void hasStreamRegistrar_returnsFalseWhenNoStreamListenerBeansPresent() {
        // RacerStreamListenerRegistrar bean exists but no @RacerStreamListener methods
        // are registered — hasStreamRegistrar() examines the registration map, not the bean.
        assertThat(harness.hasStreamRegistrar()).isFalse();
    }
}
