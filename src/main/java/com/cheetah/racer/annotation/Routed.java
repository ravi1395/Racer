package com.cheetah.racer.annotation;

import java.lang.annotation.*;

/**
 * Marks a {@code boolean} parameter on a {@link RacerListener}-annotated method that
 * should be populated with the routing decision at dispatch time.
 *
 * <p>When a routing rule fires with
 * {@link RouteAction#FORWARD_AND_PROCESS}, the local handler is still invoked.
 * Annotating a {@code boolean} / {@link Boolean} parameter with {@code @Routed} lets
 * the handler know that the message was also forwarded to another channel:
 *
 * <pre>
 * &#64;RacerListener(channel = "racer:events")
 * &#64;RacerRoute(&#64;RacerRouteRule(field = "eventType", matches = "AUDIT.*",
 *             to = "audit", action = RouteAction.FORWARD_AND_PROCESS))
 * public void onEvent(RacerMessage message, &#64;Routed boolean wasForwarded) {
 *     if (wasForwarded) {
 *         // acknowledge-only — downstream will do the full processing
 *     } else {
 *         // full local processing
 *     }
 * }
 * </pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>The annotated parameter must be of type {@code boolean} or {@link Boolean}.</li>
 *   <li>It may appear anywhere in the parameter list.</li>
 *   <li>At most one {@code @Routed} parameter is allowed per method.</li>
 *   <li>When the dispatch decision is {@link com.cheetah.racer.router.RouteDecision#PASS},
 *       the injected value is {@code false}.</li>
 * </ul>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Routed {}
