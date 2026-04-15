package com.cheetah.racer.listener.pipeline;

import java.util.List;

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
 * </ul>
 */
public final class DispatchPipeline {

    private final List<RacerMessageStage> stages;

    /**
     * Creates a new pipeline with the given ordered stages.
     *
     * @param stages ordered list of stages; must not be {@code null}
     */
    public DispatchPipeline(List<RacerMessageStage> stages) {
        this.stages = List.copyOf(stages);
    }

    /**
     * Executes all stages in order, short-circuiting on empty or error.
     *
     * @param message initial message to process
     * @param ctx     listener context (listenerId, channel, method)
     * @return {@code Mono<Void>} that completes when all stages finish
     */
    public Mono<Void> execute(RacerMessage message, RacerListenerContext ctx) {
        Mono<RacerMessage> chain = Mono.just(message);
        for (RacerMessageStage stage : stages) {
            // Use switchIfEmpty to propagate empty naturally — avoids unnecessary
            // defer/flatMap nesting for the common case where no stage short-circuits.
            chain = chain.flatMap(msg -> stage.execute(msg, ctx));
        }
        return chain.then();
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
