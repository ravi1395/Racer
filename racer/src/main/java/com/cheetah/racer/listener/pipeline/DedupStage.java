package com.cheetah.racer.listener.pipeline;

import org.springframework.lang.Nullable;

import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Pipeline stage that performs message deduplication via Redis
 * {@code SET NX EX}.
 *
 * <p>
 * The dedup key is resolved as follows:
 * <ol>
 * <li>If {@code fieldKey} is set, the named field is extracted from the payload
 * JSON
 * and the key becomes {@code <channel>:<fieldValue>}.</li>
 * <li>Otherwise the envelope UUID ({@link RacerMessage#getId()}) is used.</li>
 * </ol>
 *
 * <p>
 * If the message has already been processed (duplicate), this stage returns
 * {@code Mono.empty()} to silently short-circuit the pipeline.
 */
@Slf4j
public final class DedupStage implements RacerMessageStage {

    private final RacerDedupService dedupService;

    /**
     * Field name to extract from payload JSON for the dedup key; {@code null} → use
     * envelope id.
     */
    @Nullable
    private final String fieldKey;

    private final ObjectMapper objectMapper;

    /**
     * Creates a new dedup stage.
     *
     * @param dedupService the dedup service performing Redis NX checks; must not be
     *                     {@code null}
     * @param fieldKey     optional JSON field name to extract a stable business
     *                     key; may be {@code null}
     * @param objectMapper used to parse the payload for {@code fieldKey} extraction
     */
    public DedupStage(RacerDedupService dedupService, @Nullable String fieldKey, ObjectMapper objectMapper) {
        this.dedupService = dedupService;
        this.fieldKey = fieldKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<RacerMessage> execute(RacerMessage msg, RacerListenerContext ctx) {
        String effectiveKey = resolveKey(msg, ctx);
        return dedupService.checkAndMarkProcessed(effectiveKey, ctx.listenerId())
                .flatMap(shouldProcess -> {
                    if (!shouldProcess) {
                        log.debug("[RACER-LISTENER] '{}' duplicate dedupId={} — skipped",
                                ctx.listenerId(), effectiveKey);
                        return Mono.<RacerMessage>empty();
                    }
                    return Mono.just(msg);
                });
    }

    /**
     * Resolves the dedup key from the message envelope or from a payload field.
     * Uses the pre-parsed {@link RacerListenerContext#parsedPayload()} when
     * available to avoid a redundant JSON parse (#6).
     *
     * @param msg the message being processed
     * @param ctx the listener context carrying channel and optional parsed payload
     * @return the resolved dedup key
     */
    private String resolveKey(RacerMessage msg, RacerListenerContext ctx) {
        if (fieldKey != null && !fieldKey.isEmpty()) {
            String payload = msg.getPayload() != null ? msg.getPayload() : "";
            try {
                // Reuse the pre-parsed tree when available — avoids a second
                // objectMapper.readTree() call on the same payload.
                com.fasterxml.jackson.databind.JsonNode node = ctx.parsedPayload() != null
                        ? ctx.parsedPayload()
                        : objectMapper.readTree(payload);
                com.fasterxml.jackson.databind.JsonNode field = node.get(fieldKey);
                if (field != null && !field.isNull()) {
                    return ctx.channel() + ":" + field.asText();
                }
                log.warn("[RACER-LISTENER] dedupKey '{}' not found in payload for channel '{}' — "
                        + "falling back to envelope id.", fieldKey, ctx.channel());
            } catch (Exception e) {
                log.warn("[RACER-LISTENER] Could not extract dedupKey '{}' from payload: {} — "
                        + "falling back to envelope id.", fieldKey, e.getMessage());
            }
        }
        return msg.getId();
    }
}
