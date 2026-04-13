package com.cheetah.racer.test;

import com.cheetah.racer.listener.RacerListenerRegistrar;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Test utility that dispatches synthetic {@link RacerMessage} instances directly into the
 * full processing pipelines of registered
 * {@link com.cheetah.racer.annotation.RacerListener} and
 * {@link com.cheetah.racer.annotation.RacerStreamListener} handler methods, bypassing
 * Redis Pub/Sub and Redis Streams entirely.
 *
 * <h3>What runs in the pipeline</h3>
 * <p>Every stage of the real message processing pipeline executes normally:
 * <ol>
 *   <li>Back-pressure gate (if active)</li>
 *   <li>Circuit-breaker gate (if enabled)</li>
 *   <li>Deduplication check (if {@code racer.dedup.enabled=true})</li>
 *   <li>Content-based routing via {@link com.cheetah.racer.router.RacerRouterService}
 *       (if present)</li>
 *   <li>Interceptor chain ({@link com.cheetah.racer.listener.RacerMessageInterceptor}
 *       beans)</li>
 *   <li>Schema validation (if {@code racer.schema.enabled=true})</li>
 *   <li>Method argument resolution (typed parameter deserialization)</li>
 *   <li>Handler method invocation</li>
 *   <li>DLQ enqueue on failure</li>
 * </ol>
 *
 * <h3>What is bypassed</h3>
 * <ul>
 *   <li>Redis Pub/Sub subscription (replaced by the direct
 *       {@link RacerListenerRegistrar#processMessage(String, RacerMessage)} call)</li>
 *   <li>Redis Stream XREADGROUP polling and XACK (replaced by the direct
 *       {@link RacerStreamListenerRegistrar#processMessage(String, String, RacerMessage)}
 *       call)</li>
 * </ul>
 *
 * <p>Registered as a bean by {@link RacerTestAutoConfiguration} when
 * {@link RacerListenerRegistrar} is present in the application context.
 *
 * <h3>Typical usage</h3>
 * <pre>
 * {@literal @}RacerTest
 * class OrderListenerTest {
 *
 *     {@literal @}Autowired RacerTestHarness harness;
 *     {@literal @}Autowired InMemoryRacerPublisherRegistry registry;
 *
 *     {@literal @}BeforeEach
 *     void reset() { registry.resetAll(); }
 *
 *     {@literal @}Test
 *     void orderListenerPublishesConfirmation() {
 *         RacerMessage msg = RacerMessageBuilder.forChannel("racer:orders")
 *                 .payload(new Order(42, "Widget"))
 *                 .build();
 *
 *         harness.fireAt("orderService.handleOrder", msg).block();
 *
 *         registry.getTestPublisher("confirmations").assertMessageCount(1);
 *     }
 * }
 * </pre>
 *
 * @see RacerMessageBuilder
 * @see InMemoryRacerPublisherRegistry
 * @see RacerListenerRegistrar#processMessage(String, RacerMessage)
 * @see RacerStreamListenerRegistrar#processMessage(String, String, RacerMessage)
 */
@Slf4j
public class RacerTestHarness {

    /** Pub/Sub listener registrar — provides the {@link #fireAt} path. */
    private final RacerListenerRegistrar listenerRegistrar;

    /**
     * Stream listener registrar — provides the {@link #fireAtStream} path.
     * May be {@code null} when no {@link com.cheetah.racer.annotation.RacerStreamListener}
     * methods are registered or the registrar bean is absent.
     */
    @Nullable
    private final RacerStreamListenerRegistrar streamRegistrar;

    /**
     * Constructs a test harness backed by the given listener registrars.
     *
     * @param listenerRegistrar Pub/Sub listener registrar; must not be {@code null}
     * @param streamRegistrar   stream listener registrar; may be {@code null} if unused
     */
    public RacerTestHarness(RacerListenerRegistrar listenerRegistrar,
                             @Nullable RacerStreamListenerRegistrar streamRegistrar) {
        this.listenerRegistrar = listenerRegistrar;
        this.streamRegistrar   = streamRegistrar;
    }

    // ── Pub/Sub listener dispatch ──────────────────────────────────────────────

    /**
     * Fires {@code message} directly into the full processing pipeline of the
     * {@link com.cheetah.racer.annotation.RacerListener}-annotated handler registered
     * under {@code listenerId}, bypassing Redis Pub/Sub entirely.
     *
     * <p>The returned {@link Mono} must be subscribed to (e.g. via {@code .block()}) for
     * the pipeline to run.  The {@code Mono} completes when the handler finishes, or emits
     * an error if the pipeline throws (e.g. schema validation failure, invocation error).
     *
     * <p>Use {@link #getListenerRegistrations()} to discover valid listener IDs when the
     * ID is not known in advance.
     *
     * @param listenerId the listener ID set via {@code @RacerListener(id="…")} or the
     *                   auto-generated {@code "<beanName>.<methodName>"} form
     * @param message    the synthetic {@link RacerMessage} to inject into the pipeline
     * @return a {@link Mono} that completes when the pipeline finishes; emits
     *         {@link IllegalArgumentException} if no listener is registered with the ID
     */
    public Mono<Void> fireAt(String listenerId, RacerMessage message) {
        log.debug("[RACER-TEST] Firing message id='{}' at listener '{}'",
                message.getId(), listenerId);
        return listenerRegistrar.processMessage(listenerId, message);
    }

    // ── Stream listener dispatch ───────────────────────────────────────────────

    /**
     * Fires {@code message} directly into the processing pipeline of the
     * {@link com.cheetah.racer.annotation.RacerStreamListener}-annotated handler
     * registered for the given {@code streamKey} and consumer {@code group}, bypassing
     * Redis Streams entirely.
     *
     * <p>No {@code XACK} is issued since there is no real stream record.
     *
     * @param streamKey the Redis stream key (e.g. {@code "orders:stream"})
     * @param group     the consumer group name
     * @param message   the synthetic {@link RacerMessage} to inject into the pipeline
     * @return a {@link Mono} that completes when the pipeline finishes; emits
     *         {@link IllegalArgumentException} if no stream listener matches the
     *         stream key and group, or {@link IllegalStateException} if no stream
     *         registrar is present
     */
    public Mono<Void> fireAtStream(String streamKey, String group, RacerMessage message) {
        if (streamRegistrar == null) {
            return Mono.error(new IllegalStateException(
                    "[RACER-TEST] No RacerStreamListenerRegistrar is present in the application "
                    + "context. Ensure at least one @RacerStreamListener method is defined and "
                    + "RacerAutoConfiguration is imported."));
        }
        log.debug("[RACER-TEST] Firing message id='{}' at stream listener key='{}' group='{}'",
                message.getId(), streamKey, group);
        return streamRegistrar.processMessage(streamKey, group, message);
    }

    /**
     * Fires {@code message} into the stream listener identified by {@code listenerId}.
     *
     * <p>This overload is useful when the listener ID is known but the stream key / group
     * combination is not.  Use {@link #fireAtStream(String, String, RacerMessage)} when
     * you know the exact stream key and group.
     *
     * @param listenerId the stream listener ID from {@code @RacerStreamListener(id="…")}
     *                   or the auto-generated {@code "<beanName>.<methodName>"} fallback
     * @param message    the synthetic message to inject
     * @return a {@link Mono} that completes when the pipeline finishes
     */
    public Mono<Void> fireAtStreamListener(String listenerId, RacerMessage message) {
        if (streamRegistrar == null) {
            return Mono.error(new IllegalStateException(
                    "[RACER-TEST] No RacerStreamListenerRegistrar is present in the application context."));
        }
        log.debug("[RACER-TEST] Firing message id='{}' at stream listener id='{}'",
                message.getId(), listenerId);
        return streamRegistrar.processMessage(listenerId, message);
    }

    // ── Introspection ──────────────────────────────────────────────────────────

    /**
     * Returns an unmodifiable view of all registered Pub/Sub listener IDs and their
     * associated metadata.
     *
     * <p>Useful for discovering listener IDs programmatically when the exact method name
     * or bean name is not known at compile time.
     *
     * @return map of listener ID → {@link RacerListenerRegistrar.ListenerRegistration}
     */
    public Map<String, RacerListenerRegistrar.ListenerRegistration> getListenerRegistrations() {
        return listenerRegistrar.getListenerRegistrations();
    }

    /**
     * Returns {@code true} if a stream listener registrar is available (i.e. at least
     * one {@link com.cheetah.racer.annotation.RacerStreamListener} method was registered).
     *
     * @return {@code true} when {@link #fireAtStream} can be called without an error
     */
    public boolean hasStreamRegistrar() {
        return streamRegistrar != null;
    }
}
