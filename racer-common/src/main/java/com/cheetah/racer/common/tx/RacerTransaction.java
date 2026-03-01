package com.cheetah.racer.common.tx;

import com.cheetah.racer.common.publisher.RacerPublisherRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Best-effort batch publisher that sends to multiple channels in a single reactive chain.
 *
 * <p>Channels are published sequentially via {@link Flux#concat}.
 * If any publish fails, the error propagates to the subscriber and subsequent publishes
 * in the batch are skipped (fail-fast).  This provides "all-or-nothing" semantics at the
 * application level, though not a true Redis {@code MULTI/EXEC} atomic transaction.
 *
 * <h3>Usage</h3>
 * <pre>
 * &#64;Autowired
 * private RacerTransaction racerTransaction;
 *
 * racerTransaction.execute(tx -&gt; {
 *     tx.publish("orders",        orderPayload);
 *     tx.publish("notifications", notifyPayload);
 *     tx.publish("audit",         auditPayload);
 * }).subscribe(results -&gt; ...);
 * </pre>
 *
 * <h3>Return value</h3>
 * {@code Mono<List<Long>>} — one {@code Long} per channel, representing the number of
 * Pub/Sub subscribers that received the message.
 */
@Slf4j
public class RacerTransaction {

    private final RacerPublisherRegistry registry;

    public RacerTransaction(RacerPublisherRegistry registry) {
        this.registry = registry;
    }

    /**
     * Builds and executes a batch publish.
     *
     * @param configurer lambda that registers one or more
     *                   {@link TxPublisher#publish(String, Object)} calls
     * @return {@code Mono<List<Long>>} — subscriber counts in registration order
     */
    public Mono<List<Long>> execute(Consumer<TxPublisher> configurer) {
        TxPublisher tx = new TxPublisher(registry);
        configurer.accept(tx);
        return tx.commit()
                .doOnSuccess(results ->
                        log.debug("[racer-tx] Batch complete — {} channel(s)", results.size()))
                .doOnError(ex ->
                        log.error("[racer-tx] Batch publish failed: {}", ex.getMessage()));
    }

    // -----------------------------------------------------------------------
    // Inner builder: collects (alias, payload, sender) triples
    // -----------------------------------------------------------------------

    public static class TxPublisher {

        private final RacerPublisherRegistry registry;
        private final List<PublishEntry> entries = new ArrayList<>();

        TxPublisher(RacerPublisherRegistry registry) {
            this.registry = registry;
        }

        /**
         * Enqueues a publish to the channel identified by {@code alias}.
         * Execution is deferred until {@link #commit()} is called.
         */
        public void publish(String alias, Object payload) {
            entries.add(new PublishEntry(alias, payload, null));
        }

        /** Enqueues a publish with an explicit sender label. */
        public void publish(String alias, Object payload, String sender) {
            entries.add(new PublishEntry(alias, payload, sender));
        }

        Mono<List<Long>> commit() {
            if (entries.isEmpty()) {
                return Mono.just(List.of());
            }
            List<Mono<Long>> ops = entries.stream()
                    .map(e -> e.sender != null
                            ? registry.getPublisher(e.alias()).publishAsync(e.payload, e.sender)
                            : registry.getPublisher(e.alias()).publishAsync(e.payload))
                    .toList();
            return Flux.concat(ops).collectList();
        }

        private record PublishEntry(String alias, Object payload, String sender) {}
    }
}
