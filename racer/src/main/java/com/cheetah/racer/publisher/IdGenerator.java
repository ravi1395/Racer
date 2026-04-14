package com.cheetah.racer.publisher;

/**
 * Strategy interface for generating unique message envelope IDs.
 *
 * <p>The active implementation is selected via the {@code racer.id-strategy} property
 * and registered as a Spring bean in
 * {@link com.cheetah.racer.config.RacerAutoConfiguration}, which wires it into
 * {@link MessageEnvelopeBuilder} at startup.
 *
 * <p>Implementations must be thread-safe and allocation-efficient, as this method
 * is called on every published message.
 */
@FunctionalInterface
public interface IdGenerator {

    /**
     * Generates a new unique message ID string.
     *
     * @return a non-null, non-blank unique identifier string suitable for use
     *         as the {@code id} field in a Racer message envelope
     */
    String generate();
}
