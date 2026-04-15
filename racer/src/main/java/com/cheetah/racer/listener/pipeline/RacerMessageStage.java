package com.cheetah.racer.listener.pipeline;

import com.cheetah.racer.model.RacerMessage;

import reactor.core.publisher.Mono;

/**
 * A single composable stage in a {@link DispatchPipeline}.
 *
 * <p>
 * Each stage receives the current {@link RacerMessage} and a
 * {@link RacerListenerContext}
 * carrying listener metadata. It should:
 * <ul>
 * <li>Return {@code Mono.just(msg)} to allow the next stage to execute.</li>
 * <li>Return {@code Mono.empty()} to silently short-circuit the pipeline (e.g.
 * dedup hit).</li>
 * <li>Return {@code Mono.error(...)} to send the message to the DLQ and stop
 * processing.</li>
 * </ul>
 *
 * <p>
 * Implementations are constructed once at listener registration time and are
 * therefore
 * effectively immutable during message processing — no volatile reads after
 * startup.
 */
public interface RacerMessageStage {

    /**
     * Execute this stage.
     *
     * @param msg the message being processed
     * @param ctx immutable context (listenerId, channel, method)
     * @return a {@link Mono} that emits the message to continue, emits empty to
     *         stop silently,
     *         or signals an error to trigger DLQ handling
     */
    Mono<RacerMessage> execute(RacerMessage msg, RacerListenerContext ctx);
}
