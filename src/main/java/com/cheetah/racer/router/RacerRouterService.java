package com.cheetah.racer.router;

import com.cheetah.racer.annotation.RacerRoute;
import com.cheetah.racer.annotation.RacerRouteRule;
import com.cheetah.racer.annotation.RouteAction;
import com.cheetah.racer.annotation.RouteMatchSource;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Content-based message router.
 *
 * <p>Scans all Spring beans annotated with {@link RacerRoute} at startup and compiles
 * a list of routing rules.  When {@link #route(RacerMessage)} is called (from
 * {@code ConsumerSubscriber}), it evaluates rules against the message payload and
 * re-publishes to the first matching channel alias.
 *
 * <h3>Thread safety</h3>
 * Rules are populated once at startup via {@link #init()} and never modified afterwards,
 * so the list is safe to read concurrently without locking.
 *
 * <h3>Routing logic</h3>
 * <ol>
 *   <li>For each {@link CompiledRouteRule}, extract the match candidate from the message
 *       (payload field, sender, or id — depending on {@link RouteMatchSource}).</li>
 *   <li>If the candidate value matches the rule's regex pattern, apply the rule's
 *       {@link RouteAction}: forward, forward-and-process, drop, or drop-to-DLQ.</li>
 *   <li>Return a {@link RouteDecision} so the caller knows how to proceed.</li>
 *   <li>If no rule matches, return {@link RouteDecision#PASS} — caller processes locally.</li>
 * </ol>
 */
@Slf4j
public class RacerRouterService {

    private final ApplicationContext applicationContext;
    private final RacerPublisherRegistry registry;
    private final ObjectMapper objectMapper;

    private final List<CompiledRouteRule> globalRules = new ArrayList<>();

    public RacerRouterService(ApplicationContext applicationContext,
                              RacerPublisherRegistry registry,
                              ObjectMapper objectMapper) {
        this.applicationContext = applicationContext;
        this.registry           = registry;
        this.objectMapper       = objectMapper;
    }

    // -----------------------------------------------------------------------
    // Startup: scan beans
    // -----------------------------------------------------------------------

    @PostConstruct
    public void init() {
        applicationContext.getBeansWithAnnotation(RacerRoute.class).forEach((beanName, bean) -> {
            RacerRoute annotation = resolveAnnotation(bean);
            if (annotation == null) return;

            List<CompiledRouteRule> compiled = compile(annotation);
            globalRules.addAll(compiled);
            compiled.forEach(r -> {
                if (r.source() == RouteMatchSource.PAYLOAD) {
                    log.info("[racer-router] Rule: source=PAYLOAD field='{}' matches='{}' → alias='{}' action={}",
                            r.field(), r.pattern().pattern(), r.alias(), r.action());
                } else {
                    log.info("[racer-router] Rule: source={} matches='{}' → alias='{}' action={}",
                            r.source(), r.pattern().pattern(), r.alias(), r.action());
                }
            });
        });

        if (globalRules.isEmpty()) {
            log.debug("[racer-router] No @RacerRoute beans found — router is inactive.");
        } else {
            log.info("[racer-router] {} routing rule(s) active.", globalRules.size());
        }
    }

    // -----------------------------------------------------------------------
    // Compilation: annotation → CompiledRouteRule
    // -----------------------------------------------------------------------

    /**
     * Compiles a {@link RacerRoute} annotation into an ordered list of
     * {@link CompiledRouteRule} instances ready for evaluation.
     */
    public List<CompiledRouteRule> compile(RacerRoute annotation) {
        return compile(annotation.value());
    }

    /**
     * Compiles an array of {@link RacerRouteRule} annotations.
     * Exposed as a static helper so callers without a service reference can compile rules.
     */
    public static List<CompiledRouteRule> compile(RacerRouteRule[] rules) {
        return Arrays.stream(rules)
                .map(r -> new CompiledRouteRule(
                        r.source(),
                        r.field(),
                        Pattern.compile(r.matches()),
                        r.to(),
                        r.sender(),
                        r.action()))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Runtime: route or skip
    // -----------------------------------------------------------------------

    /**
     * Evaluates the global routing rules against {@code message} and returns a
     * {@link RouteDecision} indicating how the caller should proceed.
     *
     * @return {@link RouteDecision#PASS} if no rule matched; otherwise the decision
     *         determined by the first matching rule's {@link RouteAction}
     */
    public RouteDecision route(RacerMessage message) {
        if (globalRules.isEmpty()) return RouteDecision.PASS;
        return evaluate(message, globalRules);
    }

    /**
     * Evaluates {@code rules} (which may be per-listener or global) against
     * {@code message} and returns a {@link RouteDecision}.
     *
     * <p>The JSON payload is parsed lazily — only if at least one rule uses
     * {@link RouteMatchSource#PAYLOAD}.
     *
     * @param message the incoming message
     * @param rules   ordered list of compiled rules to evaluate
     * @return the first matching rule's decision, or {@link RouteDecision#PASS}
     */
    public RouteDecision evaluate(RacerMessage message, List<CompiledRouteRule> rules) {
        if (rules.isEmpty()) return RouteDecision.PASS;

        JsonNode payloadNode = null; // parsed lazily

        for (CompiledRouteRule rule : rules) {
            String candidate;
            try {
                if (rule.source() == RouteMatchSource.SENDER) {
                    candidate = message.getSender();
                } else if (rule.source() == RouteMatchSource.ID) {
                    candidate = message.getId();
                } else {
                    // PAYLOAD (default) — JSON field lookup
                    if (payloadNode == null) {
                        payloadNode = objectMapper.readTree(toJsonString(message.getPayload()));
                    }
                    JsonNode fieldNode = payloadNode.get(rule.field());
                    if (fieldNode == null) continue;
                    candidate = fieldNode.asText();
                }
            } catch (Exception ex) {
                log.debug("[racer-router] Cannot evaluate rule for message id={}: {}",
                        message.getId(), ex.getMessage());
                continue;
            }

            if (candidate != null && rule.pattern().matcher(candidate).matches()) {
                return applyAction(message, rule);
            }
        }

        return RouteDecision.PASS;
    }

    private RouteDecision applyAction(RacerMessage message, CompiledRouteRule rule) {
        if (rule.action() == RouteAction.DROP)         return RouteDecision.DROPPED;
        if (rule.action() == RouteAction.DROP_TO_DLQ) return RouteDecision.DROPPED_TO_DLQ;

        // FORWARD or FORWARD_AND_PROCESS — publish to target alias
        RacerChannelPublisher publisher = registry.getPublisher(rule.alias());
        String sender = rule.sender().isBlank() ? message.getSender() : rule.sender();

        publisher.publishAsync(message.getPayload(), sender)
                .subscribe(
                        count -> log.debug("[racer-router] message id={} → '{}' ({} subscriber(s))",
                                message.getId(), rule.alias(), count),
                        ex -> log.error("[racer-router] Routing failed for message id={}: {}",
                                message.getId(), ex.getMessage())
                );

        log.info("[racer-router] Forwarded message id={} → alias='{}' action={}",
                message.getId(), rule.alias(), rule.action());

        return rule.action() == RouteAction.FORWARD_AND_PROCESS
                ? RouteDecision.FORWARDED_AND_PROCESS
                : RouteDecision.FORWARDED;
    }

    /**
     * Dry-run: evaluate rules without publishing.
     *
     * @param payload sample payload object to test against the rules
     * @return the channel alias that would be selected, or {@code null} if no rule matches
     */
    public String dryRun(Object payload) {
        try {
            String payloadStr = toJsonString(payload);
            JsonNode payloadNode = objectMapper.readTree(payloadStr);

            for (CompiledRouteRule rule : globalRules) {
                if (rule.source() != RouteMatchSource.PAYLOAD) continue;
                JsonNode fieldNode = payloadNode.get(rule.field());
                if (fieldNode == null) continue;
                if (rule.pattern().matcher(fieldNode.asText()).matches()) {
                    return rule.alias();
                }
            }
        } catch (Exception ex) {
            log.debug("[racer-router] Dry-run error: {}", ex.getMessage());
        }
        return null;
    }

    /** Returns human-readable rule descriptions for the debug REST endpoint. */
    public List<String> getRuleDescriptions() {
        return globalRules.stream()
                .map(r -> {
                    if (r.source() == RouteMatchSource.PAYLOAD) {
                        return String.format("source=PAYLOAD field='%s' matches='%s' → alias='%s' action=%s",
                                r.field(), r.pattern().pattern(), r.alias(), r.action());
                    }
                    return String.format("source=%s matches='%s' → alias='%s' action=%s",
                            r.source(), r.pattern().pattern(), r.alias(), r.action());
                })
                .toList();
    }

    public boolean hasRules() {
        return !globalRules.isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String toJsonString(Object value) throws Exception {
        if (value instanceof String s) return s;
        return objectMapper.writeValueAsString(value);
    }

    /** Handles CGLIB-proxied beans where {@code getClass().getAnnotation()} returns null. */
    private RacerRoute resolveAnnotation(Object bean) {
        RacerRoute ann = bean.getClass().getAnnotation(RacerRoute.class);
        if (ann == null && bean.getClass().getSuperclass() != null) {
            ann = bean.getClass().getSuperclass().getAnnotation(RacerRoute.class);
        }
        return ann;
    }
}
