package com.cheetah.racer.annotation;

import java.lang.annotation.*;

/**
 * Defines a single content-based routing rule used inside {@link RacerRoute}.
 *
 * <h3>Example</h3>
 * <pre>
 * &#64;RacerRoute({
 *     &#64;RacerRouteRule(field = "eventType", matches = "ORDER.*",   to = "orders"),
 *     &#64;RacerRouteRule(field = "eventType", matches = "PAYMENT.*", to = "payments")
 * })
 * &#64;Service
 * public class EventRouter { }
 * </pre>
 *
 * <p>Rules are evaluated in declaration order. The first matching rule wins.
 */
@Target({})   // used only as an attribute value inside @RacerRoute
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerRouteRule {

    /**
     * Top-level JSON field in the incoming message payload to inspect.
     * Defaults to {@code "type"}.
     */
    String field() default "type";

    /**
     * Regex pattern matched against the field value.
     * Example: {@code "ORDER.*"} matches {@code "ORDER_CREATED"}, {@code "ORDER_SHIPPED"}, etc.
     */
    String matches();

    /**
     * Channel alias to forward the message to.
     * Must be declared under {@code racer.channels.<alias>} in properties.
     */
    String to();

    /**
     * Override the sender label when re-publishing to the target channel.
     * If empty, the original message sender is preserved.
     */
    String sender() default "";
}
