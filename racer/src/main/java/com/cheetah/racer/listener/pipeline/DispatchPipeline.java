package com.cheetah.racer.listener.pipeline;

import java.util.List;
import java.util.function.BiFunction;

import com.cheetah.racer.model.RacerMessage;

import reactor.core.publisher.Mono;

/**
 * A fixed ordered chain of {@link RacerMessageStage} instances composed at
 * listener
 * registration time.
 *
 * <p>
 * Stages are evaluated left-to-right. If any stage returns {@code Mono.empty()}
 * the
 * chain short-circuits and subsequent stages are skipped. Errors propagate to
 * the caller
 * for DLQ handling.
 *
 * <p>
 * The pipeline is immutable after construction, which means:
 * <ul>
 * <li>No conditional feature flags are evaluated on the dispatch hot path.</li>
 * <li>Each stage object is small and JIT-inlinable.</li>
 * <li>The operator chain is pre-composed once so {@link #execute} never
 * allocates
 * intermediate {@code MonoFlatMap} objects per message (#7).</li>
 * </ul>
 */
public final class DispatchPipeline {

    private final List<RacerMessageStage> stages;

    /**
     * Pre-composed stage chain — built once in the constructor so that
     * {@link #execute} never re-assembles N {@code flatMap} operator nodes
     * per message.
     */
    private final BiFunction<RacerMessage, RacerListenerContext, Mono<RacerMessage>> composedChain;

    /**
     * Creates a new pipeline with the given ordered stages.
     *
     * @param stages ordered list of stages; must not be {@code null}
     */
    public DispatchPipeline(List<RacerMessageStage> stages) {
        this.stages = List.copyOf(stages);
        this.composedChain = buildChain(this.stages);
    }

    /**
     * Pre-composes the stage list into a single reusable BiFunction.
     * The resulting chain short-circuits on empty (stage returns
     * {@code Mono.empty()}) just as the imperative loop did.
     */
    private static BiFunction<RacerMessage, RacerListenerContext, Mono<RacerMessage>> buildChain(
            List<RacerMessageStage> stages) {
        if (stages.isEmpty()) {
            return (msg, ctx) -> Mono.just(msg);
        }
        BiFunction<RacerMessage, RacerListenerContext, Mono<RacerMessage>> chain = (msg, ctx) -> stages.get(0)
                .execute(msg, ctx);
        for (int i = 1; i < stages.size(); i++) {
            final RacerMessageStage next = stages.get(i);
            final BiFunction<RacerMessage, RacerListenerContext, Mono<RacerMessage>> prev = chain;
            chain = (msg, ctx) -> prev.apply(msg, ctx).flatMap(m -> next.execute(m, ctx));
        }
        return chain;
    }

    /**
     * Executes all stages in order via the pre-composed chain,
     * short-circuiting on empty or error.
     *
     * @param message initial message to process
     * @param ctx     listener context (listenerId, channel, method, parsedPayload)
     * @return {@code Mono<Void>} that completes when all stages finish
     */
    public Mono<Void> execute(RacerMessage message, RacerListenerContext ctx) {
        return composedChain.apply(message, ctx).then();
    }

    /**
     * Returns the number of stages in this pipeline (for logging/diagnostics).
     *
     * @return stage count
     */
    public int size() {
        return stages.size();
    }
}
