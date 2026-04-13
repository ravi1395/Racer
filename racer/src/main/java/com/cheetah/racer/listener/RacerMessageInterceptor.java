package com.cheetah.racer.listener;

import com.cheetah.racer.model.RacerMessage;
import reactor.core.publisher.Mono;

/**
 * SPI for programmatic message interception before a
 * {@link com.cheetah.racer.annotation.RacerListener} handler is invoked.
 *
 * <p>Interceptors are applied in {@link org.springframework.core.annotation.Order} order after
 * routing decisions have been made and before the handler method is called. Each interceptor
 * receives the current message and may return a new (possibly mutated) {@link RacerMessage}
 * or an error signal to abort processing.
 *
 * <h3>Registration</h3>
 * Declare an interceptor as a Spring bean and it is picked up automatically by
 * {@link RacerListenerRegistrar}:
 * <pre>
 * &#64;Bean
 * &#64;Order(10)
 * public RacerMessageInterceptor loggingInterceptor() {
 *     return (message, ctx) -&gt; {
 *         log.info("Received {} on {}", message.getId(), ctx.channel());
 *         return Mono.just(message);
 *     };
 * }
 * </pre>
 */
@FunctionalInterface
public interface RacerMessageInterceptor {

    /**
     * Intercept an incoming message before it reaches the listener method.
     *
     * @param message the incoming message (possibly already mutated by a previous interceptor)
     * @param context metadata about the target listener
     * @return a {@link Mono} emitting the (possibly mutated) message, or an error to abort dispatch
     */
    Mono<RacerMessage> intercept(RacerMessage message, InterceptorContext context);
}
