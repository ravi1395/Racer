package com.cheetah.racer.common.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as a scheduled poller whose return value is published into a Racer channel.
 *
 * <p>The annotated method is called on the configured schedule. It is responsible for
 * fetching, computing, or assembling the data itself. The return value is then published
 * to the configured Racer channel.
 *
 * <h3>Fixed-rate example</h3>
 * <pre>
 * {@literal @}RacerPoll(fixedRate = 15_000, channel = "racer:inventory", sender = "inv-poller")
 * public String fetchInventory() {
 *     return restClient.get("https://api.example.com/inventory");
 * }
 * </pre>
 *
 * <h3>Cron example with reactive return type</h3>
 * <pre>
 * {@literal @}RacerPoll(cron = "0 0/5 * * * *", channelRef = "prices", sender = "price-poller")
 * public Mono&lt;String&gt; fetchPrices() {
 *     return webClient.get().uri("https://api.example.com/prices")
 *                    .retrieve().bodyToMono(String.class);
 * }
 * </pre>
 *
 * <h3>Supported return types</h3>
 * <ul>
 *   <li>{@code String} - published as-is.</li>
 *   <li>Any serializable object - serialized to JSON before publishing.</li>
 *   <li>{@code Mono} - subscribed to; the emitted value is published.</li>
 *   <li>{@code void} or {@code null} - nothing is published for that tick.</li>
 * </ul>
 *
 * <p>Requires {@link EnableRacer} to be active.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerPoll {

    /** Polling interval in milliseconds. Ignored when {@link #cron()} is non-empty. Default: 10000. */
    long fixedRate() default 10_000;

    /** Initial delay in milliseconds before the first poll (fixed-rate only). */
    long initialDelay() default 0;

    /** Spring 6-field cron expression. Takes precedence over {@link #fixedRate()} when set. */
    String cron() default "";

    /** Raw Redis channel name to publish to. Takes precedence over {@link #channelRef()}. */
    String channel() default "";

    /** Channel alias from {@code racer.channels.<alias>}. Used when {@link #channel()} is empty. */
    String channelRef() default "";

    /** Sender label embedded in every published message envelope. */
    String sender() default "racer-poller";

    /** When {@code true} (default), publishes fire-and-forget. {@code false} blocks until Redis confirms. */
    boolean async() default true;
}
