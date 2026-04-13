package com.cheetah.racer.router;

/**
 * The outcome of evaluating routing rules against a message.
 * Returned by {@link RacerRouterService#route} and {@link RacerRouterService#evaluate}.
 *
 * <p>The caller ({@code RacerListenerRegistrar}) uses this value to decide whether to
 * proceed with local handler dispatch, forward, or drop the message.
 */
public enum RouteDecision {

    /** No rule matched — process the message locally. */
    PASS,

    /**
     * A rule matched with {@link com.cheetah.racer.annotation.RouteAction#FORWARD}:
     * the message was published to a target alias; skip local dispatch.
     */
    FORWARDED,

    /**
     * A rule matched with {@link com.cheetah.racer.annotation.RouteAction#FORWARD_AND_PROCESS}:
     * the message was published to a target alias AND local dispatch should still proceed.
     */
    FORWARDED_AND_PROCESS,

    /**
     * A rule matched with {@link com.cheetah.racer.annotation.RouteAction#DROP}:
     * the message should be silently discarded.
     */
    DROPPED,

    /**
     * A rule matched with {@link com.cheetah.racer.annotation.RouteAction#DROP_TO_DLQ}:
     * the caller must enqueue the message to the Dead Letter Queue.
     */
    DROPPED_TO_DLQ
}
