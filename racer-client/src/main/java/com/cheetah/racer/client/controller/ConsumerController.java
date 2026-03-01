package com.cheetah.racer.client.controller;

import com.cheetah.racer.client.service.ConsumerSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for managing the consumer's processing mode and viewing stats.
 */
@RestController
@RequestMapping("/api/consumer")
@RequiredArgsConstructor
public class ConsumerController {

    private final ConsumerSubscriber consumerSubscriber;

    /**
     * GET /api/consumer/status
     * Returns current processing mode and message counts.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "mode", consumerSubscriber.getCurrentMode(),
                "processedCount", consumerSubscriber.getProcessedCount(),
                "failedCount", consumerSubscriber.getFailedCount()
        ));
    }

    /**
     * PUT /api/consumer/mode?mode=SYNC|ASYNC
     * Switches the processing mode at runtime.
     */
    @PutMapping("/mode")
    public ResponseEntity<Map<String, String>> switchMode(@RequestParam String mode) {
        try {
            String newMode = consumerSubscriber.switchMode(mode);
            return ResponseEntity.ok(Map.of(
                    "status", "switched",
                    "mode", newMode
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
