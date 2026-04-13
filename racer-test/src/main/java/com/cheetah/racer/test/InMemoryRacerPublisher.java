package com.cheetah.racer.test;

import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of {@link RacerChannelPublisher} for use in unit and
 * integration tests.
 *
 * <p>Instead of publishing to Redis, all messages are captured in a thread-safe
 * {@link CopyOnWriteArrayList}.  Tests can retrieve and assert on published messages
 * without requiring a live Redis connection.
 *
 * <p>Instances are created and managed by {@link InMemoryRacerPublisherRegistry}.
 * Tests access them via {@link InMemoryRacerPublisherRegistry#getTestPublisher(String)}.
 *
 * <h3>Typical usage</h3>
 * <pre>
 * {@literal @}RacerTest
 * class OrderServiceTest {
 *
 *     {@literal @}Autowired InMemoryRacerPublisherRegistry registry;
 *
 *     {@literal @}BeforeEach
 *     void reset() { registry.resetAll(); }
 *
 *     {@literal @}Test
 *     void shouldPublishOrderEvent() {
 *         service.processOrder(order);
 *
 *         registry.getTestPublisher("orders")
 *                 .assertMessageCount(1)
 *                 .assertPayload(0, expectedOrderEvent);
 *     }
 * }
 * </pre>
 *
 * @see InMemoryRacerPublisherRegistry
 * @see RacerTestHarness
 */
@Slf4j
public class InMemoryRacerPublisher implements RacerChannelPublisher {

    /** Redis channel name this publisher logically targets (e.g. {@code racer:orders}). */
    private final String channelName;

    /** Channel alias as configured in {@code racer.channels.<alias>}. */
    private final String channelAlias;

    /** Jackson mapper used for payload equality comparisons in assertion helpers. */
    private final ObjectMapper objectMapper;

    /** Thread-safe list of all publish events captured since construction or last reset. */
    private final List<CapturedMessage> messages = new CopyOnWriteArrayList<>();

    /**
     * Creates an in-memory publisher for the given channel.
     *
     * @param channelName  the Redis channel name (e.g. {@code racer:orders})
     * @param channelAlias the alias from {@code racer.channels.<alias>}
     * @param objectMapper Jackson mapper used for payload comparison in assertions
     */
    public InMemoryRacerPublisher(String channelName, String channelAlias,
                                   ObjectMapper objectMapper) {
        this.channelName  = channelName;
        this.channelAlias = channelAlias;
        this.objectMapper = objectMapper;
    }

    // ── RacerChannelPublisher ──────────────────────────────────────────────────

    /**
     * Captures {@code payload} (with no sender) and immediately completes with {@code 1L}.
     *
     * @param payload any object; stored as-is for later assertion
     * @return {@code Mono.just(1L)} — always succeeds
     */
    @Override
    public Mono<Long> publishAsync(Object payload) {
        return publishAsync(payload, null);
    }

    /**
     * Captures {@code payload} and {@code sender}, then immediately completes with {@code 1L}.
     *
     * @param payload any object; stored as-is for later assertion
     * @param sender  sender label included in the capture record; may be {@code null}
     * @return {@code Mono.just(1L)} — always succeeds
     */
    @Override
    public Mono<Long> publishAsync(Object payload, String sender) {
        capture(payload, sender);
        log.debug("[RACER-TEST] Captured async publish → channel='{}' alias='{}' payload={}",
                channelName, channelAlias, payload);
        return Mono.just(1L);
    }

    /**
     * Captures {@code payload} synchronously and returns {@code 1L}.
     *
     * @param payload any object; stored as-is for later assertion
     * @return {@code 1L} — always succeeds
     */
    @Override
    public Long publishSync(Object payload) {
        capture(payload, null);
        log.debug("[RACER-TEST] Captured sync publish → channel='{}' alias='{}' payload={}",
                channelName, channelAlias, payload);
        return 1L;
    }

    /** {@inheritDoc} */
    @Override
    public String getChannelName() { return channelName; }

    /** {@inheritDoc} */
    @Override
    public String getChannelAlias() { return channelAlias; }

    // ── Assertion helpers ──────────────────────────────────────────────────────

    /**
     * Returns an unmodifiable view of all captured publish events, in insertion order.
     *
     * @return all messages captured since construction or the last {@link #reset()} call
     */
    public List<CapturedMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * Asserts that exactly {@code count} messages have been published to this channel.
     * Returns {@code this} to support fluent assertion chains.
     *
     * @param count expected number of captured messages
     * @return this publisher for fluent chaining
     * @throws AssertionError if the actual count does not equal {@code count}
     */
    public InMemoryRacerPublisher assertMessageCount(int count) {
        if (messages.size() != count) {
            throw new AssertionError(
                    "Channel alias '" + channelAlias + "': expected " + count
                    + " published message(s) but found " + messages.size() + ".");
        }
        return this;
    }

    /**
     * Asserts that the payload at {@code index} equals {@code expected}.
     *
     * <p>Equality is determined by serializing both the captured payload and the expected
     * value to JSON via Jackson and comparing the resulting strings.  This means two POJO
     * instances with the same field values are considered equal even if they are different
     * object instances.
     *
     * @param index    zero-based index into the captured message list
     * @param expected the expected payload value; will be serialized to JSON for comparison
     * @return this publisher for fluent chaining
     * @throws AssertionError            if the payloads do not match or serialization fails
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     */
    public InMemoryRacerPublisher assertPayload(int index, Object expected) {
        try {
            String expectedJson = objectMapper.writeValueAsString(expected);
            String actualJson   = objectMapper.writeValueAsString(messages.get(index).payload());
            if (!expectedJson.equals(actualJson)) {
                throw new AssertionError(
                        "Channel alias '" + channelAlias + "': payload mismatch at index " + index
                        + "\n  Expected : " + expectedJson
                        + "\n  Actual   : " + actualJson);
            }
        } catch (AssertionError e) {
            // Re-throw assertion errors directly so the stack trace stays clean.
            throw e;
        } catch (Exception e) {
            throw new AssertionError(
                    "Channel alias '" + channelAlias + "': could not compare payloads at index "
                    + index + " — " + e.getMessage(), e);
        }
        return this;
    }

    /**
     * Clears all captured messages.
     * Call this in a {@code @BeforeEach} setup method to isolate test cases.
     */
    public void reset() {
        messages.clear();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Records a single publish event.
     *
     * @param payload the payload object
     * @param sender  the sender label; may be {@code null}
     */
    private void capture(Object payload, String sender) {
        messages.add(new CapturedMessage(payload, sender, Instant.now()));
    }

    // ── Inner record ──────────────────────────────────────────────────────────

    /**
     * Represents a single publish event captured by this in-memory publisher.
     *
     * @param payload    the object passed to {@code publishAsync} / {@code publishSync}
     * @param sender     the sender label; {@code null} if not provided
     * @param capturedAt the instant at which the event was captured
     */
    public record CapturedMessage(Object payload, String sender, Instant capturedAt) {}
}
