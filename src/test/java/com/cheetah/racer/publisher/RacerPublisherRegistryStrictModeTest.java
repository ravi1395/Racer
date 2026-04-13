package com.cheetah.racer.publisher;

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.exception.RacerConfigurationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for strict channel alias validation in {@link RacerPublisherRegistry}.
 *
 * <p>Covers the behaviour introduced in v1.4.0 item 1.1:
 * when {@code racer.strict-channel-validation=true} an unknown alias throws
 * {@link RacerConfigurationException} instead of silently falling back to the
 * default channel.
 */
@ExtendWith(MockitoExtension.class)
class RacerPublisherRegistryStrictModeTest {

    @Mock
    ReactiveRedisTemplate<String, String> redisTemplate;

    ObjectMapper objectMapper;

    /** Helper — builds a registry with the given strict flag and a single 'orders' alias. */
    private RacerPublisherRegistry buildRegistry(boolean strictMode) {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        RacerProperties props = new RacerProperties();
        props.setDefaultChannel("racer:messages");
        props.setStrictChannelValidation(strictMode);

        // Register one valid alias so we can distinguish "known" vs "unknown" lookups
        RacerProperties.ChannelProperties orders = new RacerProperties.ChannelProperties();
        orders.setName("racer:orders");
        props.setChannels(Map.of("orders", orders));

        RacerPublisherRegistry registry = new RacerPublisherRegistry(props, redisTemplate, objectMapper);
        registry.init();
        return registry;
    }

    // ── Strict mode = true ───────────────────────────────────────────────────

    @Test
    void strictMode_unknownAlias_throwsRacerConfigurationException() {
        // Arrange — strict mode on, alias "typo" not in racer.channels.*
        RacerPublisherRegistry registry = buildRegistry(true);

        // Act + Assert — any lookup of an unknown alias must fail fast at call-time
        assertThatThrownBy(() -> registry.getPublisher("typo"))
                .isInstanceOf(RacerConfigurationException.class)
                .hasMessageContaining("typo")
                .hasMessageContaining("strict-channel-validation");
    }

    @Test
    void strictMode_unknownAlias_errorMessageListsDefinedAliases() {
        // The error message should name the valid aliases so the developer can fix
        // the typo without opening application.properties
        RacerPublisherRegistry registry = buildRegistry(true);

        assertThatThrownBy(() -> registry.getPublisher("ordres"))
                .isInstanceOf(RacerConfigurationException.class)
                .hasMessageContaining("orders"); // the *valid* alias that was probably meant
    }

    @Test
    void strictMode_knownAlias_returnsPublisherWithoutThrowing() {
        RacerPublisherRegistry registry = buildRegistry(true);

        // "orders" is in racer.channels.* — must not throw
        RacerChannelPublisher pub = registry.getPublisher("orders");
        assertThat(pub).isNotNull();
        assertThat(pub.getChannelName()).isEqualTo("racer:orders");
    }

    @Test
    void strictMode_nullAlias_returnsDefaultChannelWithoutThrowing() {
        // Null / blank aliases always map to the default channel; strict mode does not affect them
        RacerPublisherRegistry registry = buildRegistry(true);

        RacerChannelPublisher pub = registry.getPublisher(null);
        assertThat(pub).isNotNull();
        assertThat(pub.getChannelName()).isEqualTo("racer:messages");
    }

    @Test
    void strictMode_blankAlias_returnsDefaultChannelWithoutThrowing() {
        RacerPublisherRegistry registry = buildRegistry(true);

        RacerChannelPublisher pub = registry.getPublisher("   ");
        assertThat(pub.getChannelName()).isEqualTo("racer:messages");
    }

    // ── Strict mode = false (default — backward-compatible fallback) ─────────

    @Test
    void lenientMode_unknownAlias_fallsBackToDefaultChannel() {
        // Default behaviour: unknown alias logs a warning and returns the default publisher
        RacerPublisherRegistry registry = buildRegistry(false);

        RacerChannelPublisher pub = registry.getPublisher("does-not-exist");
        assertThat(pub).isNotNull();
        assertThat(pub.getChannelName()).isEqualTo("racer:messages"); // default
    }

    @Test
    void lenientMode_unknownAlias_doesNotThrow() {
        RacerPublisherRegistry registry = buildRegistry(false);

        // Must not throw — lenient mode is the backward-compatible path
        assertThat(registry.getPublisher("completely-unknown")).isNotNull();
    }
}
