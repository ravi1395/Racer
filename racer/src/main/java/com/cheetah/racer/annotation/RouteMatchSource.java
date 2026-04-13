package com.cheetah.racer.annotation;

/**
 * Specifies which part of the incoming {@link com.cheetah.racer.model.RacerMessage}
 * a {@link RacerRouteRule} is matched against.
 *
 * <p>Use in the {@link RacerRouteRule#source()} attribute:
 * <pre>
 * &#64;RacerRouteRule(source = RouteMatchSource.SENDER, matches = "inventory-service", to = "audit")
 * </pre>
 */
public enum RouteMatchSource {

    /**
     * Match against a top-level JSON field inside the message payload.
     * The target field is specified via {@link RacerRouteRule#field()}.
     * This is the default.
     */
    PAYLOAD,

    /**
     * Match against the {@code sender} header of the message envelope —
     * i.e. {@code RacerMessage.getSender()}.
     * The {@link RacerRouteRule#field()} attribute is ignored when this source is used.
     */
    SENDER,

    /**
     * Match against the message's UUID {@code id} field —
     * i.e. {@code RacerMessage.getId()}.
     * Useful for routing specific replayed messages during incident investigation.
     * The {@link RacerRouteRule#field()} attribute is ignored when this source is used.
     */
    ID
}
