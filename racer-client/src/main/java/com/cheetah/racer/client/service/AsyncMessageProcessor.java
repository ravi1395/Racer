package com.cheetah.racer.client.service;

import com.cheetah.racer.common.model.RacerMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Asynchronous message processor.
 * Processes messages non-blocking on the reactive scheduler.
 * Multiple messages can be processed concurrently.
 */
@Slf4j
@Component("asyncProcessor")
public class AsyncMessageProcessor implements MessageProcessor {

    @Override
    public Mono<Void> process(RacerMessage message) {
        return Mono.defer(() -> {
            log.info("[ASYNC] Processing message id={} payload='{}'", message.getId(), message.getPayload());

            // Simulate a failure for messages containing "error" — demonstrates DLQ
            if (message.getPayload() != null && message.getPayload().toLowerCase().contains("error")) {
                return Mono.error(
                        new RuntimeException("Simulated async processing failure for message: " + message.getId()));
            }

            // Simulate async work with a non-blocking delay
            return Mono.delay(Duration.ofMillis(50))
                    .doOnNext(v -> log.info("[ASYNC] Completed processing message id={}", message.getId()))
                    .then();
        });
    }

    @Override
    public String getMode() {
        return "ASYNC";
    }
}
