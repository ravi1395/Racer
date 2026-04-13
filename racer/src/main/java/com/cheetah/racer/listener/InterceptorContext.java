package com.cheetah.racer.listener;

import java.lang.reflect.Method;

/**
 * Immutable context object passed to each {@link RacerMessageInterceptor}.
 *
 * @param listenerId the resolved listener identifier
 *                   (see {@link com.cheetah.racer.annotation.RacerListener#id()})
 * @param channel    the Redis Pub/Sub channel the message arrived on
 * @param method     the handler {@link Method} that will be invoked after the interceptor chain
 */
public record InterceptorContext(String listenerId, String channel, Method method) {}
