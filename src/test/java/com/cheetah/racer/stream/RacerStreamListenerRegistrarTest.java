package com.cheetah.racer.stream;

import com.cheetah.racer.annotation.RacerStreamListener;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.listener.RacerMessageInterceptor;
import com.cheetah.racer.model.RacerMessage;
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
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RacerStreamListenerRegistrar}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerStreamListenerRegistrarTest {

    // ── Test beans ────────────────────────────────────────────────────────────

    static class SampleStreamBean {
        @RacerStreamListener(streamKey = "stream:orders", group = "test-group")
        public void handleOrder(RacerMessage msg) {}
    }

    static class PlainBean {
        public void noAnnotation() {}
    }

    // ── Mocks and collaborators ───────────────────────────────────────────────

    @Mock ReactiveRedisTemplate<String, String> redisTemplate;
    @SuppressWarnings("rawtypes")
    @Mock ReactiveStreamOperations streamOps;
    @Mock Environment environment;

    ObjectMapper objectMapper;
    RacerProperties properties;
    RacerStreamListenerRegistrar registrar;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new RacerProperties();
        properties.setChannels(new LinkedHashMap<>());

        when(environment.resolvePlaceholders(anyString())).thenAnswer(inv -> inv.getArgument(0));

        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(streamOps.createGroup(anyString(), any(), anyString())).thenReturn(Mono.just("OK"));

        registrar = new RacerStreamListenerRegistrar(
                redisTemplate, objectMapper, properties, null, null, null);
        registrar.setEnvironment(environment);
    }

    // ── Tests: bean discovery ─────────────────────────────────────────────────

    @Test
    void postProcessAfterInitialization_discoversAnnotatedMethods() throws Exception {
        SampleStreamBean bean = new SampleStreamBean();
        Object result = registrar.postProcessAfterInitialization(bean, "sampleStreamBean");

        assertThat(result).isSameAs(bean);
        // Allow async group creation to complete
        Thread.sleep(300);
    }

    @Test
    void postProcessAfterInitialization_noAnnotation_returnsBean() {
        PlainBean bean = new PlainBean();
        Object result = registrar.postProcessAfterInitialization(bean, "plainBean");

        assertThat(result).isSameAs(bean);
    }

    // ── Tests: tracked stream groups ──────────────────────────────────────────

    @Test
    void getTrackedStreamGroups_afterRegistration_containsEntry() throws Exception {
        SampleStreamBean bean = new SampleStreamBean();
        registrar.postProcessAfterInitialization(bean, "sampleStreamBean");
        Thread.sleep(200);

        assertThat(registrar.getTrackedStreamGroups())
                .containsEntry("stream:orders", "test-group");
    }

    // ── Tests: counter accessors ──────────────────────────────────────────────

    @Test
    void getProcessedCount_unknownListener_returnsZero() {
        assertThat(registrar.getProcessedCount("nonexistent")).isZero();
    }

    @Test
    void getFailedCount_unknownListener_returnsZero() {
        assertThat(registrar.getFailedCount("nonexistent")).isZero();
    }

    // ── Tests: interceptors ───────────────────────────────────────────────────

    @Test
    void setInterceptors_storesInterceptors() {
        List<RacerMessageInterceptor> interceptors = Collections.emptyList();
        registrar.setInterceptors(interceptors);
        // No exception — interceptors stored successfully
    }

    @Test
    void setInterceptors_nullSafe() {
        registrar.setInterceptors(null);
        // No exception — null handled gracefully
    }

    // ── Tests: log prefix ─────────────────────────────────────────────────────

    @Test
    void logPrefix_returnsExpected() throws Exception {
        // logPrefix() is protected — we can test it via the class since we are in the same package
        // Use reflection to access the protected method
        java.lang.reflect.Method logPrefixMethod =
                RacerStreamListenerRegistrar.class.getDeclaredMethod("logPrefix");
        logPrefixMethod.setAccessible(true);
        String prefix = (String) logPrefixMethod.invoke(registrar);

        assertThat(prefix).isEqualTo("RACER-STREAM-LISTENER");
    }

    // ── Tests: lifecycle ──────────────────────────────────────────────────────

    @Test
    void stop_disposesSubscriptions() throws Exception {
        SampleStreamBean bean = new SampleStreamBean();
        registrar.postProcessAfterInitialization(bean, "sampleStreamBean");
        Thread.sleep(300);

        // Should not throw
        registrar.stop();
    }
}
