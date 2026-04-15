package com.cheetah.racer.exception;

/**
 * Thrown (and used as the DLQ cause) when a stream record is rejected because
 * the circuit breaker for the target listener is in the OPEN state.
 *
 * <p>DLQ consumers can catch or inspect this exception type to distinguish
 * circuit-open rejections from normal processing failures, enabling targeted
 * retry or alerting strategies.
 */
public class RacerCircuitOpenException extends RacerException {

    /**
     * Creates a new exception for the given listener.
     *
     * @param listenerId the listener ID whose circuit breaker rejected the call
     */
    public RacerCircuitOpenException(String listenerId) {
        super("Circuit breaker OPEN for listener '" + listenerId + "' — call rejected");
    }
}
