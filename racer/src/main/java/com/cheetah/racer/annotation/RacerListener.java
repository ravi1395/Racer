package com.cheetah.racer.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as a Racer channel receiver.
 *
 * <p>The annotated method is auto-subscribed to the specified Redis Pub/Sub channel at
 * application startup. Incoming messages are deserialized and dispatched to the method
 * according to the configured {@link #mode() concurrency mode}.
 *
 * <h3>Supported parameter types</h3>
 * The method must declare exactly <strong>one</strong> parameter of any of these types:
 * <ul>
 *   <li>{@code RacerMessage} — receives the full message envelope (id, channel, payload,
 *       sender, timestamp, priority).</li>
 *   <li>{@code String} — receives the raw payload string.</li>
 *   <li>Any other type {@code T} — the payload JSON is automatically deserialized into
 *       an instance of {@code T} via Jackson.</li>
 * </ul>
 *
 * <h3>Supported return types</h3>
 * <ul>
 *   <li>{@code void} / {@code Void} — fire-and-forget; method runs to completion.</li>
 *   <li>{@code Mono<Void>} / {@code Mono<?>} — the returned {@code Mono} is subscribed
 *       to; downstream errors are caught and forwarded to the DLQ.</li>
 * </ul>
 *
 * <h3>Sequential processing (default)</h3>
 * <pre>
 * &#64;RacerListener(channel = "racer:orders")
 * public void onOrder(RacerMessage message) {
 *     orderService.handle(message.getPayload());
 * }
 * </pre>
 *
 * <h3>Concurrent processing with 8 workers — channel alias from properties</h3>
 * <pre>
 * // application.yaml:
 * racer:
 *   channels:
 *     payments:
 *       name: racer:payments:incoming
 *
 * &#64;RacerListener(channelRef = "payments", mode = ConcurrencyMode.CONCURRENT, concurrency = 8)
 * public Mono&lt;Void&gt; onPayment(PaymentDto dto) {
 *     return paymentService.process(dto);
 * }
 * </pre>
 *
 * <h3>Raw-payload listener with CONCURRENT workers</h3>
 * <pre>
 * &#64;RacerListener(channel = "racer:raw-events", mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
 * public void onEvent(String rawJson) {
 *     eventBus.dispatch(rawJson);
 * }
 * </pre>
 *
 * <p>Failed messages are automatically forwarded to the Dead Letter Queue when
 * {@code DeadLetterQueueService} is available. Schema validation and content-based
 * routing (via {@code @RacerRoute}) are applied automatically when those features are active.
 *
 * <p>Requires {@link EnableRacer} to be active.
 *
 * @see ConcurrencyMode
 * @see EnableRacer
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerListener {

    /**
     * Raw Redis channel name to subscribe to, e.g. {@code "racer:orders"}.
     * Supports Spring property placeholder syntax: {@code "${racer.my-channel}"}.
     * Takes precedence over {@link #channelRef()} when both are specified.
     */
    String channel() default "";

    /**
     * Channel alias defined under {@code racer.channels.<alias>.name} in application
     * properties. Resolved at runtime via {@code RacerProperties}.
     * Used when {@link #channel()} is empty.
     */
    String channelRef() default "";

    /**
     * Concurrency strategy for dispatching incoming messages.
     * <ul>
     *   <li>{@link ConcurrencyMode#SEQUENTIAL} (default) — one message at a time.</li>
     *   <li>{@link ConcurrencyMode#CONCURRENT} — up to {@link #concurrency()} parallel workers.</li>
     *   <li>{@link ConcurrencyMode#AUTO} — adaptively tunes worker count based on throughput
     *       and error rate; {@link #concurrency()} is ignored.</li>
     * </ul>
     */
    ConcurrencyMode mode() default ConcurrencyMode.SEQUENTIAL;

    /**
     * Number of parallel workers when {@link #mode()} is {@link ConcurrencyMode#CONCURRENT}.
     * Ignored in {@code SEQUENTIAL} mode (effective concurrency is always 1) and in
     * {@code AUTO} mode (concurrency is managed adaptively by {@link AdaptiveConcurrencyTuner}).
     * Must be &ge; 1. Defaults to 4.
     */
    int concurrency() default 4;

    /**
     * Optional listener identifier used in log messages and metrics tags.
     * If empty, defaults to {@code "<beanName>.<methodName>"}.
     */
    String id() default "";

    /**
     * When {@code true}, enables idempotency deduplication for this listener.
     *
     * <p>Before dispatching each message, the listener checks whether the message's
     * dedup key has been processed recently (within {@code racer.dedup.ttl-seconds}).
     * Duplicate messages are silently dropped without invoking the handler.
     *
     * <p>Requires {@code racer.dedup.enabled=true}. When the global flag is off, this
     * per-listener setting is ignored and all messages flow through normally.
     *
     * @see com.cheetah.racer.dedup.RacerDedupService
     */
    boolean dedup() default false;

    /**
     * Optional field name to extract from the message payload JSON and use as the
     * stable deduplication key when {@link #dedup()} is {@code true}.
     *
     * <p>When set, Racer reads this field from the deserialized payload object and uses
     * its value (namespaced by channel and listener ID) as the Redis dedup key.  This
     * allows messages that carry the same business identity (e.g. the same
     * {@code orderId}) to be deduplicated even when they arrive as separate envelopes
     * with distinct auto-generated IDs.
     *
     * <p>When empty (the default), Racer falls back to a SHA-256 hash of the channel
     * plus raw payload string, so that structurally identical payloads are always
     * deduplicated regardless of the envelope UUID.
     *
     * <p>Example: {@code @RacerListener(dedup = true, dedupKey = "orderId")}
     */
    String dedupKey() default "";

    /**
     * Poll interval in milliseconds for durable (stream-backed) listeners.
     *
     * <p>Controls how frequently the consumer issues an XREADGROUP call after each
     * batch is fully processed. Lower values reduce end-to-end latency at the cost of
     * more Redis round-trips; higher values reduce Redis load at the cost of increased
     * message processing latency.
     *
     * <p>Only applies when the channel alias is configured with
     * {@code racer.channels.<alias>.durable=true}. Ignored for standard Pub/Sub
     * listeners.
     *
     * <p>A value of {@code 0} (or negative) falls back to the default of {@code 200} ms.
     *
     * <p>Example — low-latency consumer: {@code @RacerListener(channelRef = "orders", pollIntervalMs = 50)}
     * <p>Example — rate-limited consumer: {@code @RacerListener(channelRef = "reports", pollIntervalMs = 5000)}
     */
    long pollIntervalMs() default 200;
}
