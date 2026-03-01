package com.cheetah.racer.server.controller;

import com.cheetah.racer.common.router.RacerRouterService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller exposing visibility into the Racer content-based router.
 *
 * <p>Only active when a {@link RacerRouterService} bean is present in the context
 * (i.e. at least one {@code @RacerRoute}-annotated bean has been registered).
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /api/router/rules}  — list all compiled routing rules</li>
 *   <li>{@code POST /api/router/test}   — dry-run: evaluate routing without publishing</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/router")
@RequiredArgsConstructor
@ConditionalOnBean(RacerRouterService.class)
public class RouterController {

    private final RacerRouterService racerRouterService;

    /**
     * GET /api/router/rules
     * Returns descriptions of every compiled routing rule.
     *
     * <p>Example response:
     * <pre>
     * {
     *   "ruleCount": 2,
     *   "rules": [
     *     "bean=OrderRouter  field=type  pattern=^ORDER.*  →  alias=orders  sender=(default)",
     *     "bean=OrderRouter  field=status  pattern=^FAILED$  →  alias=dlq  sender=system"
     *   ]
     * }
     * </pre>
     */
    @GetMapping("/rules")
    public ResponseEntity<Map<String, Object>> getRules() {
        List<String> descriptions = racerRouterService.getRuleDescriptions();
        return ResponseEntity.ok(Map.of(
                "ruleCount", descriptions.size(),
                "rules",     descriptions
        ));
    }

    /**
     * POST /api/router/test
     * Dry-run: evaluate routing rules against the given payload object without
     * actually publishing anything.
     *
     * <p>Request body: any JSON object (will be inspected field by field).
     *
     * <p>Example response:
     * <pre>
     * { "matched": "orders" }          // a rule matched; 'orders' is the channel alias
     * { "matched": null }              // no rule matched
     * </pre>
     *
     * @param payload arbitrary JSON object to test against the routing rules
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testRouting(@RequestBody Object payload) {
        String matchedAlias = racerRouterService.dryRun(payload);
        return ResponseEntity.ok(Map.of(
                "matched", matchedAlias != null ? matchedAlias : "null"
        ));
    }
}
