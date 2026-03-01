package com.cheetah.racer.client.service;

import com.cheetah.racer.common.model.RacerMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Synchronous message processor.
 * Processes messages one at a time on a bounded-elastic scheduler (blocking-friendly).
 * Each message is fully processed before the next one is handled.
 */
@Slf4j
@Component("syncProcessor")
public class SyncMessageProcessor implements MessageProcessor {

    @Override
    public Mono<Void> process(RacerMessage message) {
        return Mono.fromRunnable(() -> {
            log.info("[SYNC] Processing message id={} payload='{}'", message.getId(), message.getPayload());

            // Simulate processing work (replace with real business logic)
            simulateWork(message);

            log.info("[SYNC] Completed processing message id={}", message.getId());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public String getMode() {
        return "SYNC";
    }

    private void simulateWork(RacerMessage message) {
        // Simulate a failure for messages containing "error" — used to demonstrate DLQ
        if (message.getPayload() != null && message.getPayload().toLowerCase().contains("error")) {
            throw new RuntimeException("Simulated processing failure for message: " + message.getId());
        }

        try {
            // Simulate processing delay
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
