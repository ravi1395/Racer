package com.cheetah.racer.test;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.exception.RacerConfigurationException;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only extension of {@link RacerPublisherRegistry} that replaces all Redis-backed
 * channel publishers with {@link InMemoryRacerPublisher} instances.
 *
 * <p>Registered as a {@code @Primary} bean by {@link RacerTestAutoConfiguration}.  Any
 * bean that injects {@link RacerPublisherRegistry} (including
 * {@link com.cheetah.racer.aspect.PublishResultAspect} and
 * {@link com.cheetah.racer.processor.RacerPublisherFieldProcessor}) will receive this
 * in-memory variant and therefore capture published messages rather than sending them
 * to Redis.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Spring creates this bean and calls {@link #init()} (overriding the parent's
 *       {@code @PostConstruct}).</li>
 *   <li>{@link #init()} builds one {@link InMemoryRacerPublisher} per configured channel
 *       alias without touching Redis.</li>
 *   <li>Tests call {@link #getTestPublisher(String)} to retrieve the publisher for a
 *       specific alias and run assertions on it.</li>
 *   <li>Tests call {@link #resetAll()} in {@code @BeforeEach} to clear captured messages
 *       between test cases.</li>
 * </ol>
 *
 * <p>The null Redis template passed to the super constructor is intentional — the parent
 * class's {@code init()} is overridden and never invoked, so the template is never
 * dereferenced.
 *
 * @see InMemoryRacerPublisher
 * @see RacerTestAutoConfiguration
 */
@Slf4j
public class InMemoryRacerPublisherRegistry extends RacerPublisherRegistry {

    /** Internal alias for the default (unnamed) channel — matches the parent's constant. */
    private static final String DEFAULT_ALIAS = "__default__";

    /**
     * Alias → in-memory publisher map; populated by {@link #init()}.
     * Stored independently of the parent's private {@code registry} field because
     * we override {@link #init()} and {@link #getPublisher(String)}.
     */
    private final Map<String, InMemoryRacerPublisher> testPublishers = new ConcurrentHashMap<>();

    /**
     * Cached reference to the Racer configuration properties.
     * The parent class stores properties privately, so we keep our own reference.
     */
    private final RacerProperties racerProperties;

    /**
     * Jackson mapper forwarded to each {@link InMemoryRacerPublisher} for use in
     * payload-equality assertions.
     */
    private final ObjectMapper objectMapper;

    /**
     * Constructs an in-memory publisher registry.
     *
     * <p>{@code null} is passed for the Redis template because {@link #init()} is overridden
     * and never calls {@code super.init()}, so the parent's template field is never accessed.
     *
     * @param properties   Racer configuration properties; used to enumerate channel aliases
     * @param objectMapper Jackson mapper for payload serialization in assertions
     */
    public InMemoryRacerPublisherRegistry(RacerProperties properties, ObjectMapper objectMapper) {
        // Pass null for the Redis template — safe because our init() override never calls super.init()
        // and therefore never dereferences the template.
        super(properties, null, objectMapper);
        this.racerProperties = properties;
        this.objectMapper    = objectMapper;
    }

    /**
     * Builds one {@link InMemoryRacerPublisher} per configured channel alias, plus a
     * publisher for the default channel, without making any Redis connections.
     *
     * <p>This method overrides the parent's {@code @PostConstruct init()}.  Spring calls
     * only the most-derived version of a {@code @PostConstruct} method, so the parent's
     * implementation (which would attempt Redis connections) is never invoked.
     */
    @Override
    public void init() {
        // Register the default channel
        String defaultChannel = racerProperties.getDefaultChannel();
        testPublishers.put(DEFAULT_ALIAS,
                new InMemoryRacerPublisher(defaultChannel, DEFAULT_ALIAS, objectMapper));
        log.info("[RACER-TEST] In-memory publisher registered for default channel: '{}'",
                defaultChannel);

        // Register a publisher for each named alias in racer.channels.*
        racerProperties.getChannels().forEach((alias, channelProps) -> {
            if (channelProps.getName() == null || channelProps.getName().isBlank()) {
                log.warn("[RACER-TEST] Channel alias '{}' has no 'name' configured — skipping.", alias);
                return;
            }
            testPublishers.put(alias,
                    new InMemoryRacerPublisher(channelProps.getName(), alias, objectMapper));
            log.info("[RACER-TEST] In-memory publisher registered: alias='{}' → channel='{}'",
                    alias, channelProps.getName());
        });
    }

    /**
     * Returns the {@link InMemoryRacerPublisher} for {@code alias}.
     *
     * <p>Follows the same resolution logic as the parent class:
     * <ol>
     *   <li>Null / blank alias → returns the default publisher.</li>
     *   <li>Known alias → returns the named in-memory publisher.</li>
     *   <li>Unknown alias + strict mode on → throws
     *       {@link RacerConfigurationException}.</li>
     *   <li>Unknown alias + strict mode off → logs WARN and returns the default
     *       in-memory publisher.</li>
     * </ol>
     *
     * @param alias the channel alias; {@code null} or blank returns the default publisher
     * @throws RacerConfigurationException when strict mode is enabled and the alias is unknown
     */
    @Override
    public RacerChannelPublisher getPublisher(String alias) {
        // Null / blank → always use the default channel
        if (alias == null || alias.isBlank()) {
            return testPublishers.get(DEFAULT_ALIAS);
        }

        InMemoryRacerPublisher pub = testPublishers.get(alias);
        if (pub != null) {
            return pub;
        }

        // Unknown alias — honour strict-mode setting for consistency with production behaviour
        if (racerProperties.isStrictChannelValidation()) {
            throw new RacerConfigurationException(
                    "[RACER-TEST] Unknown channel alias '" + alias + "'. "
                    + "Defined aliases: " + racerProperties.getChannels().keySet() + ". "
                    + "Fix the alias or set racer.strict-channel-validation=false to fall "
                    + "back to the default channel.");
        }

        log.warn("[RACER-TEST] Unknown channel alias '{}' — falling back to default in-memory publisher.",
                alias);
        return testPublishers.get(DEFAULT_ALIAS);
    }

    /**
     * Returns an unmodifiable view of all registered alias → publisher mappings,
     * including the internal {@value #DEFAULT_ALIAS} entry.
     *
     * @return immutable snapshot of the alias-to-publisher map
     */
    @Override
    public Map<String, RacerChannelPublisher> getAll() {
        return Map.copyOf(testPublishers);
    }

    // ── Test-specific API ──────────────────────────────────────────────────────

    /**
     * Returns the {@link InMemoryRacerPublisher} for the given alias so that tests can
     * assert on published messages.
     *
     * <p>Use {@code "__default__"} to access the default-channel publisher.
     *
     * @param alias the channel alias (e.g. {@code "orders"}); or {@code "__default__"}
     *              for the unnamed default channel
     * @return the in-memory publisher, or {@code null} if no publisher is registered for
     *         the alias (which indicates a misconfigured test or alias)
     */
    public InMemoryRacerPublisher getTestPublisher(String alias) {
        return testPublishers.get(alias);
    }

    /**
     * Returns the number of channel publishers currently registered (including the
     * implicit {@code "__default__"} entry).
     *
     * @return total publisher count
     */
    public int publisherCount() {
        return testPublishers.size();
    }

    /**
     * Registers an additional channel alias programmatically, using the alias itself as
     * the channel name.
     *
     * <p>Called by {@link RacerTestExecutionListener} to register channels declared in
     * {@link RacerTest#channels()} before the test method runs.  Registration is
     * idempotent — if an {@link InMemoryRacerPublisher} already exists for {@code alias}
     * (e.g. because it was registered by {@link #init()} from {@code application.properties}),
     * this call is a no-op.
     *
     * @param alias the channel alias to register; the channel name is set equal to the alias
     */
    public void registerChannel(String alias) {
        if (alias == null || alias.isBlank()) {
            log.warn("[RACER-TEST] registerChannel() called with a blank alias — ignoring.");
            return;
        }
        if (testPublishers.containsKey(alias)) {
            log.debug("[RACER-TEST] Channel alias '{}' is already registered — skipping duplicate.",
                    alias);
            return;
        }
        // Use alias as both the Redis channel name and the registry key so that
        // @RacerTest(channels = {"orders"}) registers "orders" → channel "orders".
        testPublishers.put(alias, new InMemoryRacerPublisher(alias, alias, objectMapper));
        log.info("[RACER-TEST] In-memory publisher registered from @RacerTest.channels(): "
                + "alias='{}' → channel='{}'", alias, alias);
    }

    /**
     * Clears all captured messages across every registered in-memory publisher.
     * Call this in {@code @BeforeEach} to ensure each test starts with a clean slate.
     */
    public void resetAll() {
        testPublishers.values().forEach(InMemoryRacerPublisher::reset);
        log.debug("[RACER-TEST] All in-memory publishers reset ({} publisher(s)).",
                testPublishers.size());
    }
}
