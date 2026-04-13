package com.cheetah.racer.publisher;

import reactor.core.publisher.Mono;

/**
 * Publishes arbitrary payloads to a fixed Redis channel.
 *
 * <p>Obtained via {@link com.cheetah.racer.annotation.RacerPublisher} field injection
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

    /**
     * Publishes {@code payload} asynchronously with an explicit message ID.
     *
     * <p>The supplied {@code messageId} is embedded in the envelope {@code id} field
     * instead of a random UUID.  Pass a stable business key (e.g. an order ID) so that
     * {@link com.cheetah.racer.dedup.RacerDedupService} can suppress repeated deliveries
     * of the same logical event.
     *
     * @param payload   any serializable object
     * @param sender    sender identifier included in the envelope
     * @param messageId business key used as the dedup ID; if {@code null} a UUID is generated
     * @return Mono emitting the number of subscribers that received the message
     */
    default Mono<Long> publishAsync(Object payload, String sender, String messageId) {
        return publishAsync(payload, sender);
    }

    /**
     * Publishes {@code payload} asynchronously, marking the envelope as already-routed.
     * Messages published via this method will have {@code routed=true} in the envelope,
     * preventing the router from re-evaluating them (cycle prevention).
     */
    default Mono<Long> publishRoutedAsync(Object payload, String sender) {
        return publishAsync(payload, sender);
    }

    /** Redis channel name this publisher targets (e.g. {@code racer:orders}). */
    String getChannelName();

    /** Alias as configured in {@code racer.channels.<alias>}. */
    String getChannelAlias();
}
