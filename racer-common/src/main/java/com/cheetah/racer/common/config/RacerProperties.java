package com.cheetah.racer.common.config;

import com.cheetah.racer.common.RedisChannels;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Racer framework properties.
 *
 * <pre>
 * # Default channel used when no alias is specified
 * racer.default-channel=racer:messages
 *
 * # Named channel definitions
 * racer.channels.orders.name=racer:orders
 * racer.channels.orders.async=true
 * racer.channels.orders.sender=order-service
 *
 * racer.channels.notifications.name=racer:notifications
 * racer.channels.notifications.async=false
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "racer")
public class RacerProperties {

    /**
     * Default Redis channel used when {@code @RacerPublisher} has no alias
     * and {@code @PublishResult} has neither {@code channel} nor {@code channelRef}.
     */
    private String defaultChannel = RedisChannels.MESSAGE_CHANNEL;

    /**
     * Named channel definitions keyed by alias.
     */
    private Map<String, ChannelProperties> channels = new LinkedHashMap<>();

    @Data
    public static class ChannelProperties {

        /** Redis channel name (key), e.g. {@code racer:orders}. */
        private String name;

        /** Default async flag for publish operations on this channel. */
        private boolean async = true;

        /** Default sender identifier for messages on this channel. */
        private String sender = "racer";
    }

    /**
     * Retention settings for durable streams and DLQ pruning.
     * Mapped under {@code racer.retention.*}.
     */
    @Data
    public static class RetentionProperties {

        /**
         * Maximum number of entries to keep per stream (XTRIM MAXLEN ~).
         * Defaults to 10 000.
         */
        private long streamMaxLen = 10_000;

        /**
         * Age in hours after which DLQ entries are pruned.
         * Defaults to 72 h (3 days).
         */
        private long dlqMaxAgeHours = 72;

        /**
         * Cron expression controlling when the pruning job runs.
         * Defaults to the top of every hour.
         */
        private String scheduleCron = "0 0 * * * *";
    }

    /** Retention / pruning configuration. */
    private RetentionProperties retention = new RetentionProperties();
}
