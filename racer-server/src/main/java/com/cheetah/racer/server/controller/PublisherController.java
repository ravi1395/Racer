package com.cheetah.racer.server.controller;

import com.cheetah.racer.common.RedisChannels;
import com.cheetah.racer.common.tx.RacerTransaction;
import com.cheetah.racer.server.service.PublisherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for publishing messages to Redis channels.
 */
@RestController
@RequestMapping("/api/publish")
@RequiredArgsConstructor
public class PublisherController {

    private final PublisherService publisherService;
    private final RacerTransaction racerTransaction;

    /**
     * POST /api/publish/async
     * Publishes a message asynchronously (non-blocking).
     * Body: { "channel": "optional-channel", "payload": "message content", "sender": "server-name" }
     */
    @PostMapping("/async")
    public Mono<ResponseEntity<Map<String, Object>>> publishAsync(@RequestBody Map<String, String> request) {
        String channel = request.getOrDefault("channel", RedisChannels.MESSAGE_CHANNEL);
        String payload = request.get("payload");
        String sender = request.getOrDefault("sender", "racer-server");

        return publisherService.publishAsync(channel, payload, sender)
                .map(subscribers -> ResponseEntity.ok(Map.of(
                        "status", "published",
                        "mode", "async",
                        "channel", channel,
                        "subscribers", subscribers
                )));
    }

    /**
     * POST /api/publish/sync
     * Publishes a message synchronously (blocking until confirmed).
     * Body: { "channel": "optional-channel", "payload": "message content", "sender": "server-name" }
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> publishSync(@RequestBody Map<String, String> request) {
        String channel = request.getOrDefault("channel", RedisChannels.MESSAGE_CHANNEL);
        String payload = request.get("payload");
        String sender = request.getOrDefault("sender", "racer-server");

        Long subscribers = publisherService.publishSync(channel, payload, sender);
        return ResponseEntity.ok(Map.of(
                "status", "published",
                "mode", "sync",
                "channel", channel,
                "subscribers", subscribers != null ? subscribers : 0
        ));
    }

    /**
     * POST /api/publish/batch
     * Publishes multiple messages asynchronously.
     * Body: { "channel": "optional", "payloads": ["msg1","msg2","msg3"], "sender": "server" }
     */
    @PostMapping("/batch")
    public Mono<ResponseEntity<Map<String, Object>>> publishBatch(@RequestBody Map<String, Object> request) {
        String channel = (String) request.getOrDefault("channel", RedisChannels.MESSAGE_CHANNEL);
        @SuppressWarnings("unchecked")
        var payloads = (java.util.List<String>) request.get("payloads");
        String sender = (String) request.getOrDefault("sender", "racer-server");

        return reactor.core.publisher.Flux.fromIterable(payloads)
                .flatMap(payload -> publisherService.publishAsync(channel, payload, sender))
                .collectList()
                .map(results -> ResponseEntity.ok(Map.of(
                        "status", "published",
                        "mode", "async-batch",
                        "channel", channel,
                        "messageCount", payloads.size()
                )));
    }

    /**
     * POST /api/publish/batch-atomic
     * Publishes multiple messages to (potentially different) channels as an atomic
     * sequence using {@link RacerTransaction}. All publishes are concatenated and
     * submitted in order; if any fails the returned Mono errors out.
     *
     * <p>Request body: list of {@code { "alias": "...", "payload": "...", "sender": "..." }} items.
     * The {@code alias} field resolves to a registered channel alias (see {@code racer.channels.*}).
     *
     * <p>Example:
     * <pre>
     * [
     *   { "alias": "orders",        "payload": "order-001", "sender": "checkout" },
     *   { "alias": "notifications", "payload": "notify-001" }
     * ]
     * </pre>
     */
    @PostMapping("/batch-atomic")
    public Mono<ResponseEntity<Map<String, Object>>> publishBatchAtomic(
            @RequestBody java.util.List<Map<String, Object>> items) {

        return racerTransaction.execute(tx ->
                items.forEach(item -> {
                    String alias   = (String) item.getOrDefault("alias", "");
                    String payload = (String) item.get("payload");
                    String sender  = (String) item.getOrDefault("sender", "racer-server");
                    tx.publish(alias, payload, sender);
                })
        ).map(counts -> ResponseEntity.ok(Map.of(
                "status",       "published",
                "mode",         "atomic-batch",
                "messageCount", items.size(),
                "subscriberCounts", counts
        )));
    }
}
