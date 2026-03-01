package com.cheetah.racer.common.publisher;

import reactor.core.publisher.Mono;

/**
 * Publishes arbitrary payloads to a fixed Redis channel.
 *
 * <p>Obtained via {@link com.cheetah.racer.common.annotation.RacerPublisher} field injection
 * or directly from {@link RacerPublisherRegistry}.
 *
 * <h3>Example</h3>
 * <pre>
 * {@literal @}RacerPublisher("orders")
 * private RacerChannelPublisher ordersPublisher;
 *
 * ordersPublisher.publishAsync(myOrder).subscribe();   // fire-and-forget
 * ordersPublisher.publishSync(myOrder);                // blocking
 * </pre>
 */
public interface RacerChannelPublisher {

    /**
     * Publishes {@code payload} to this channel asynchronously.
     *
     * @param payload any serializable object; serialized to JSON automatically
     * @return Mono emitting the number of subscribers that received the message
     */
    Mono<Long> publishAsync(Object payload);

    /**
     * Publishes {@code payload} to this channel asynchronously with a custom sender.
     *
     * @param payload any serializable object
     * @param sender  sender identifier included in the envelope
     * @return Mono emitting the number of subscribers that received the message
     */
    Mono<Long> publishAsync(Object payload, String sender);

    /**
     * Publishes {@code payload} synchronously, blocking until Redis confirms.
     *
     * @param payload any serializable object
     * @return number of subscribers that received the message
     */
    Long publishSync(Object payload);

    /** Redis channel name this publisher targets (e.g. {@code racer:orders}). */
    String getChannelName();

    /** Alias as configured in {@code racer.channels.<alias>}. */
    String getChannelAlias();
}
