package com.cheetah.racer.client.controller;

import com.cheetah.racer.client.service.DeadLetterQueueService;
import com.cheetah.racer.client.service.DlqReprocessorService;
import com.cheetah.racer.client.service.RacerRetentionService;
import com.cheetah.racer.common.model.DeadLetterMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for managing the Dead Letter Queue.
 */
@RestController
@RequestMapping("/api/dlq")
@RequiredArgsConstructor
public class DlqController {

    private final DeadLetterQueueService dlqService;
    private final DlqReprocessorService reprocessorService;
    private final RacerRetentionService retentionService;

    /**
     * GET /api/dlq/messages
     * Lists all messages currently in the DLQ.
     */
    @GetMapping("/messages")
    public Flux<DeadLetterMessage> getMessages() {
        return dlqService.peekAll();
    }

    /**
     * GET /api/dlq/size
     * Returns the current DLQ size.
     */
    @GetMapping("/size")
    public Mono<ResponseEntity<Map<String, Object>>> getSize() {
        return dlqService.size()
                .map(size -> ResponseEntity.ok(Map.of("dlqSize", (Object) size)));
    }

    /**
     * POST /api/dlq/reprocess/one?mode=ASYNC
     * Reprocess a single message from the DLQ.
     */
    @PostMapping("/reprocess/one")
    public Mono<ResponseEntity<Map<String, Object>>> reprocessOne(
            @RequestParam(defaultValue = "ASYNC") String mode) {
        return reprocessorService.reprocessOne(mode)
                .map(success -> ResponseEntity.ok(Map.of(
                        "reprocessed", (Object) success,
                        "mode", mode,
                        "totalReprocessed", reprocessorService.getReprocessedCount(),
                        "permanentlyFailed", reprocessorService.getPermanentlyFailedCount()
                )));
    }

    /**
     * POST /api/dlq/reprocess/all?mode=ASYNC
     * Reprocess all messages currently in the DLQ.
     */
    @PostMapping("/reprocess/all")
    public Mono<ResponseEntity<Map<String, Object>>> reprocessAll(
            @RequestParam(defaultValue = "ASYNC") String mode) {
        return reprocessorService.reprocessAll(mode)
                .map(count -> ResponseEntity.ok(Map.of(
                        "reprocessedCount", (Object) count,
                        "mode", mode,
                        "totalReprocessed", reprocessorService.getReprocessedCount(),
                        "permanentlyFailed", reprocessorService.getPermanentlyFailedCount()
                )));
    }

    /**
     * POST /api/dlq/republish/one
     * Republishes a single DLQ message back to the original Pub/Sub channel.
     */
    @PostMapping("/republish/one")
    public Mono<ResponseEntity<Map<String, Object>>> republishOne() {
        return reprocessorService.republishOne()
                .map(subscribers -> ResponseEntity.ok(Map.of(
                        "republished", true,
                        "subscribers", (Object) subscribers
                )));
    }

    /**
     * DELETE /api/dlq/clear
     * Clears all messages from the DLQ.
     */
    @DeleteMapping("/clear")
    public Mono<ResponseEntity<Map<String, Object>>> clear() {
        return dlqService.clear()
                .map(cleared -> ResponseEntity.ok(Map.of("cleared", (Object) cleared)));
    }

    /**
     * GET /api/dlq/stats
     * Returns DLQ processing statistics.
     */
    @GetMapping("/stats")
    public Mono<ResponseEntity<Map<String, Object>>> getStats() {
        return dlqService.size()
                .map(size -> ResponseEntity.ok(Map.of(
                        "queueSize", (Object) size,
                        "totalReprocessed", reprocessorService.getReprocessedCount(),
                        "permanentlyFailed", reprocessorService.getPermanentlyFailedCount()
                )));
    }

    /**
     * POST /api/dlq/trim
     * Immediately runs a retention trim: truncates all configured durable streams
     * to their configured max-len and removes DLQ entries older than the configured
     * max-age.
     */
    @PostMapping("/trim")
    public Mono<ResponseEntity<Map<String, Object>>> trim() {
        retentionService.trimStreams();   // synchronous stream XTRIM
        return retentionService.pruneDlq()
                .map(pruned -> ResponseEntity.ok(Map.of(
                        "status", "trimmed",
                        "dlqPruned", (Object) pruned
                )));
    }

    /**
     * GET /api/dlq/retention-config
     * Returns the current retention configuration values.
     */
    @GetMapping("/retention-config")
    public ResponseEntity<Map<String, Object>> getRetentionConfig() {
        return ResponseEntity.ok(retentionService.getConfig());
    }
}
