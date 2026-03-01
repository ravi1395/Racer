package com.cheetah.racer.common;

/**
 * Constants for Redis channel names and keys used across modules.
 */
public final class RedisChannels {

    private RedisChannels() {}

    /** Primary pub/sub channel for messages */
    public static final String MESSAGE_CHANNEL = "racer:messages";

    /** Channel for broadcasting notifications/events */
    public static final String NOTIFICATION_CHANNEL = "racer:notifications";

    /** Redis key for the Dead Letter Queue (stored as a Redis List) */
    public static final String DEAD_LETTER_QUEUE = "racer:dlq";

    /** Redis key for DLQ processing lock */
    public static final String DLQ_LOCK = "racer:dlq:lock";

    /** Redis key for message processing metrics */
    public static final String METRICS_KEY = "racer:metrics";

    /** Maximum retry attempts before permanently dead-lettering */
    public static final int MAX_RETRY_ATTEMPTS = 3;

    // ── Request-Reply (Pub/Sub) ──────────────────────────────────────────

    /** Prefix for ephemeral reply channels: racer:reply:<correlationId> */
    public static final String REPLY_CHANNEL_PREFIX = "racer:reply:";

    // ── Request-Reply (Streams) ──────────────────────────────────────────

    /** Stream key where the server writes requests */
    public static final String REQUEST_STREAM = "racer:stream:requests";

    /** Prefix for per-correlation response streams: racer:stream:response:<correlationId> */
    public static final String RESPONSE_STREAM_PREFIX = "racer:stream:response:";

    /** Consumer group used by the client to read the request stream */
    public static final String STREAM_CONSUMER_GROUP = "racer-client-group";

    /** Default timeout in seconds for waiting on a reply */
    public static final long REPLY_TIMEOUT_SECONDS = 30;
}
