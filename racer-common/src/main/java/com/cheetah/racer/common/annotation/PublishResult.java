package com.cheetah.racer.common.annotation;

import java.lang.annotation.*;

/**
 * Publishes the return value of an annotated method to a Redis channel.
 *
 * <p>The return value is serialized to JSON and sent to the configured channel.
 * Both reactive ({@code Mono}/{@code Flux}) and regular return types are supported:
 * <ul>
 *   <li>Reactive: result is tapped inside the reactive pipeline (non-blocking)</li>
 *   <li>Regular: result is published after the method returns</li>
 * </ul>
 *
 * <h3>Usage — direct channel name</h3>
 * <pre>
 * {@literal @}PublishResult(channel = "racer:orders", async = true)
 * public Order createOrder(OrderRequest req) { ... }
 * </pre>
 *
 * <h3>Usage — channel alias from properties</h3>
 * <pre>
 * // application.properties:
 * racer.channels.orders.name=racer:orders
 *
 * {@literal @}PublishResult(channelRef = "orders")
 * public Mono&lt;Order&gt; createOrder(OrderRequest req) { ... }
 * </pre>
 *
 * <p>{@code channel} takes priority over {@code channelRef}.
 * If neither is set, the default channel is used.
 *
 * <p>Requires {@link EnableRacer} to be active and the bean to be a Spring proxy.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublishResult {

    /**
     * Direct Redis channel name, e.g. {@code "racer:orders"}.
     * Takes priority over {@link #channelRef()}.
     */
    String channel() default "";

    /**
     * Channel alias defined under {@code racer.channels.<alias>}.
     * Resolved at runtime via {@link com.cheetah.racer.common.publisher.RacerPublisherRegistry}.
     */
    String channelRef() default "";

    /**
     * Sender identifier attached to the published message.
     */
    String sender() default "racer-publisher";

    /**
     * {@code true} = fire-and-forget (subscribe and return immediately).
     * {@code false} = blocks until Redis confirms the publish.
     */
    boolean async() default true;

    /**
     * When {@code true} the return value is published to a Redis Stream
     * (durable, consumer-group delivery) instead of Pub/Sub.
     * Requires {@code racer-client} with {@code racer.durable.stream-keys} configured.
     */
    boolean durable() default false;

    /**
     * The Redis Stream key to publish to when {@link #durable()} is {@code true}.
     * Defaults to {@code <resolvedChannelName>:stream} when blank.
     */
    String streamKey() default "";
}
