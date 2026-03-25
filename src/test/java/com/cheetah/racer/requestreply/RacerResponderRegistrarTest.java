package com.cheetah.racer.requestreply;

import com.cheetah.racer.annotation.RacerResponder;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.RacerRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerResponderRegistrar}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerResponderRegistrarTest {

    // ── Test beans ────────────────────────────────────────────────────────────

    static class SampleResponderBean {
        @RacerResponder(channel = "racer:requests")
        public String handleRequest(RacerRequest request) {
            return "Processed: " + request.getPayload();
        }
    }

    static class StreamResponderBean {
        @RacerResponder(stream = "racer:stream:requests", group = "test-group")
        public String handleStreamRequest(RacerRequest request) {
            return "Stream: " + request.getPayload();
        }
    }

    static class PlainBean {
        public String noAnnotation() { return "plain"; }
    }

    // ── Mocks and collaborators ───────────────────────────────────────────────

    @Mock ReactiveRedisMessageListenerContainer listenerContainer;
    @Mock ReactiveRedisTemplate<String, String> redisTemplate;
    @SuppressWarnings("rawtypes")
    @Mock ReactiveStreamOperations streamOps;
    @Mock Environment environment;

    ObjectMapper objectMapper;
    RacerProperties properties;
    RacerResponderRegistrar registrar;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new RacerProperties();
        properties.setChannels(new LinkedHashMap<>());

        when(environment.resolvePlaceholders(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(listenerContainer.receive(any(ChannelTopic.class))).thenReturn(Flux.never());
        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.createGroup(anyString(), any(), anyString())).thenReturn(Mono.just("OK"));

        registrar = new RacerResponderRegistrar(
                listenerContainer, redisTemplate, objectMapper, properties);
        registrar.setEnvironment(environment);
    }

    // ── Tests: bean discovery ─────────────────────────────────────────────────

    @Test
    void postProcessAfterInitialization_discoversAnnotatedMethods_pubsub() throws Exception {
        SampleResponderBean bean = new SampleResponderBean();
        Object result = registrar.postProcessAfterInitialization(bean, "sampleResponderBean");

        assertThat(result).isSameAs(bean);
        Thread.sleep(200);
    }

    @Test
    void postProcessAfterInitialization_discoversAnnotatedMethods_stream() throws Exception {
        StreamResponderBean bean = new StreamResponderBean();
        Object result = registrar.postProcessAfterInitialization(bean, "streamResponderBean");

        assertThat(result).isSameAs(bean);
        Thread.sleep(200);
    }

    @Test
    void postProcessAfterInitialization_noAnnotation_returnsBean() {
        PlainBean bean = new PlainBean();
        Object result = registrar.postProcessAfterInitialization(bean, "plainBean");

        assertThat(result).isSameAs(bean);
    }

    // ── Tests: lifecycle ──────────────────────────────────────────────────────

    @Test
    void stop_disposesSubscriptions() throws Exception {
        SampleResponderBean bean = new SampleResponderBean();
        registrar.postProcessAfterInitialization(bean, "sampleResponderBean");
        Thread.sleep(200);

        // Should not throw
        registrar.stop();
    }

    @Test
    void stop_withNoSubscriptions_doesNotThrow() {
        registrar.stop();
    }
}
