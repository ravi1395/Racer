package com.cheetah.racer.annotation;

import java.lang.annotation.*;

/**
 * Declares content-based routing rules on a Spring-managed bean.
 *
 * <p>When the Racer consumer receives a message, {@code RacerRouterService} evaluates
 * each {@link RacerRouteRule} in order and re-publishes the message to the first
 * matching channel alias.  If a rule matches, the local processor is skipped.
 *
 * <h3>Usage</h3>
 * <pre>
 * &#64;Service
 * &#64;RacerRoute({
 *     &#64;RacerRouteRule(field = "eventType", matches = "ORDER.*",   to = "orders"),
 *     &#64;RacerRouteRule(field = "eventType", matches = "PAYMENT.*", to = "payments")
 * })
 * public class MyRouter { }
 * </pre>
 *
 * <p>The annotated bean class only acts as a rule container — no methods are required.
 * Rules are registered at startup by {@code RacerRouterService} via a
 * {@code BeanPostProcessor}-style scan.
 *
 * <p>Requires {@link EnableRacer} to be active so that {@code RacerRouterService} is
 * registered in the application context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerRoute {

    /** One or more routing rules, evaluated in declaration order. First match wins. */
    RacerRouteRule[] value();
}
