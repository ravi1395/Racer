package com.cheetah.racer.server.controller;

import com.cheetah.racer.common.model.RacerReply;
import com.cheetah.racer.server.service.PubSubRequestReplyService;
import com.cheetah.racer.server.service.StreamRequestReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * REST controller for two-way request-reply communication.
 * The server sends a request and waits for the client's reply.
 */
@RestController
@RequestMapping("/api/request")
@RequiredArgsConstructor
public class RequestReplyController {

    private final PubSubRequestReplyService pubSubService;
    private final StreamRequestReplyService streamService;

    /**
     * POST /api/request/pubsub
     * Send a request via Pub/Sub and wait for the reply.
     * Body: { "payload": "...", "sender": "...", "timeoutSeconds": 30 }
     */
    @PostMapping("/pubsub")
    public Mono<ResponseEntity<Map<String, Object>>> pubsubRequestReply(@RequestBody Map<String, Object> body) {
        String payload = (String) body.get("payload");
        String sender = (String) body.getOrDefault("sender", "racer-server");
        int timeoutSec = body.containsKey("timeoutSeconds")
                ? ((Number) body.get("timeoutSeconds")).intValue() : 30;

        return pubSubService.requestReply(payload, sender, Duration.ofSeconds(timeoutSec))
                .map(reply -> ResponseEntity.ok(Map.<String, Object>of(
                        "transport", "pubsub",
                        "correlationId", reply.getCorrelationId(),
                        "success", reply.isSuccess(),
                        "reply", reply.getPayload() != null ? reply.getPayload() : "",
                        "responder", reply.getResponder(),
                        "errorMessage", reply.getErrorMessage() != null ? reply.getErrorMessage() : ""
                )))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(504).body(Map.of(
                        "transport", "pubsub",
                        "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
                ))));
    }

    /**
     * POST /api/request/stream
     * Send a request via Redis Streams and wait for the reply.
     * Body: { "payload": "...", "sender": "...", "timeoutSeconds": 30 }
     */
    @PostMapping("/stream")
    public Mono<ResponseEntity<Map<String, Object>>> streamRequestReply(@RequestBody Map<String, Object> body) {
        String payload = (String) body.get("payload");
        String sender = (String) body.getOrDefault("sender", "racer-server");
        int timeoutSec = body.containsKey("timeoutSeconds")
                ? ((Number) body.get("timeoutSeconds")).intValue() : 30;

        return streamService.requestReply(payload, sender, Duration.ofSeconds(timeoutSec))
                .map(reply -> ResponseEntity.ok(Map.<String, Object>of(
                        "transport", "stream",
                        "correlationId", reply.getCorrelationId(),
                        "success", reply.isSuccess(),
                        "reply", reply.getPayload() != null ? reply.getPayload() : "",
                        "responder", reply.getResponder(),
                        "errorMessage", reply.getErrorMessage() != null ? reply.getErrorMessage() : ""
                )))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(504).body(Map.of(
                        "transport", "stream",
                        "error", e.getMessage() != null ? e.getMessage() : "Unknown error"
                ))));
    }
}
