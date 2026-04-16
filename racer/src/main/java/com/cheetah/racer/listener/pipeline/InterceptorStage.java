package com.cheetah.racer.listener.pipeline;

import java.util.List;
import java.util.function.BiFunction;

import com.cheetah.racer.listener.InterceptorContext;
import com.cheetah.racer.listener.RacerMessageInterceptor;
import com.cheetah.racer.model.RacerMessage;

import reactor.core.publisher.Mono;

/**
 * Pipeline stage that applies the ordered list of
 * {@link RacerMessageInterceptor} beans.
 *
 * <p>
 * Interceptors are applied in registration order (typically
 * {@link org.springframework.core.annotation.Order}).
 * Each interceptor may mutate the message or signal an error to abort the
 * pipeline.
 * If the list is empty this stage is a no-op pass-through.
 *
 * <p>
 * The interceptor chain is pre-composed once at construction time to avoid
 * building N {@code MonoFlatMap} operator objects on every message (#7).
 */
public final class InterceptorStage implements RacerMessageStage {

    /**
     * Ordered interceptors captured at listener registration time; never mutated.
     */
    private final List<RacerMessageInterceptor> interceptors;

    /**
     * Pre-composed interceptor chain keyed by (message, interceptorContext).
     * Built once in the constructor; reused across all messages for this stage.
     */
    private final BiFunction<RacerMessage, InterceptorContext, Mono<RacerMessage>> composedChain;

    /**
     * Creates a new interceptor stage.
     *
     * @param interceptors ordered list of interceptors to apply; must not be
     *                     {@code null}
     */
    public InterceptorStage(List<RacerMessageInterceptor> interceptors) {
        this.interceptors = List.copyOf(interceptors);
        this.composedChain = buildChain(this.interceptors);
    }

    /**
     * Pre-composes the interceptor list into a single reusable BiFunction.
     */
    private static BiFunction<RacerMessage, InterceptorContext, Mono<RacerMessage>> buildChain(
            List<RacerMessageInterceptor> interceptors) {
        if (interceptors.isEmpty()) {
            return (msg, ctx) -> Mono.just(msg);
        }
        BiFunction<RacerMessage, InterceptorContext, Mono<RacerMessage>> chain = (msg, ctx) -> interceptors.get(0)
                .intercept(msg, ctx);
        for (int i = 1; i < interceptors.size(); i++) {
            final RacerMessageInterceptor next = interceptors.get(i);
            final BiFunction<RacerMessage, InterceptorContext, Mono<RacerMessage>> prev = chain;
            chain = (msg, ctx) -> prev.apply(msg, ctx).flatMap(m -> next.intercept(m, ctx));
        }
        return chain;
    }

    @Override
    public Mono<RacerMessage> execute(RacerMessage msg, RacerListenerContext ctx) {
        if (interceptors.isEmpty()) {
            return Mono.just(msg);
        }
        // Create a per-execute InterceptorContext; the pre-composed chain reuses
        // the same lambda structure across all messages for this listener.
        InterceptorContext ictx = new InterceptorContext(ctx.listenerId(), ctx.channel(), ctx.method());
        return composedChain.apply(msg, ictx);
    }
}
