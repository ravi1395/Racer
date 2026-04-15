package com.cheetah.racer.listener.pipeline;

import java.util.concurrent.atomic.AtomicBoolean;

import com.cheetah.racer.model.RacerMessage;

import reactor.core.publisher.Mono;

/**
 * Pipeline stage that gates on the back-pressure flag.
 *
 * <p>
 * When the {@link com.cheetah.racer.backpressure.RacerBackPressureMonitor}
 * detects that
 * the listener thread-pool queue fill ratio has exceeded the configured
 * threshold it sets
 * {@code pressureActive=true}. This stage then signals an error so the calling
 * {@link DispatchPipeline} can route the message to the DLQ via the surrounding
 * {@code onErrorResume} in {@code RacerListenerRegistrar}.
 */
public final class BackPressureStage implements RacerMessageStage {

    private final AtomicBoolean pressureActive;
    private final String listenerId;

    /**
     * Creates a new back-pressure stage.
     *
     * @param pressureActive shared flag controlled by the back-pressure monitor
     * @param listenerId     listener identifier used in the error message
     */
    public BackPressureStage(AtomicBoolean pressureActive, String listenerId) {
        this.pressureActive = pressureActive;
        this.listenerId = listenerId;
    }

    @Override
    public Mono<RacerMessage> execute(RacerMessage msg, RacerListenerContext ctx) {
        if (pressureActive.get()) {
            // Signal error so the caller can route to DLQ
            return Mono.error(
                    new BackPressureActiveException("Back-pressure active on listener '" + listenerId + "'"));
        }
        return Mono.just(msg);
    }

    /**
     * Thrown when the back-pressure gate is active.
     * Caught by {@code RacerListenerRegistrar} to enqueue the message to the DLQ.
     */
    public static final class BackPressureActiveException extends RuntimeException {
        public BackPressureActiveException(String message) {
            super(message);
        }
    }
}
