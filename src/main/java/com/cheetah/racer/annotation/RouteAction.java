package com.cheetah.racer.annotation;

/**
 * Declares what the router should do when a {@link RacerRouteRule} matches.
 *
 * <p>Used as the {@link RacerRouteRule#action()} attribute.
 */
public enum RouteAction {

    /**
     * Re-publish the message to the target alias and skip local handler dispatch.
     * This is the default behaviour (preserves the original Racer routing contract).
     */
    FORWARD,

    /**
     * Re-publish the message to the target alias <em>and</em> invoke the local handler.
     * Use this to fan out to a downstream channel while still processing the message locally.
     * The handler can inspect a {@link Routed @Routed boolean} parameter to know a forward
     * also occurred.
     */
    FORWARD_AND_PROCESS,

    /**
     * Silently discard the message. No forward, no local dispatch, no DLQ entry.
     * Useful for filtering out known noise or internally generated echo messages.
     */
    DROP,

    /**
     * Send the message to the Dead Letter Queue without invoking the local handler.
     * Use this to isolate messages matching a known poison-message pattern so they can
     * be inspected and replayed later.
     */
    DROP_TO_DLQ
}
