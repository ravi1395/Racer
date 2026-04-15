package com.cheetah.racer.listener.pipeline;

import java.util.List;

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
 */
public final class InterceptorStage implements RacerMessageStage {

    /**
     * Ordered interceptors captured at listener registration time; never mutated.
     */
    private final List<RacerMessageInterceptor> interceptors;

    /**
     * Creates a new interceptor stage.
     *
     * @param interceptors ordered list of interceptors to apply; must not be
     *                     {@code null}
     */
    public InterceptorStage(List<RacerMessageInterceptor> interceptors) {
        this.interceptors = List.copyOf(interceptors);
    }

    @Override
    public Mono<RacerMessage> execute(RacerMessage msg, RacerListenerContext ctx) {
        if (interceptors.isEmpty()) {
            return Mono.just(msg);
        }
        // Build the chain by folding over interceptors; each flatMap sequentially
        // chains on the previous
        InterceptorContext ictx = new InterceptorContext(ctx.listenerId(), ctx.channel(), ctx.method());
        Mono<RacerMessage> chain = Mono.just(msg);
        for (RacerMessageInterceptor interceptor : interceptors) {
            chain = chain.flatMap(m -> interceptor.intercept(m, ictx));
        }
        return chain;
    }
}
