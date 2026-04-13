package com.cheetah.racer.router;

import com.cheetah.racer.annotation.RouteAction;
import com.cheetah.racer.annotation.RouteMatchSource;

import java.util.regex.Pattern;

/**
 * A compiled, ready-to-evaluate routing rule produced from
 * {@link com.cheetah.racer.annotation.RacerRouteRule}.
 *
 * <p>Instances are produced by {@link RacerRouterService#compile(com.cheetah.racer.annotation.RacerRoute)}
 * and stored for efficient evaluation at message-dispatch time (zero allocation per message).
 *
 * @param source  which part of the message envelope to match against
 * @param field   JSON field name (only relevant when {@code source == PAYLOAD})
 * @param pattern pre-compiled regex to test the extracted value against
 * @param alias   channel alias to forward to on a match (must be declared under
 *                {@code racer.channels.<alias>})
 * @param sender  overridden sender label written into the forwarded message
 *                (empty string = preserve the original sender)
 * @param action  what to do when this rule matches
 */
public record CompiledRouteRule(
        RouteMatchSource source,
        String field,
        Pattern pattern,
        String alias,
        String sender,
        RouteAction action) {}
