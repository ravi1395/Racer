package com.cheetah.racer.client.service;

import com.cheetah.racer.common.model.RacerMessage;
import reactor.core.publisher.Mono;

/**
 * Strategy interface for processing received messages.
 * Implementations provide synchronous or asynchronous processing.
 */
public interface MessageProcessor {

    /**
     * Process the given message reactively.
     * Implementations may block (sync) or return immediately (async).
     */
    Mono<Void> process(RacerMessage message);

    /**
     * Returns the processing mode name.
     */
    String getMode();
}
