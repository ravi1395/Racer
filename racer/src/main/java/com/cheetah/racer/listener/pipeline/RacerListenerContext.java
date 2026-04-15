package com.cheetah.racer.listener.pipeline;

import java.lang.reflect.Method;

/**
 * Immutable context carried through each stage of a {@link DispatchPipeline}.
 *
 * <p>
 * Stages read this context to access metadata about the current dispatch
 * (listener ID, channel, handler method) without needing additional parameters.
 *
 * @param listenerId resolved listener identifier (from
 *                   {@code @RacerListener#id()})
 * @param channel    the Redis Pub/Sub or Stream channel the message arrived on
 * @param method     the {@code @RacerListener}-annotated handler method
 */
public record RacerListenerContext(String listenerId, String channel, Method method) {
}
