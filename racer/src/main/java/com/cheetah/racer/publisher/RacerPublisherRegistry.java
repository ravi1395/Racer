package com.cheetah.racer.publisher;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.lang.Nullable;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.exception.RacerConfigurationException;
import com.cheetah.racer.metrics.NoOpRacerMetrics;
import com.cheetah.racer.metrics.RacerMetricsPort;
import com.cheetah.racer.ratelimit.RacerRateLimiter;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory and registry of {@link RacerChannelPublisher} instances.
 *
 * <p>
 * One publisher is created for every channel alias declared under
 * {@code racer.channels.*}, plus a single publisher for the
 * {@link com.cheetah.racer.config.RacerProperties#getDefaultChannel() default
 * channel}
 * (registered under the internal alias {@value #DEFAULT_ALIAS}).
 *
 * <p>
 * Publishers are constructed once at application startup via {@link #init()}
 * and
 * shared for the lifetime of the application. Each publisher optionally
 * delegates to
 * {@link RacerMetricsPort} for Micrometer instrumentation and to
 * {@link RacerSchemaRegistry} for outbound payload validation; both are no-ops
 * when
 * the corresponding beans are absent from the application context.
 *
 * <h3>Look-up order (used by {@code @RacerPublisher} and
 * {@code @PublishResult})</h3>
 * <ol>
 * <li>If a non-blank alias is provided, return the publisher registered for
 * that alias.</li>
 * <li>If the alias is {@code null}, blank, or unknown, fall back to the default
 * publisher
 * and log a warning in the unknown-alias case.</li>
 * </ol>
 *
 * @see RacerChannelPublisherImpl
 * @see RacerMetricsPort
 * @see RacerSchemaRegistry
 */
@Slf4j
public class RacerPublisherRegistry {

    static final String DEFAULT_ALIAS = "__default__";

    private final RacerProperties properties;
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RacerMetricsPort racerMetrics;
    @Nullable
    private final RacerSchemaRegistry schemaRegistry;
    @Nullable
    private final RacerRateLimiter rateLimiter;

    /** alias → publisher; populated by {@link #init()}. */
    private final Map<String, RacerChannelPublisher> registry = new ConcurrentHashMap<>();

    /**
     * Full constructor used by
     * {@link com.cheetah.racer.config.RacerAutoConfiguration}.
     *
     * @param properties        Racer configuration properties
     * @param redisTemplate     reactive Redis template for publishing
     * @param objectMapper      JSON serializer
     * @param racerMetrics      metrics port; use {@link NoOpRacerMetrics#INSTANCE}
     *                          when absent
     * @param schemaRegistryOpt optional JSON-Schema validator; absent → validation
     *                          skipped
     * @param rateLimiterOpt    optional rate limiter; absent → no rate limiting
     */
    public RacerPublisherRegistry(RacerProperties properties,
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            RacerMetricsPort racerMetrics,
            Optional<RacerSchemaRegistry> schemaRegistryOpt,
            Optional<RacerRateLimiter> rateLimiterOpt) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.racerMetrics = racerMetrics != null ? racerMetrics : NoOpRacerMetrics.INSTANCE;
        this.schemaRegistry = schemaRegistryOpt.orElse(null);
        this.rateLimiter = rateLimiterOpt.orElse(null);
    }

    /**
     * Creates a minimal registry for testing — no metrics, no schema validation,
     * no rate limiting. Passes {@link Optional#empty()} for all optional
     * dependencies.
     *
     * <p>
     * Usage:
     * </p>
     * 
     * <pre>
     * RacerPublisherRegistry registry = RacerPublisherRegistry.forTesting(props, template, mapper);
     * </pre>
     *
     * @param properties    Racer configuration properties
     * @param redisTemplate reactive Redis template
     * @param objectMapper  JSON serializer
     * @return a registry with no-op metrics and no schema validation or rate
     *         limiting
     */
    public static RacerPublisherRegistry forTesting(
            RacerProperties properties,
            ReactiveRedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper) {
        return new RacerPublisherRegistry(properties, redisTemplate, objectMapper,
                NoOpRacerMetrics.INSTANCE, // metrics
                Optional.empty(), // schemaRegistry
                Optional.empty()); // rateLimiter
    }

    /**
     * Builds and registers publishers for all configured channels.
     * Called automatically by Spring after dependency injection is complete.
     * The default channel is always registered; named channel aliases are added
     * for every entry in {@code racer.channels.*} that has a non-blank
     * {@code name}.
     */
    @PostConstruct
    public void init() {
        // Register the default channel
        registry.put(DEFAULT_ALIAS, new RacerChannelPublisherImpl(
                redisTemplate, objectMapper,
                properties.getDefaultChannel(), DEFAULT_ALIAS, "racer",
                false, "", 0L, racerMetrics, schemaRegistry, rateLimiter));
        log.info("[racer] Default channel registered: '{}'", properties.getDefaultChannel());

        // Register each named channel
        properties.getChannels().forEach((alias, channelProps) -> {
            if (channelProps.getName() == null || channelProps.getName().isBlank()) {
                log.warn("[racer] Channel alias '{}' has no 'name' configured — skipping.", alias);
                return;
            }
            if (channelProps.isDurable()) {
                String actualStreamKey = channelProps.getStreamKey().isBlank()
                        ? channelProps.getName() + ":stream"
                        : channelProps.getStreamKey();
                registry.put(alias, new RacerChannelPublisherImpl(
                        redisTemplate, objectMapper,
                        channelProps.getName(), alias, channelProps.getSender(),
                        true, actualStreamKey,
                        properties.getRetention().getStreamMaxLen(),
                        racerMetrics, schemaRegistry, rateLimiter));
                log.info("[racer] Channel '{}' registered → stream '{}' (durable)", alias, actualStreamKey);
            } else {
                registry.put(alias, new RacerChannelPublisherImpl(
                        redisTemplate, objectMapper,
                        channelProps.getName(), alias, channelProps.getSender(),
                        false, "", 0L, racerMetrics, schemaRegistry, rateLimiter));
                log.info("[racer] Channel '{}' registered → '{}'", alias, channelProps.getName());
            }
        });
    }

    /**
     * Returns the publisher for the given alias.
     *
     * <p>
     * When {@code racer.strict-channel-validation=true} and the alias is non-blank
     * but
     * not registered, a {@link RacerConfigurationException} is thrown immediately
     * so the
     * misconfiguration is visible at startup rather than silently publishing to the
     * wrong
     * channel in production.
     *
     * <p>
     * When strict mode is {@code false} (default) an unknown alias falls back to
     * the
     * default channel with a WARN log — preserving backward-compatible behaviour.
     *
     * @param alias the channel alias from {@code @RacerPublisher},
     *              {@code @PublishResult},
     *              or any other caller; {@code null} / blank uses the default
     *              channel
     * @throws RacerConfigurationException when strict mode is on and the alias is
     *                                     unknown
     */
    public RacerChannelPublisher getPublisher(String alias) {
        // Blank / null alias → always use the default channel
        if (alias == null || alias.isBlank()) {
            return registry.get(DEFAULT_ALIAS);
        }

        RacerChannelPublisher publisher = registry.get(alias);
        if (publisher != null) {
            return publisher;
        }

        // Unknown alias — strict mode throws; lenient mode falls back and warns
        if (properties.isStrictChannelValidation()) {
            throw new RacerConfigurationException(
                    "[racer] Unknown channel alias '" + alias + "'. "
                            + "Defined aliases: " + properties.getChannels().keySet() + ". "
                            + "Fix the alias or set racer.strict-channel-validation=false to fall back to the default channel.");
        }

        log.warn("[racer] Unknown channel alias '{}' — falling back to default channel. "
                + "Set racer.strict-channel-validation=true to turn this into a startup error.", alias);
        return registry.get(DEFAULT_ALIAS);
    }

    /**
     * Returns an unmodifiable snapshot of all registered alias-to-publisher
     * mappings,
     * including the internal {@value #DEFAULT_ALIAS} entry.
     */
    public Map<String, RacerChannelPublisher> getAll() {
        return Map.copyOf(registry);
    }
}
