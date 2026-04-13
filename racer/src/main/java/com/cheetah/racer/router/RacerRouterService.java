package com.cheetah.racer.router;

import com.cheetah.racer.annotation.RacerRoute;
import com.cheetah.racer.annotation.RacerRouteRule;
import com.cheetah.racer.annotation.RouteAction;
import com.cheetah.racer.annotation.RouteMatchSource;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.model.PriorityLevel;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPriorityPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.cheetah.racer.router.dsl.RacerFunctionalRouter;
import com.cheetah.racer.router.dsl.RouteContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.Nullable;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Content-based message router.
 *
 * <p>Scans all Spring beans annotated with {@link RacerRoute} at startup and compiles
 * a list of routing rules.  When {@link #route(RacerMessage)} is called it evaluates
 * rules against the message payload and re-publishes to the first matching channel alias.
 * The method is fully reactive: it returns a {@code Mono<RouteDecision>} so that publish
 * failures propagate to the caller's DLQ handler rather than being swallowed.
 *
 * <h3>Thread safety</h3>
 * Rules are populated once at startup via {@link #init()} and never modified afterwards,
 * so the list is safe to read concurrently without locking.
 *
 * <h3>Routing logic</h3>
 * <ol>
 *   <li>Annotation rules ({@code @RacerRoute} / {@code @RacerRouteRule}) are evaluated first.</li>
 *   <li>If no annotation rule matched, each {@link RacerFunctionalRouter} bean is evaluated in
 *       bean-registration order; the first non-{@link RouteDecision#PASS} decision wins.</li>
 *   <li>Both systems can coexist in the same application — migrate incrementally.</li>
 *   <li>If nothing matches, {@link RouteDecision#PASS} is returned and the message is
 *       processed by the local listener unchanged.</li>
 * </ol>
 */
@Slf4j
public class RacerRouterService {

    private final ApplicationContext applicationContext;
    private final RacerPublisherRegistry registry;
    private final ObjectMapper objectMapper;
    @Nullable
    private final RacerPriorityPublisher priorityPublisher;
    private final RacerMetricsPort racerMetrics;

    private final List<CompiledRouteRule> globalRules = new ArrayList<>();
    private final List<RacerFunctionalRouter> functionalRouters = new ArrayList<>();

    public RacerRouterService(ApplicationContext applicationContext,
                              RacerPublisherRegistry registry,
                              ObjectMapper objectMapper) {
        this(applicationContext, registry, objectMapper, null, null);
    }

    public RacerRouterService(ApplicationContext applicationContext,
                              RacerPublisherRegistry registry,
                              ObjectMapper objectMapper,
                              @Nullable RacerPriorityPublisher priorityPublisher) {
        this(applicationContext, registry, objectMapper, priorityPublisher, null);
    }

    public RacerRouterService(ApplicationContext applicationContext,
                              RacerPublisherRegistry registry,
                              ObjectMapper objectMapper,
                              @Nullable RacerPriorityPublisher priorityPublisher,
                              @Nullable RacerMetricsPort racerMetrics) {
        this.applicationContext = applicationContext;
        this.registry           = registry;
        this.objectMapper       = objectMapper;
        this.priorityPublisher  = priorityPublisher;
        this.racerMetrics       = racerMetrics != null ? racerMetrics : new NoOpRacerMetrics();
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
            log.debug("[racer-router] No @RacerRoute beans found — annotation rules inactive.");
        } else {
            log.info("[racer-router] {} annotation routing rule(s) active.", globalRules.size());
        }

        Map<String, RacerFunctionalRouter> dsls =
                applicationContext.getBeansOfType(RacerFunctionalRouter.class);
        functionalRouters.addAll(dsls.values());
        functionalRouters.forEach(r ->
                log.info("[racer-router] Functional router registered: '{}' with {} rule(s).",
                        r.getName(), r.entries().size()));

        if (globalRules.isEmpty() && functionalRouters.isEmpty()) {
            log.debug("[racer-router] No routing rules found — router is inactive.");
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
    // Routing
    // -----------------------------------------------------------------------

    /**
     * Evaluates all routing rules against {@code message} and returns a
     * {@link Mono} emitting the {@link RouteDecision}.
     *
     * <p>Annotation-based global rules are checked first.  If none match, each
     * registered {@link RacerFunctionalRouter} is evaluated in bean-registration order
     * until a non-{@link RouteDecision#PASS} decision is produced.  Publishing is fully
     * chained into the reactive pipeline so Redis failures propagate to the caller.
     *
     * @param message the inbound message
     * @return a {@code Mono} that emits the routing decision when all publish I/O completes
     */
    public Mono<RouteDecision> route(RacerMessage message) {
        if (message.isRouted()) {
            log.debug("[racer-router] Skipping already-routed message id={}", message.getId());
            return Mono.just(RouteDecision.PASS);
        }

        Mono<RouteDecision> annotationStep = globalRules.isEmpty()
                ? Mono.just(RouteDecision.PASS)
                : evaluate(message, globalRules);

        return annotationStep.flatMap(decision -> {
            if (decision != RouteDecision.PASS) return Mono.just(decision);
            if (functionalRouters.isEmpty())    return Mono.just(RouteDecision.PASS);
            return evaluateFunctionalReactive(message);
        });
    }

    /**
     * Evaluates {@code rules} (which may be per-listener or global) against
     * {@code message} reactively.
     *
     * <p>Rule matching is synchronous (CPU-bound); only the publish I/O step is async.
     *
     * @param message the inbound message
     * @param rules   ordered list of compiled rules to evaluate
     * @return a {@code Mono} that emits the first matching rule's decision, or
     *         {@link RouteDecision#PASS} if no rule matched
     */
    public Mono<RouteDecision> evaluate(RacerMessage message, List<CompiledRouteRule> rules) {
        if (rules.isEmpty()) return Mono.just(RouteDecision.PASS);
        if (message.isRouted()) {
            log.debug("[racer-router] Skipping already-routed message id={} in evaluate()", message.getId());
            return Mono.just(RouteDecision.PASS);
        }

        // Rule matching is CPU-bound — done synchronously inside Mono.defer
        return Mono.defer(() -> {
            JsonNode[] payloadNode = {null}; // parsed lazily

            for (CompiledRouteRule rule : rules) {
                String candidate;
                try {
                    if (rule.source() == RouteMatchSource.SENDER) {
                        candidate = message.getSender();
                    } else if (rule.source() == RouteMatchSource.ID) {
                        candidate = message.getId();
                    } else {
                        if (payloadNode[0] == null) {
                            payloadNode[0] = objectMapper.readTree(toJsonString(message.getPayload()));
                        }
                        JsonNode fieldNode = payloadNode[0].get(rule.field());
                        if (fieldNode == null) continue;
                        candidate = fieldNode.asText();
                    }
                } catch (Exception ex) {
                    log.debug("[racer-router] Cannot evaluate rule for message id={}: {}",
                            message.getId(), ex.getMessage());
                    continue;
                }

                if (candidate != null && rule.pattern().matcher(candidate).matches()) {
                    return applyActionReactive(message, rule);
                }
            }
            return Mono.just(RouteDecision.PASS);
        });
    }

    /** Evaluates functional routers reactively, executing deferred publishes after the handler. */
    private Mono<RouteDecision> evaluateFunctionalReactive(RacerMessage message) {
        return Mono.defer(() -> {
            for (RacerFunctionalRouter router : functionalRouters) {
                DefaultRouteContext ctx = new DefaultRouteContext(message);
                RouteDecision decision = router.evaluate(message, ctx);
                if (decision != RouteDecision.PASS) {
                    // Execute all deferred publishes collected by publishTo/publishToWithPriority
                    return ctx.executePendingPublishes().thenReturn(decision);
                }
            }
            return Mono.just(RouteDecision.PASS);
        });
    }

    /**
     * Reactive version of {@link #applyAction}: returns a {@code Mono} that completes
     * once the publish has been handed off to Redis, propagating any Redis error to the
     * caller instead of silently swallowing it.
     */
    private Mono<RouteDecision> applyActionReactive(RacerMessage message, CompiledRouteRule rule) {
        if (rule.action() == RouteAction.DROP)         return Mono.just(RouteDecision.DROPPED);
        if (rule.action() == RouteAction.DROP_TO_DLQ) return Mono.just(RouteDecision.DROPPED_TO_DLQ);

        RacerChannelPublisher publisher = registry.getPublisher(rule.alias());
        String sender = rule.sender().isBlank() ? message.getSender() : rule.sender();
        RouteDecision decision = rule.action() == RouteAction.FORWARD_AND_PROCESS
                ? RouteDecision.FORWARDED_AND_PROCESS
                : RouteDecision.FORWARDED;

        return publisher.publishRoutedAsync(message.getPayload(), sender)
                .doOnNext(count -> log.debug("[racer-router] message id={} → '{}' ({} subscriber(s))",
                        message.getId(), rule.alias(), count))
                .doOnSuccess(count -> log.info("[racer-router] Forwarded message id={} → alias='{}' action={}",
                        message.getId(), rule.alias(), rule.action()))
                .doOnError(ex -> {
                    racerMetrics.recordFailed(rule.alias(), ex.getClass().getSimpleName());
                    log.error("[racer-router] Routing failed for message id={} → '{}': {}",
                            message.getId(), rule.alias(), ex.getMessage());
                })
                .thenReturn(decision);
        // Errors propagate to the caller — RacerListenerRegistrar will send to DLQ
    }

    /**
     * Dry-run: evaluates all routing rules against a synthetic message constructed from
     * the provided values, without publishing to any channel.
     *
     * <p>Unlike the deprecated {@link #dryRun(Object)}, this variant correctly evaluates
     * rules with {@code source = SENDER} and {@code source = ID} in addition to
     * {@code PAYLOAD} rules. Pass {@code null} for any source you want to exclude.
     *
     * @param payload   message payload object tested against {@code PAYLOAD} rules;
     *                  may be {@code null} to skip PAYLOAD rules
     * @param sender    sender label tested against {@code SENDER} rules;
     *                  may be {@code null} to skip SENDER rules
     * @param messageId message ID tested against {@code ID} rules;
     *                  may be {@code null} to skip ID rules
     * @return the {@link RouteDecision} from the first matching rule, or {@code null}
     *         if no rule matches
     */
    public RouteDecision dryRun(@Nullable Object payload,
                                @Nullable String sender,
                                @Nullable String messageId) {
        for (CompiledRouteRule rule : globalRules) {
            try {
                boolean matched = switch (rule.source()) {
                    case PAYLOAD -> {
                        if (payload == null) yield false;
                        JsonNode node = objectMapper.readTree(toJsonString(payload));
                        JsonNode fieldNode = node.get(rule.field());
                        yield fieldNode != null && rule.pattern().matcher(fieldNode.asText()).matches();
                    }
                    case SENDER -> sender != null && rule.pattern().matcher(sender).matches();
                    case ID     -> messageId != null && rule.pattern().matcher(messageId).matches();
                };
                if (matched) {
                    return switch (rule.action()) {
                        case FORWARD             -> RouteDecision.FORWARDED;
                        case FORWARD_AND_PROCESS -> RouteDecision.FORWARDED_AND_PROCESS;
                        case DROP                -> RouteDecision.DROPPED;
                        case DROP_TO_DLQ         -> RouteDecision.DROPPED_TO_DLQ;
                    };
                }
            } catch (Exception ex) {
                log.debug("[racer-router] Dry-run error evaluating rule source={}: {}", rule.source(), ex.getMessage());
            }
        }
        return null;
    }

    /** Returns human-readable rule descriptions for the debug REST endpoint. */
    public List<String> getRuleDescriptions() {
        List<String> descriptions = new ArrayList<>();

        globalRules.forEach(r -> {
            if (r.source() == RouteMatchSource.PAYLOAD) {
                descriptions.add(String.format("[annotation] source=PAYLOAD field='%s' matches='%s' → alias='%s' action=%s",
                        r.field(), r.pattern().pattern(), r.alias(), r.action()));
            } else {
                descriptions.add(String.format("[annotation] source=%s matches='%s' → alias='%s' action=%s",
                        r.source(), r.pattern().pattern(), r.alias(), r.action()));
            }
        });

        functionalRouters.forEach(router ->
                descriptions.add(String.format("[functional] router='%s' rules=%d",
                        router.getName(), router.entries().size())));

        return List.copyOf(descriptions);
    }

    public boolean hasRules() {
        return !globalRules.isEmpty() || !functionalRouters.isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String toJsonString(Object value) throws Exception {
        if (value instanceof String s) return s;
        return objectMapper.writeValueAsString(value);
    }

    // -----------------------------------------------------------------------
    // DefaultRouteContext — bridges DSL handlers to the publisher registry
    // -----------------------------------------------------------------------

    /**
     * {@link RouteContext} implementation that delegates publish operations to
     * {@link RacerPublisherRegistry}, mirroring what {@link #applyAction} does for
     * annotation-based rules.
     *
     * <p>Publish operations are <em>deferred</em>: calling {@code publishTo()} enqueues
     * a {@code Mono<Void>} rather than subscribing immediately.  The deferred operations
     * are executed as a reactive chain when {@link #executePendingPublishes()} is called
     * from {@link #evaluateFunctionalReactive}, ensuring errors surface instead of being
     * silently swallowed.
     */
    private final class DefaultRouteContext implements RouteContext {

        private final RacerMessage message;
        private final List<Mono<Void>> pendingPublishes = new ArrayList<>();

        DefaultRouteContext(RacerMessage message) { this.message = message; }

        @Override
        public void publishTo(String alias, RacerMessage msg) {
            publishTo(alias, msg, msg.getSender());
        }

        @Override
        public void publishTo(String alias, RacerMessage msg, String sender) {
            RacerChannelPublisher publisher = registry.getPublisher(alias);
            pendingPublishes.add(
                    publisher.publishRoutedAsync(msg.getPayload(), sender)
                            .doOnNext(count -> log.debug("[racer-router] DSL forwarded id={} → '{}' ({} subscriber(s))",
                                    message.getId(), alias, count))
                            .doOnError(ex -> {
                                racerMetrics.recordFailed(alias, ex.getClass().getSimpleName());
                                log.error("[racer-router] DSL routing failed for id={} → '{}': {}",
                                        message.getId(), alias, ex.getMessage());
                            })
                            .then()
            );
        }

        @Override
        public void publishToWithPriority(String alias, RacerMessage msg, String level) {
            var pp = priorityPublisher;
            if (pp != null) {
                String channelName = registry.getPublisher(alias).getChannelName();
                String sender = msg.getSender();
                pendingPublishes.add(
                        pp.publish(channelName, msg.getPayload(), sender, PriorityLevel.fromString(level))
                                .doOnNext(count -> log.debug("[racer-router] DSL priority-forwarded id={} → '{}:priority:{}' ({} subscriber(s))",
                                        message.getId(), alias, level, count))
                                .doOnError(ex -> {
                                    racerMetrics.recordFailed(alias, ex.getClass().getSimpleName());
                                    log.error("[racer-router] DSL priority routing failed for id={} → '{}:priority:{}': {}",
                                            message.getId(), alias, level, ex.getMessage());
                                })
                                .then()
                );
            } else {
                log.warn("[racer-router] publishToWithPriority called but no RacerPriorityPublisher configured — falling back to standard publish for id={}", message.getId());
                publishTo(alias, msg);
            }
        }

        /**
         * Executes all deferred publish operations sequentially.
         * The first error propagates to the caller (no silent swallowing).
         */
        Mono<Void> executePendingPublishes() {
            if (pendingPublishes.isEmpty()) return Mono.empty();
            return Flux.concat(pendingPublishes).then();
        }
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
