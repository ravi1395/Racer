package com.cheetah.racer.publisher;

import java.io.StringWriter;
import java.time.Instant;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.lang.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import reactor.core.publisher.Mono;

/**
 * Static factory that builds the standard Racer JSON envelope and serializes
 * it.
 *
 * <p>
 * Eliminates the near-identical {@code serializeEnvelope / serialize} private
 * methods
 * that were duplicated across {@link RacerChannelPublisherImpl},
 * {@link RacerPipelinedPublisher}, and {@link RacerPriorityPublisher}.
 *
 * <h3>Payload serialization contract</h3>
 * The {@code payload} argument is always embedded in the envelope as a <em>JSON
 * string</em>
 * rather than a nested JSON object:
 * <ul>
 * <li>If {@code payload} is already a {@link String} it is used verbatim.</li>
 * <li>Otherwise it is serialized via
 * {@code objectMapper.writeValueAsString(payload)}
 * and the resulting JSON string is embedded.</li>
 * </ul>
 * This guarantees that
 * {@link com.cheetah.racer.model.RacerMessage#getPayload()} always
 * returns a proper JSON string (or plain string) on the consumer side, which is
 * required
 * for POJO deserialization in
 * {@link com.cheetah.racer.listener.RacerListenerRegistrar} and for
 * {@code dedupKey} field-extraction in
 * {@link com.cheetah.racer.listener.RacerListenerRegistrar#resolveEffectiveDedupId}.
 */
public final class MessageEnvelopeBuilder {

    private MessageEnvelopeBuilder() {
    }

    // ── StringWriter Pool ─────────────────────────────────────────────────────

    /**
     * Abstraction for supplying a ready-to-use {@link StringWriter} to envelope
     * serialization.
     *
     * <p>
     * Decouples the pool implementation from the {@code build*()} methods so the
     * strategy
     * can be replaced (e.g. for testing or alternative pool implementations)
     * without touching
     * every call site.
     */
    @FunctionalInterface
    interface StringWriterProvider {
        /**
         * Returns a {@link StringWriter} whose internal buffer has been reset and is
         * ready for a fresh serialization pass.
         *
         * @return a cleared, reusable {@link StringWriter}
         */
        StringWriter get();
    }

    /**
     * Per-thread {@link StringWriter} pool.
     *
     * <p>
     * Reuses the same {@link StringWriter} instance per thread, clearing its
     * backing
     * {@link StringBuffer} before each use via {@code setLength(0)}. This avoids
     * the
     * {@code new StringWriter()} allocation that
     * {@link ObjectWriter#writeValueAsString}
     * performs internally on every call, reducing GC pressure at high publish
     * rates.
     */
    static StringWriterProvider WRITER_POOL;

    static {
        ThreadLocal<StringWriter> pool = ThreadLocal.withInitial(StringWriter::new);
        WRITER_POOL = () -> {
            StringWriter sw = pool.get();
            // Reset buffer length to 0 — reuses the backing char[] without freeing it
            sw.getBuffer().setLength(0);
            return sw;
        };
    }

    // ── ID generation ─────────────────────────────────────────────────────────

    /**
     * ThreadLocalRandom-backed UUID generator.
     *
     * <p>
     * Uses a per-thread {@link Random} instance to avoid the global
     * {@code SecureRandom} lock that makes {@link UUID#randomUUID()} a contention
     * point at 50K+ msg/sec. Produces standard UUID-format strings for full
     * wire-format compatibility with existing consumers and dedup keys.
     */
    private static final class FastUuid {
        /** Per-thread {@link Random} avoids cross-thread lock contention. */
        private static final ThreadLocal<Random> RANDOM = ThreadLocal.withInitial(Random::new);

        /**
         * Generates a UUID-format string using ThreadLocalRandom.
         *
         * @return a UUID-format string (~5-10x faster than {@link UUID#randomUUID()})
         */
        static String generate() {
            Random r = RANDOM.get();
            long mostSig = r.nextLong();
            long leastSig = r.nextLong();
            return new UUID(mostSig, leastSig).toString();
        }
    }

