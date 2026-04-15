package com.cheetah.racer.listener.pipeline;

import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.schema.SchemaValidationException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Pipeline stage that validates the message payload against the registered JSON
 * Schema.
 *
 * <p>
 * Activated per-channel when {@code racer.schema.enabled=true} and a schema has
 * been
 * registered for the channel. A validation failure signals
 * {@link SchemaValidationException}
 * so the surrounding pipeline can route the message to the DLQ.
 */
@Slf4j
public final class SchemaValidationStage implements RacerMessageStage {

    private final RacerSchemaRegistry registry;

    /**
     * Creates a new schema-validation stage.
     *
     * @param registry the schema registry used for per-channel validation; must not
     *                 be {@code null}
     */
    public SchemaValidationStage(RacerSchemaRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Mono<RacerMessage> execute(RacerMessage msg, RacerListenerContext ctx) {
        try {
            registry.validateForConsume(ctx.channel(), msg.getPayload());
        } catch (SchemaValidationException e) {
            log.warn("[RACER-LISTENER] '{}' schema validation failed for id={}: {}",
                    ctx.listenerId(), msg.getId(), e.getMessage());
            return Mono.error(e);
        }
        return Mono.just(msg);
    }
}
