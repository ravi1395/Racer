package com.cheetah.racer.listener;

import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for v1.4.0 item 1.2: deserialization error visibility.
 *
 * <p>When a {@code @RacerListener} declares a typed parameter (not {@link String}
 * or {@link RacerMessage}) and the message payload cannot be deserialized into that
 * type, the registrar must:
 * <ol>
 *   <li>Not invoke the listener method.</li>
 *   <li>Forward the message to the DLQ via {@link RacerDeadLetterHandler}.</li>
 *   <li>Pass the full deserialization exception to the DLQ handler so
 *       {@link com.cheetah.racer.model.DeadLetterMessage#getErrorMessage()} is
 *       populated with a useful diagnostic string.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerListenerDeserializationErrorTest {

    // ── Test bean ─────────────────────────────────────────────────────────────

    /** A DTO that requires valid JSON with "name" and "value" fields. */
    static class OrderDto {
        public String name;
        public int value;
    }

    /** Listener that expects an {@code OrderDto} — deserialization can fail here. */
    static class TypedListener {
        final List<OrderDto> received = new ArrayList<>();

        @RacerListener(channel = "racer:orders")
        public void onOrder(OrderDto dto) {
            received.add(dto);
        }
    }

    // ── Mocks and infrastructure ──────────────────────────────────────────────

    @Mock private ReactiveRedisMessageListenerContainer listenerContainer;
    @Mock private RacerDeadLetterHandler                deadLetterHandler;

    private ObjectMapper objectMapper;
    private RacerListenerRegistrar registrar;
    private TypedListener bean;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        RacerProperties props = new RacerProperties();
        props.setChannels(new LinkedHashMap<>());

        MockEnvironment env = new MockEnvironment();

        // Default: DLQ enqueue succeeds synchronously
        when(deadLetterHandler.enqueue(any(), any())).thenReturn(Mono.empty());

        registrar = new RacerListenerRegistrar(
                listenerContainer,
                objectMapper,
                props,
                null, // RacerMetrics — not needed here
                null, // RacerSchemaRegistry — not needed here
                null, // RacerRouterService — not needed here
                deadLetterHandler);
        registrar.setEnvironment(env);

        bean = new TypedListener();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Serialises a {@link RacerMessage} the same way the listener container would
     * deliver it — as a JSON string where the payload field contains the actual data.
     */
    private Flux<ReactiveSubscription.Message<String, String>> msgFlux(RacerMessage msg)
            throws Exception {
        String json = objectMapper.writeValueAsString(msg);
        ReactiveSubscription.Message<String, String> redisMsg =
                mock(ReactiveSubscription.Message.class, withSettings().lenient());
        when(redisMsg.getMessage()).thenReturn(json);
        return Flux.just(redisMsg);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /** Malformed JSON (truncated) that Jackson cannot parse as {@code OrderDto}. */
    @Test
    void malformedJson_doesNotCallListener_sendsToDlq() throws Exception {
        // Payload is obviously not valid OrderDto JSON
        RacerMessage msg = RacerMessage.create("racer:orders", "{bad json!!!", "test");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:orders".equals(t.getTopic())) return msgFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "typedBean");
        Thread.sleep(300); // let reactive dispatch settle

        // Listener must NOT have been invoked with a partially constructed DTO
        assertThat(bean.received).isEmpty();

        // DLQ handler must have been called exactly once for this message
        verify(deadLetterHandler, times(1)).enqueue(eq(msg), any(Exception.class));
    }

    @Test
    void malformedJson_dlqEnqueuedWithNonNullExceptionMessage() throws Exception {
        RacerMessage msg = RacerMessage.create("racer:orders", "THIS IS NOT JSON", "test");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:orders".equals(t.getTopic())) return msgFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "typedBean");
        Thread.sleep(300);

        // Capture the exception that was forwarded to the DLQ handler
        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(deadLetterHandler).enqueue(eq(msg), captor.capture());

        // The exception message must not be null — it will be stored in DeadLetterMessage.errorMessage
        Exception caught = captor.getValue();
        assertThat(caught.getMessage()).isNotNull();
        assertThat(caught.getMessage()).isNotBlank();
    }

    /**
     * A valid {@link String} payload is never deserialized to a POJO, so there
     * is no risk of a deserialization error. The DLQ handler must not be called.
     */
    @Test
    void validStringPayload_listenerIsCalledAndDlqIsNotInvoked() throws Exception {
        RacerMessage msg = RacerMessage.create("racer:orders",
                "{\"name\":\"widget\",\"value\":42}", "test");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:orders".equals(t.getTopic())) return msgFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "typedBean");
        Thread.sleep(300);

        // Success path: listener IS invoked
        assertThat(bean.received).hasSize(1);
        assertThat(bean.received.get(0).name).isEqualTo("widget");

        // No DLQ entry for a successful dispatch
        verifyNoInteractions(deadLetterHandler);
    }
}
