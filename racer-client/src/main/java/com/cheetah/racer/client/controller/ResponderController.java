package com.cheetah.racer.client.controller;

import com.cheetah.racer.client.service.PubSubResponderService;
import com.cheetah.racer.client.service.StreamResponderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Client-side controller to expose request-reply processing stats.
 */
@RestController
@RequestMapping("/api/responder")
@RequiredArgsConstructor
public class ResponderController {

    private final PubSubResponderService pubSubResponder;
    private final StreamResponderService streamResponder;

    /**
     * GET /api/responder/status
     * Returns how many request-reply messages have been processed.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "pubsub", Map.of("repliesSent", pubSubResponder.getRepliedCount()),
                "stream", Map.of("requestsProcessed", streamResponder.getProcessedCount())
        ));
    }
}
