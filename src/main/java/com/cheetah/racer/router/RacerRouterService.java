package com.cheetah.racer.router;

import com.cheetah.racer.annotation.RacerRoute;
import com.cheetah.racer.annotation.RacerRouteRule;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
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
 *   <li>Deserialize the payload as a JSON node.</li>
 *   <li>For each rule, read the specified {@code field} from the payload.</li>
 *   <li>If the field value matches the rule's regex pattern, publish to the target alias.</li>
 *   <li>Return {@code true} so the caller skips local processing.</li>
 *   <li>If no rule matches, return {@code false} — caller processes locally.</li>
 * </ol>
 */
@Slf4j
public class RacerRouterService {

    private final ApplicationContext applicationContext;
    private final RacerPublisherRegistry registry;
    private final ObjectMapper objectMapper;

    private record RouteEntry(String field, Pattern pattern, String alias, String sender) {}

    private final List<RouteEntry> rules = new ArrayList<>();

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

            for (RacerRouteRule rule : annotation.value()) {
                rules.add(new RouteEntry(
                        rule.field(),
                        Pattern.compile(rule.matches()),
                        rule.to(),
                        rule.sender()
                ));
                log.info("[racer-router] Rule registered: field='{}' matches='{}' → alias='{}'",
                        rule.field(), rule.matches(), rule.to());
            }
        });

        if (rules.isEmpty()) {
            log.debug("[racer-router] No @RacerRoute beans found — router is inactive.");
        } else {
            log.info("[racer-router] {} routing rule(s) active.", rules.size());
        }
    }

    // -----------------------------------------------------------------------
    // Runtime: route or skip
    // -----------------------------------------------------------------------

    /**
     * Evaluates routing rules against {@code message}.
     *
     * @return {@code true} if the message was re-published to a channel alias
     *         (caller should skip local processing); {@code false} if no rule matched
     */
    public boolean route(RacerMessage message) {
        if (rules.isEmpty()) return false;

        try {
            String payloadStr = toJsonString(message.getPayload());
            JsonNode payloadNode = objectMapper.readTree(payloadStr);

            for (RouteEntry rule : rules) {
                JsonNode fieldNode = payloadNode.get(rule.field());
                if (fieldNode == null) continue;

                String fieldValue = fieldNode.asText();
                if (rule.pattern().matcher(fieldValue).matches()) {

                    RacerChannelPublisher publisher = registry.getPublisher(rule.alias());
                    String sender = rule.sender().isBlank() ? message.getSender() : rule.sender();

                    publisher.publishAsync(message.getPayload(), sender)
                            .subscribe(
                                    count -> log.debug("[racer-router] message id={} → '{}' ({} subscriber(s))",
                                            message.getId(), rule.alias(), count),
                                    ex -> log.error("[racer-router] Routing failed for message id={}: {}",
                                            message.getId(), ex.getMessage())
                            );

                    log.info("[racer-router] Routed message id={} — field='{}' value='{}' → alias='{}'",
                            message.getId(), rule.field(), fieldValue, rule.alias());
                    return true;
                }
            }
        } catch (Exception ex) {
            // Non-JSON payloads or field-not-found scenarios: no route, process locally
            log.debug("[racer-router] Cannot evaluate rules for message id={}: {}",
                    message.getId(), ex.getMessage());
        }

        return false;
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

            for (RouteEntry rule : rules) {
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
        return rules.stream()
                .map(r -> String.format("field='%s' matches='%s' → alias='%s'",
                        r.field(), r.pattern().pattern(), r.alias()))
                .toList();
    }

    public boolean hasRules() {
        return !rules.isEmpty();
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
