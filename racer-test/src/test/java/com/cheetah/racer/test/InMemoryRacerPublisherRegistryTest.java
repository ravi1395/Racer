package com.cheetah.racer.test;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.exception.RacerConfigurationException;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link InMemoryRacerPublisherRegistry}.
 *
 * <p>Verifies that {@link InMemoryRacerPublisherRegistry#init()} populates publishers
 * from {@link RacerProperties}, that the publisher look-up logic mirrors the strict / non-strict
 * modes of the production registry, and that {@link InMemoryRacerPublisherRegistry#resetAll()}
 * clears all captured messages.
 *
 * <p>No Redis or Spring context is needed — the registry operates entirely in-memory.
 */
class InMemoryRacerPublisherRegistryTest {

    private static final String DEFAULT_CHANNEL       = "racer:messages";
    private static final String ORDERS_CHANNEL        = "racer:orders";
    private static final String ORDERS_ALIAS          = "orders";
    private static final String NOTIFICATIONS_CHANNEL = "racer:notifications";
    private static final String NOTIFICATIONS_ALIAS   = "notifications";

    private ObjectMapper objectMapper;
    /** Registry built from non-strict properties with two named channels. */
    private InMemoryRacerPublisherRegistry registry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        registry     = buildRegistry(false);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a registry backed by two named channels (orders, notifications) and
     * the configured strict-mode flag.
     */
    private InMemoryRacerPublisherRegistry buildRegistry(boolean strictMode) {
        RacerProperties props = new RacerProperties();
        props.setDefaultChannel(DEFAULT_CHANNEL);
        props.setStrictChannelValidation(strictMode);

        Map<String, RacerProperties.ChannelProperties> channels = new LinkedHashMap<>();
        channels.put(ORDERS_ALIAS, channelProps(ORDERS_CHANNEL));
        channels.put(NOTIFICATIONS_ALIAS, channelProps(NOTIFICATIONS_CHANNEL));
        props.setChannels(channels);

        InMemoryRacerPublisherRegistry reg =
                new InMemoryRacerPublisherRegistry(props, objectMapper);
        reg.init();
        return reg;
    }

    private RacerProperties.ChannelProperties channelProps(String name) {
        RacerProperties.ChannelProperties cp = new RacerProperties.ChannelProperties();
        cp.setName(name);
        return cp;
    }

    // ── init() ────────────────────────────────────────────────────────────────

    @Test
    void init_registersDefaultChannelPublisher() {
        InMemoryRacerPublisher defaultPub = registry.getTestPublisher("__default__");

        assertThat(defaultPub).isNotNull();
        assertThat(defaultPub.getChannelName()).isEqualTo(DEFAULT_CHANNEL);
        assertThat(defaultPub.getChannelAlias()).isEqualTo("__default__");
    }

    @Test
    void init_registersNamedChannelPublisherForOrders() {
        InMemoryRacerPublisher pub = registry.getTestPublisher(ORDERS_ALIAS);

        assertThat(pub).isNotNull();
        assertThat(pub.getChannelName()).isEqualTo(ORDERS_CHANNEL);
        assertThat(pub.getChannelAlias()).isEqualTo(ORDERS_ALIAS);
    }

    @Test
    void init_registersNamedChannelPublisherForNotifications() {
        InMemoryRacerPublisher pub = registry.getTestPublisher(NOTIFICATIONS_ALIAS);

        assertThat(pub).isNotNull();
        assertThat(pub.getChannelName()).isEqualTo(NOTIFICATIONS_CHANNEL);
    }

    @Test
    void init_publisherCountIsDefaultPlusNamedChannels() {
        // 1 default + 2 named = 3
        assertThat(registry.publisherCount()).isEqualTo(3);
    }

    @Test
    void init_skipsChannelWithNullName() {
        RacerProperties props = new RacerProperties();
        props.setDefaultChannel(DEFAULT_CHANNEL);
        RacerProperties.ChannelProperties bad = new RacerProperties.ChannelProperties();
        bad.setName(null);
        props.setChannels(Map.of("bad-alias", bad));

        InMemoryRacerPublisherRegistry reg = new InMemoryRacerPublisherRegistry(props, objectMapper);
        reg.init();

        assertThat(reg.getTestPublisher("bad-alias")).isNull();
        assertThat(reg.publisherCount()).isEqualTo(1); // only default
    }

    @Test
    void init_skipsChannelWithBlankName() {
        RacerProperties props = new RacerProperties();
        props.setDefaultChannel(DEFAULT_CHANNEL);
        RacerProperties.ChannelProperties blank = new RacerProperties.ChannelProperties();
        blank.setName("   ");
        props.setChannels(Map.of("blank-alias", blank));

        InMemoryRacerPublisherRegistry reg = new InMemoryRacerPublisherRegistry(props, objectMapper);
        reg.init();

        assertThat(reg.getTestPublisher("blank-alias")).isNull();
    }

    // ── getPublisher() look-up ────────────────────────────────────────────────

    @Test
    void getPublisher_withNullAlias_returnsDefaultPublisher() {
        RacerChannelPublisher pub = registry.getPublisher(null);

        assertThat(pub).isNotNull();
        assertThat(((InMemoryRacerPublisher) pub).getChannelName()).isEqualTo(DEFAULT_CHANNEL);
    }

    @Test
    void getPublisher_withBlankAlias_returnsDefaultPublisher() {
        RacerChannelPublisher pub = registry.getPublisher("   ");

        assertThat(pub).isNotNull();
        assertThat(((InMemoryRacerPublisher) pub).getChannelName()).isEqualTo(DEFAULT_CHANNEL);
    }

    @Test
    void getPublisher_withKnownAlias_returnsNamedPublisher() {
        RacerChannelPublisher pub = registry.getPublisher(ORDERS_ALIAS);

        assertThat(((InMemoryRacerPublisher) pub).getChannelName()).isEqualTo(ORDERS_CHANNEL);
    }

    @Test
    void getPublisher_withUnknownAlias_nonStrictMode_fallsBackToDefault() {
        RacerChannelPublisher pub = registry.getPublisher("nonexistent");

        assertThat(((InMemoryRacerPublisher) pub).getChannelName()).isEqualTo(DEFAULT_CHANNEL);
    }

    @Test
    void getPublisher_withUnknownAlias_strictMode_throwsRacerConfigurationException() {
        InMemoryRacerPublisherRegistry strictRegistry = buildRegistry(true);

        assertThatThrownBy(() -> strictRegistry.getPublisher("nonexistent"))
                .isInstanceOf(RacerConfigurationException.class)
                .hasMessageContaining("nonexistent");
    }

    // ── getAll() ──────────────────────────────────────────────────────────────

    @Test
    void getAll_containsAllRegisteredPublishers() {
        Map<String, RacerChannelPublisher> all = registry.getAll();

        assertThat(all).containsKeys("__default__", ORDERS_ALIAS, NOTIFICATIONS_ALIAS);
        assertThat(all).hasSize(3);
    }

    @Test
    void getAll_returnsImmutableSnapshot() {
        Map<String, RacerChannelPublisher> all = registry.getAll();

        assertThatThrownBy(() -> all.put("new-key", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── resetAll() ────────────────────────────────────────────────────────────

    @Test
    void resetAll_clearsMessagesInAllPublishers() {
        registry.getTestPublisher("__default__").publishSync("default-msg");
        registry.getTestPublisher(ORDERS_ALIAS).publishSync("orders-msg");
        registry.getTestPublisher(NOTIFICATIONS_ALIAS).publishSync("notif-msg");

        registry.resetAll();

        assertThat(registry.getTestPublisher("__default__").getMessages()).isEmpty();
        assertThat(registry.getTestPublisher(ORDERS_ALIAS).getMessages()).isEmpty();
        assertThat(registry.getTestPublisher(NOTIFICATIONS_ALIAS).getMessages()).isEmpty();
    }

    // ── registerChannel() ─────────────────────────────────────────────────────

    @Test
    void registerChannel_addsNewPublisherWithAliasAsChannelName() {
        registry.registerChannel("payments");

        InMemoryRacerPublisher pub = registry.getTestPublisher("payments");
        assertThat(pub).isNotNull();
        // alias is used as both alias and channel name when registered ad-hoc
        assertThat(pub.getChannelName()).isEqualTo("payments");
        assertThat(pub.getChannelAlias()).isEqualTo("payments");
        assertThat(registry.publisherCount()).isEqualTo(4); // 3 existing + payments
    }

    @Test
    void registerChannel_isIdempotent_doesNotDuplicateExistingAlias() {
        registry.registerChannel(ORDERS_ALIAS); // already registered by init()

        assertThat(registry.publisherCount()).isEqualTo(3); // unchanged
    }

    @Test
    void registerChannel_withNullAlias_isIgnored() {
        registry.registerChannel(null);

        assertThat(registry.publisherCount()).isEqualTo(3);
    }

    @Test
    void registerChannel_withBlankAlias_isIgnored() {
        registry.registerChannel("   ");

        assertThat(registry.publisherCount()).isEqualTo(3);
    }

    // ── getTestPublisher() ────────────────────────────────────────────────────

    @Test
    void getTestPublisher_returnsNullForUnregisteredAlias() {
        assertThat(registry.getTestPublisher("no-such-alias")).isNull();
    }
}
