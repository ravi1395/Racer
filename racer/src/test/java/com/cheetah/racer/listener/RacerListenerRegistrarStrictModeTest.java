package com.cheetah.racer.listener;

import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.annotation.RacerStreamListener;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.exception.RacerConfigurationException;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * Unit tests for strict channel-alias validation in
 * {@link RacerListenerRegistrar} and {@link RacerStreamListenerRegistrar}.
 *
 * <p>Covers v1.4.0 item 1.1: when {@code racer.strict-channel-validation=true},
 * a {@code @RacerListener(channelRef="unknown")} or
 * {@code @RacerStreamListener(streamKeyRef="unknown")} method must cause a
 * {@link RacerConfigurationException} at startup, not silently fall through.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerListenerRegistrarStrictModeTest {

    @Mock private ReactiveRedisMessageListenerContainer listenerContainer;
    @Mock private ReactiveRedisTemplate<String, String> redisTemplate;
    @Mock private ReactiveStreamOperations<String, Object, Object> streamOps;

    private ObjectMapper objectMapper;
    private MockEnvironment environment;

    // ── Test beans ────────────────────────────────────────────────────────────

    /** Bean with a @RacerListener using an unknown channelRef. */
    static class BadChannelRefBean {
        @RacerListener(channelRef = "no-such-alias")
        public void onMessage(String payload) {}
    }

    /** Bean with a @RacerListener using a valid channelRef. */
    static class GoodChannelRefBean {
        @RacerListener(channelRef = "orders")
        public void onMessage(String payload) {}
    }

    /** Bean with a @RacerListener using a direct channel name (not a ref). */
    static class DirectChannelBean {
        @RacerListener(channel = "racer:direct")
        public void onMessage(String payload) {}
    }

    /** Bean with a @RacerStreamListener using an unknown streamKeyRef. */
    static class BadStreamRefBean {
        @RacerStreamListener(streamKeyRef = "no-such-alias", group = "test-group")
        public void onRecord(String payload) {}
    }

    /** Bean with a @RacerStreamListener using a valid streamKeyRef. */
    static class GoodStreamRefBean {
        @RacerStreamListener(streamKeyRef = "orders", group = "orders-group")
        public void onRecord(String payload) {}
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        environment  = new MockEnvironment();

        // Stub Redis interactions so that post-validation registration paths do not NPE.
        // The "doesNotThrow" tests only assert that no RacerConfigurationException is raised;
        // we don't care whether the actual subscription succeeds in the mock context.
        doReturn(Flux.never()).when(listenerContainer).receive(any(ChannelTopic.class));
        doReturn(streamOps).when(redisTemplate).opsForStream();
        doReturn(Mono.just("OK")).when(streamOps).createGroup(any(), any(ReadOffset.class), anyString());
    }

    // ── Helper builders ───────────────────────────────────────────────────────

    private RacerProperties buildProps(boolean strict) {
        RacerProperties props = new RacerProperties();
        props.setDefaultChannel("racer:messages");
        props.setStrictChannelValidation(strict);

        RacerProperties.ChannelProperties orders = new RacerProperties.ChannelProperties();
        orders.setName("racer:orders");
        props.setChannels(Map.of("orders", orders));
        return props;
    }

    private RacerListenerRegistrar buildListenerRegistrar(boolean strict) {
        RacerProperties props = buildProps(strict);
        RacerListenerRegistrar registrar = new RacerListenerRegistrar(
                listenerContainer, objectMapper, props, null, null, null, null);
        registrar.setEnvironment(environment);
        return registrar;
    }

    private RacerStreamListenerRegistrar buildStreamRegistrar(boolean strict) {
        RacerProperties props = buildProps(strict);
        RacerStreamListenerRegistrar registrar = new RacerStreamListenerRegistrar(
                redisTemplate, objectMapper, props, null, null, null);
        registrar.setEnvironment(environment);
        return registrar;
    }

    // ── RacerListenerRegistrar strict mode ───────────────────────────────────

    @Test
    void strictMode_unknownChannelRef_throwsRacerConfigurationException() {
        RacerListenerRegistrar registrar = buildListenerRegistrar(true);

        assertThatThrownBy(() ->
                registrar.postProcessAfterInitialization(new BadChannelRefBean(), "badBean"))
                .isInstanceOf(RacerConfigurationException.class)
                .hasMessageContaining("no-such-alias")
                .hasMessageContaining("racer.channels");
    }

    @Test
    void strictMode_errorMessageListsValidAliases() {
        RacerListenerRegistrar registrar = buildListenerRegistrar(true);

        assertThatThrownBy(() ->
                registrar.postProcessAfterInitialization(new BadChannelRefBean(), "badBean"))
                .isInstanceOf(RacerConfigurationException.class)
                .hasMessageContaining("orders"); // the only valid alias
    }

    @Test
    void strictMode_knownChannelRef_doesNotThrow() {
        // "orders" alias exists — strict mode must not block it
        // We don't care about the Pub/Sub subscription succeeding here (mock container),
        // only that no RacerConfigurationException is thrown during registration.
        RacerListenerRegistrar registrar = buildListenerRegistrar(true);

        assertThatCode(() ->
                registrar.postProcessAfterInitialization(new GoodChannelRefBean(), "goodBean"))
                .doesNotThrowAnyException();
    }

    @Test
    void strictMode_directChannelName_doesNotThrow() {
        // A direct channel="racer:direct" is not an alias lookup — strict mode ignores it
        RacerListenerRegistrar registrar = buildListenerRegistrar(true);

        assertThatCode(() ->
                registrar.postProcessAfterInitialization(new DirectChannelBean(), "directBean"))
                .doesNotThrowAnyException();
    }

    @Test
    void lenientMode_unknownChannelRef_doesNotThrow() {
        // Default behaviour: missing alias logs a warning and falls back to default channel
        RacerListenerRegistrar registrar = buildListenerRegistrar(false);

        assertThatCode(() ->
                registrar.postProcessAfterInitialization(new BadChannelRefBean(), "badBean"))
                .doesNotThrowAnyException();
    }

    // ── RacerStreamListenerRegistrar strict mode ─────────────────────────────

    @Test
    void strictMode_unknownStreamKeyRef_throwsRacerConfigurationException() {
        RacerStreamListenerRegistrar registrar = buildStreamRegistrar(true);

        assertThatThrownBy(() ->
                registrar.postProcessAfterInitialization(new BadStreamRefBean(), "badStreamBean"))
                .isInstanceOf(RacerConfigurationException.class)
                .hasMessageContaining("no-such-alias");
    }

    @Test
    void strictMode_knownStreamKeyRef_doesNotThrow() {
        // "orders" alias resolves — only RacerConfigurationException would indicate a bug
        RacerStreamListenerRegistrar registrar = buildStreamRegistrar(true);

        assertThatCode(() ->
                registrar.postProcessAfterInitialization(new GoodStreamRefBean(), "goodStreamBean"))
                .doesNotThrowAnyException();
    }

    @Test
    void lenientMode_unknownStreamKeyRef_doesNotThrow() {
        RacerStreamListenerRegistrar registrar = buildStreamRegistrar(false);

        assertThatCode(() ->
                registrar.postProcessAfterInitialization(new BadStreamRefBean(), "badStreamBean"))
                .doesNotThrowAnyException();
    }
}