    /**
     * Active ID generation strategy. Defaults to {@link FastUuid} (Option A).
     * Swapped at startup by {@link com.cheetah.racer.config.RacerAutoConfiguration}
     * via {@link #setIdGenerator(IdGenerator)}.
     */
    private static volatile IdGenerator ID_GEN = FastUuid::generate;

    /**
     * Replaces the active {@link IdGenerator} strategy.
     *
     * <p>
     * Called once at application startup by the auto-configuration bean.
     * All subsequent message publishes will use the new generator.
     *
     * @param generator the non-null generator to use from this point forward
     */
    public static void setIdGenerator(IdGenerator generator) {
        ID_GEN = generator;
    }

    /**
     * Returns the built-in {@link FastUuid} {@link IdGenerator}
     * (ThreadLocalRandom-backed).
     *
     * <p>
     * Exposed so that {@link com.cheetah.racer.config.RacerAutoConfiguration} can
     * register it as a named Spring bean without needing access to the private
     * inner class.
     *
     * @return the default fast UUID generator
     */
    public static IdGenerator fastUuidGenerator() {
        return FastUuid::generate;
    }

    /**
     * Generates a new message ID using the currently-configured
     * {@link IdGenerator}.
     *
     * <p>
     * Exposed so that {@link com.cheetah.racer.model.RacerMessage} and
     * {@link com.cheetah.racer.model.RacerRequest} can reuse the same
     * ThreadLocalRandom-backed generator instead of falling back to
     * {@code UUID.randomUUID()} (which uses a globally-shared {@code SecureRandom}
     * and suffers lock contention above ~50K msg/sec).
     *
     * @return a new unique ID string
     */
    public static String generateId() {
        return ID_GEN.generate();
    }

    // ── Cached Timestamp ──────────────────────────────────────────────────────

    /**
     * Millisecond-granularity clock cache that avoids the per-call
     * {@link Instant#toString()} overhead at high publish rates.
     *
     * <p>
     * A single daemon thread updates the cached ISO-8601 string once per
     * millisecond. At 100K msg/sec that means ~99% of calls hit the cache
     * and only one {@link Instant#toString()} call is made per millisecond,
     * eliminating the {@code StringBuilder} + {@code DateTimeFormatter} allocation
     * overhead that would otherwise account for ~5% of envelope build time.
     *
     * <p>
     * <b>Trade-off:</b> timestamp granularity is reduced to 1 ms (from
     * nanoseconds),
     * which is sufficient for all practical Racer use-cases.
     */
    private static final class CachedClock {

        /** Latest cached epoch-millis value. Updated by the background thread. */
        private static volatile long CACHED_MILLIS = System.currentTimeMillis();

        /**
         * Latest cached ISO-8601 timestamp string. Updated by the background thread.
         */
        private static volatile String CACHED_TIMESTAMP = formatInstant(CACHED_MILLIS);

        static {
            // Single daemon thread — does not prevent JVM shutdown.
            // Named thread simplifies profiler / thread-dump diagnostics.
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "racer-clock-cache");
                t.setDaemon(true);
                return t;
            });
            // Update every 10 ms — sufficient precision for message timestamps while
            // keeping the timer-thread overhead negligible (#13).
            scheduler.scheduleAtFixedRate(() -> {
                CACHED_MILLIS = System.currentTimeMillis();
                CACHED_TIMESTAMP = formatInstant(CACHED_MILLIS);
            }, 0, 10, TimeUnit.MILLISECONDS);
        }

        /**
         * Returns the current ISO-8601 timestamp string at millisecond granularity.
         * Safe for concurrent access (field is {@code volatile}).
         *
         * @return cached ISO-8601 timestamp updated every millisecond
         */
        static String now() {
            return CACHED_TIMESTAMP;
        }

        /**
         * Converts epoch-millis to an ISO-8601 string.
         * Called only by the background scheduler thread (once per millisecond).
         *
         * @param millis epoch time in milliseconds
         * @return ISO-8601 formatted string
         */
        private static String formatInstant(long millis) {
            return Instant.ofEpochMilli(millis).toString();
        }
    }

    // ── Cached ObjectWriter ───────────────────────────────────────────────────

    /**
     * Cached {@link ObjectWriter} for {@link RacerEnvelope}.
     *
     * <p>
     * Lazily initialized on the first {@code build*()} call and reused for all
     * subsequent calls. Eliminates per-message writer lookup overhead; Jackson can
     * resolve all field serializers at initialization time because field types are
     * known statically from the POJO, unlike a {@code Map<String,Object>}.
     */
    private static volatile ObjectWriter ENVELOPE_WRITER;

    /**
     * Returns the cached {@link ObjectWriter}, initializing it from the given
     * {@code objectMapper} on the first call.
     *
     * <p>
     * Uses double-checked locking; safe because {@link ObjectWriter} is immutable
     * and {@code objectMapper} is the same Spring-managed singleton for every call.
     *
     * @param objectMapper mapper to build the writer from (used only on first call)
     * @return the shared, thread-safe {@link ObjectWriter}
     */
    private static ObjectWriter getWriter(ObjectMapper objectMapper) {
        ObjectWriter w = ENVELOPE_WRITER;
        if (w == null) {
            synchronized (MessageEnvelopeBuilder.class) {
                w = ENVELOPE_WRITER;
                if (w == null) {
                    ENVELOPE_WRITER = w = objectMapper.writerFor(RacerEnvelope.class);
                }
            }
        }
        return w;
    }

    // ── Internal helper ──────────────────────────────────────────────────────

    /**
     * Serializes the payload value to a JSON string so the envelope
     * {@code "payload"} field
     * is always a JSON string, never a nested JSON object.
     *
     * <p>
     * String payloads (including pre-serialized JSON strings) are used verbatim.
     * All other types are serialized via {@code objectMapper}.
     */
    private static String serializePayload(ObjectMapper objectMapper, Object payload) throws Exception {
        if (payload == null)
            return "null";
        if (payload instanceof String)
            return (String) payload;
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * Builds a standard pub/sub envelope:
     * 
     * <pre>
     * {"id": "...", "channel": "...", "sender": "...", "timestamp": "...", "payload": ...}
     * </pre>
     */
    public static Mono<String> build(ObjectMapper objectMapper,
            String channel, String sender, Object payload) {
        return build(objectMapper, channel, sender, payload, false);
    }

    /**
     * Builds a standard pub/sub envelope with an optional routed flag.
     * When {@code routed} is {@code true}, the envelope includes
     * {@code "routed": true}
     * so that downstream listeners skip routing evaluation (prevents infinite
     * loops).
     */
    public static Mono<String> build(ObjectMapper objectMapper,
            String channel, String sender, Object payload,
            boolean routed) {
        return build(objectMapper, channel, sender, payload, routed, null);
    }

    /**
     * Builds a standard pub/sub envelope with an optional routed flag and an
     * explicit
     * message ID for deduplication purposes.
     *
     * <p>
     * When {@code messageId} is non-null, it is used as the envelope {@code id}
     * field
     * directly, allowing callers to pass a stable business key so that
     * {@link com.cheetah.racer.dedup.RacerDedupService} can suppress
     * retransmissions of
     * the same logical event. When {@code null}, a random UUID is generated.
     */
    public static Mono<String> build(ObjectMapper objectMapper,
            String channel, String sender, Object payload,
            boolean routed, @Nullable String messageId) {
        return Mono.fromCallable(() -> {
            RacerEnvelope envelope = new RacerEnvelope(
                    messageId != null ? messageId : ID_GEN.generate(),
                    channel,
                    sender,
                    CachedClock.now(),
                    serializePayload(objectMapper, payload),
                    routed ? Boolean.TRUE : null, // null omits the field via @JsonInclude(NON_NULL)
                    null,
                    null);
            // Use the pooled StringWriter to avoid per-call StringWriter allocation
            StringWriter sw = WRITER_POOL.get();
            getWriter(objectMapper).writeValue(sw, envelope);
            return sw.toString();
        });
    }

    /**
     * Builds a priority envelope:
     * 
     * <pre>
     * {"id": "...", "channel": "...", "sender": "...", "timestamp": "...", "payload": "...", "priority": "HIGH"}
     * </pre>
     */
    public static Mono<String> buildWithPriority(ObjectMapper objectMapper,
            String channel, String sender,
            String priority, Object payload) {
        return Mono.fromCallable(() -> {
            RacerEnvelope envelope = new RacerEnvelope(
                    ID_GEN.generate(),
                    channel,
                    sender,
                    CachedClock.now(),
                    serializePayload(objectMapper, payload),
                    null,
                    null,
                    priority);
            // Use the pooled StringWriter to avoid per-call StringWriter allocation
            StringWriter sw = WRITER_POOL.get();
            getWriter(objectMapper).writeValue(sw, envelope);
            return sw.toString();
        });
    }

    /**
     * Builds a durable stream entry envelope:
     * 
     * <pre>
     * {"id": "...", "sender": "...", "timestamp": "...", "payload": ...}
     * </pre>
     */
    public static Mono<String> buildStream(ObjectMapper objectMapper,
            String sender, Object payload) {
        return Mono.fromCallable(() -> {
            // channel is null → omitted via @JsonInclude(NON_NULL) on RacerEnvelope
            RacerEnvelope envelope = new RacerEnvelope(
                    ID_GEN.generate(),
                    null,
                    sender,
                    CachedClock.now(),
                    serializePayload(objectMapper, payload),
                    null,
                    null,
                    null);
            // Use the pooled StringWriter to avoid per-call StringWriter allocation
            StringWriter sw = WRITER_POOL.get();
            getWriter(objectMapper).writeValue(sw, envelope);
            return sw.toString();
        });
    }

    // ── Phase 4.2 — Distributed Tracing ──────────────────────────────────────

    /**
     * Builds a pub/sub envelope that includes the W3C {@code traceparent} header
     * when
     * tracing is enabled.
     *
     * <pre>
     * {"id":"...","channel":"...","sender":"...","timestamp":"...","payload":"...","traceparent":"00-..."}
     * </pre>
     *
     * <p>
     * When {@code traceparent} is {@code null} the output is identical to
     * {@link #build(ObjectMapper, String, String, Object, boolean, String)}.
     *
     * @param objectMapper Jackson mapper used for serialization
     * @param channel      channel / topic name
     * @param sender       originator identifier
     * @param payload      message body
     * @param routed       whether the message was forwarded by the router
     * @param messageId    explicit message ID; {@code null} generates a random UUID
     * @param traceparent  W3C traceparent value; {@code null} omits the field
     */
    public static Mono<String> buildWithTrace(ObjectMapper objectMapper,
            String channel, String sender, Object payload,
            boolean routed, @Nullable String messageId,
            @Nullable String traceparent) {
        return Mono.fromCallable(() -> {
            // Blank traceparent treated the same as null — field omitted from envelope
            String tp = (traceparent != null && !traceparent.isBlank()) ? traceparent : null;
            RacerEnvelope envelope = new RacerEnvelope(
                    messageId != null ? messageId : ID_GEN.generate(),
                    channel,
                    sender,
                    CachedClock.now(),
                    serializePayload(objectMapper, payload),
                    routed ? Boolean.TRUE : null, // null omits the field via @JsonInclude(NON_NULL)
                    tp,
                    null);
            // Use the pooled StringWriter to avoid per-call StringWriter allocation
            StringWriter sw = WRITER_POOL.get();
            getWriter(objectMapper).writeValue(sw, envelope);
            return sw.toString();
        });
    }
}
