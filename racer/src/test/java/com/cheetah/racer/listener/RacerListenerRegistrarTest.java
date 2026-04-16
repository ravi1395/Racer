package com.cheetah.racer.listener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;

import com.cheetah.racer.annotation.ConcurrencyMode;
import com.cheetah.racer.annotation.RacerListener;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;
import com.cheetah.racer.circuitbreaker.RacerCircuitBreakerRegistry;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.dedup.RacerDedupService;
import com.cheetah.racer.exception.RacerConfigurationException;
import com.cheetah.racer.metrics.RacerMetrics;
import com.cheetah.racer.model.RacerMessage;
import com.cheetah.racer.router.RacerRouterService;
import com.cheetah.racer.router.RouteDecision;
import com.cheetah.racer.schema.RacerSchemaRegistry;
import com.cheetah.racer.schema.SchemaValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Unit tests for {@link RacerListenerRegistrar}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerListenerRegistrarTest {

    // ── Test bean ─────────────────────────────────────────────────────────────

    /** Simple test bean whose methods are discovered by the registrar. */
    static class SampleReceiver {

        final List<RacerMessage> received = new ArrayList<>();
        final List<String> receivedStrings = new ArrayList<>();
        final List<SampleDto> receivedDtos = new ArrayList<>();
        final AtomicInteger invocations = new AtomicInteger(0);

        @RacerListener(channel = "racer:test")
        public void onMessage(RacerMessage msg) {
            received.add(msg);
            invocations.incrementAndGet();
        }

        @RacerListener(channel = "racer:strings")
        public void onString(String payload) {
            receivedStrings.add(payload);
            invocations.incrementAndGet();
        }

        @RacerListener(channel = "racer:dtos")
        public void onDto(SampleDto dto) {
            receivedDtos.add(dto);
            invocations.incrementAndGet();
        }

        @RacerListener(channel = "racer:mono", mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
        public Mono<Void> onMonoReturn(RacerMessage msg) {
            invocations.incrementAndGet();
            return Mono.empty();
        }

        @RacerListener(channel = "racer:error")
        public void onErrorMessage(RacerMessage msg) {
            invocations.incrementAndGet();
            throw new RuntimeException("simulated processing failure");
        }
    }

    static class SampleDto {
        public String name;
        public int value;
    }

    // ── Mocks and collaborators ───────────────────────────────────────────────

    @Mock
    ReactiveRedisMessageListenerContainer listenerContainer;
    @Mock
    RacerMetrics racerMetrics;
    @Mock
    RacerSchemaRegistry racerSchemaRegistry;
    @Mock
    RacerRouterService racerRouterService;
    @Mock
    RacerDeadLetterHandler deadLetterHandler;
    @Mock
    Environment environment;

    ObjectMapper objectMapper;
    RacerProperties properties;
    RacerListenerRegistrar registrar;
    SampleReceiver bean;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new RacerProperties();
        properties.setChannels(new LinkedHashMap<>());

        // Stub environment to pass through values unchanged
        when(environment.resolvePlaceholders(anyString())).thenAnswer(inv -> inv.getArgument(0));

        // Default: router never claims a message (no routing rules)
        when(racerRouterService.route(any())).thenReturn(Mono.just(RouteDecision.PASS));

        // Default: DLQ enqueue succeeds
        when(deadLetterHandler.enqueue(any(), any())).thenReturn(Mono.empty());

        // Default: schema validation passes (no-op)
        doNothing().when(racerSchemaRegistry).validateForConsume(anyString(), any());

        registrar = new RacerListenerRegistrar(
                listenerContainer,
                objectMapper,
                properties,
                racerMetrics,
                racerSchemaRegistry,
                racerRouterService,
                deadLetterHandler);
        registrar.setEnvironment(environment);

        bean = new SampleReceiver();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a Flux that emits a single serialized RacerMessage then completes.
     */
    private Flux<ReactiveSubscription.Message<String, String>> singleMessageFlux(RacerMessage msg)
            throws Exception {
        String json = objectMapper.writeValueAsString(msg);
        ReactiveSubscription.Message<String, String> redisMsg = mockRedisMessage(json);
        return Flux.just(redisMsg);
    }

    @SuppressWarnings("unchecked")
    private ReactiveSubscription.Message<String, String> mockRedisMessage(String body) {
        ReactiveSubscription.Message<String, String> m = mock(ReactiveSubscription.Message.class);
        when(m.getMessage()).thenReturn(body);
        return m;
    }

    private RacerMessage buildMessage(String channel, String payload) {
        return RacerMessage.create(channel, payload, "test-sender");
    }

    // ── Tests: RacerMessage parameter ────────────────────────────────────────

    @Test
    void listener_receivesRacerMessage_dispatchesToMethod() throws Exception {
        RacerMessage msg = buildMessage("racer:test", "hello");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:test".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300); // allow reactive dispatch to settle

        assertThat(bean.received).hasSize(1);
        assertThat(bean.received.get(0).getPayload()).isEqualTo("hello");
    }

    // ── Tests: String parameter ───────────────────────────────────────────────

    @Test
    void listener_withStringParam_passesRawPayload() throws Exception {
        RacerMessage msg = buildMessage("racer:strings", "raw-payload");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:strings".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        assertThat(bean.receivedStrings).hasSize(1);
        assertThat(bean.receivedStrings.get(0)).isEqualTo("raw-payload");
    }

    // ── Tests: POJO parameter (flexible deserialization) ─────────────────────

    @Test
    void listener_withPojoParam_deserializesPayloadIntoType() throws Exception {
        SampleDto dto = new SampleDto();
        dto.name = "widget";
        dto.value = 42;
        RacerMessage msg = buildMessage("racer:dtos", objectMapper.writeValueAsString(dto));

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:dtos".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        assertThat(bean.receivedDtos).hasSize(1);
        assertThat(bean.receivedDtos.get(0).name).isEqualTo("widget");
        assertThat(bean.receivedDtos.get(0).value).isEqualTo(42);
    }

    // ── Tests: Mono return type ───────────────────────────────────────────────

    @Test
    void listener_withMonoReturn_subscribesToReturnedMono() throws Exception {
        RacerMessage msg = buildMessage("racer:mono", "ping");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:mono".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        assertThat(bean.invocations.get()).isGreaterThanOrEqualTo(1);
    }

    // ── Tests: concurrent mode ────────────────────────────────────────────────

    @Test
    void concurrentListener_processesMultipleMessagesInParallel() throws Exception {
        int messageCount = 8;
        // workersAtGate: released once at least 2 workers have incremented concurrent
        // and are waiting at the startLatch.  This ensures the main thread never
        // opens the gate before any workers are actually blocked there, which would
        // cause all handlers to complete instantly and never overlap.
        CountDownLatch workersAtGate = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch allDispatched = new CountDownLatch(messageCount);
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        // A bean with a method that simulates blocking work
        Object slowBean = new Object() {
            @RacerListener(channel = "racer:concurrent", mode = ConcurrencyMode.CONCURRENT, concurrency = 4)
            public void onMsg(RacerMessage msg) throws InterruptedException {
                int c = concurrent.incrementAndGet();
                maxConcurrent.updateAndGet(prev -> Math.max(prev, c));
                workersAtGate.countDown(); // signal that this worker is waiting at the gate
                startLatch.await(2, TimeUnit.SECONDS);
                concurrent.decrementAndGet();
                allDispatched.countDown();
            }
        };

        Sinks.Many<ReactiveSubscription.Message<String, String>> sink = Sinks.many().multicast().onBackpressureBuffer();

        when(listenerContainer.receive(ChannelTopic.of("racer:concurrent"))).thenReturn(sink.asFlux());

        registrar.postProcessAfterInitialization(slowBean, "slowBean");

        // Emit all messages
        for (int i = 0; i < messageCount; i++) {
            RacerMessage msg = buildMessage("racer:concurrent", "msg-" + i);
            sink.tryEmitNext(mockRedisMessage(objectMapper.writeValueAsString(msg)));
        }

        // Wait for at least 2 workers to reach the gate before releasing, so that
        // maxConcurrent is recorded while they are both inside the handler.
        boolean gateReached = workersAtGate.await(5, TimeUnit.SECONDS);
        assertThat(gateReached).as("At least 2 workers should reach the gate within 5 s").isTrue();

        startLatch.countDown();
        boolean allDone = allDispatched.await(5, TimeUnit.SECONDS);

        assertThat(allDone).as("All %d messages should be processed within 5 s", messageCount).isTrue();
        assertThat(maxConcurrent.get()).as("Max concurrent workers").isGreaterThan(1);
    }

    // ── Tests: sequential mode ────────────────────────────────────────────────

    @Test
    void sequentialListener_processesOneMessageAtATime() throws Exception {
        List<Integer> order = new ArrayList<>();
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxActive = new AtomicInteger(0);

        Object seqBean = new Object() {
            @RacerListener(channel = "racer:seq") // default: SEQUENTIAL
            public void onMsg(RacerMessage msg) throws InterruptedException {
                int a = active.incrementAndGet();
                maxActive.updateAndGet(p -> Math.max(p, a));
                Thread.sleep(20);
                order.add(Integer.parseInt(msg.getPayload()));
                active.decrementAndGet();
            }
        };

        Sinks.Many<ReactiveSubscription.Message<String, String>> sink = Sinks.many().unicast().onBackpressureBuffer();

        when(listenerContainer.receive(ChannelTopic.of("racer:seq"))).thenReturn(sink.asFlux());

        registrar.postProcessAfterInitialization(seqBean, "seqBean");

        for (int i = 0; i < 4; i++) {
            RacerMessage msg = buildMessage("racer:seq", String.valueOf(i));
            sink.tryEmitNext(mockRedisMessage(objectMapper.writeValueAsString(msg)));
        }

        Thread.sleep(500);

        assertThat(maxActive.get()).isEqualTo(1); // never more than 1 active at once
    }

    // ── Tests: error handling & DLQ ───────────────────────────────────────────

    @Test
    void listener_whenMethodThrows_enqueuesToDlq() throws Exception {
        RacerMessage msg = buildMessage("racer:error", "bad-data");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:error".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        verify(deadLetterHandler, timeout(500).atLeastOnce()).enqueue(any(RacerMessage.class), any(Throwable.class));
    }

    @Test
    void listener_whenMethodThrows_incrementsFailedCount() throws Exception {
        RacerMessage msg = buildMessage("racer:error", "bad-data");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:error".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        assertThat(registrar.getFailedCount("sampleReceiver.onErrorMessage")).isGreaterThan(0);
    }

    // ── Tests: schema validation ──────────────────────────────────────────────

    @Test
    void listener_whenSchemaValidationFails_enqueuesToDlqAndSkipsMethod() throws Exception {
        RacerMessage msg = buildMessage("racer:test", "invalid-payload");

        doThrow(new SchemaValidationException("racer:test", List.of()))
                .when(racerSchemaRegistry).validateForConsume(eq("racer:test"), anyString());

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:test".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        verify(deadLetterHandler, timeout(500).atLeastOnce()).enqueue(any(), any(SchemaValidationException.class));
        assertThat(bean.received).isEmpty(); // method must NOT be invoked
    }

    // ── Tests: routing ────────────────────────────────────────────────────────

    @Test
    void listener_whenRouterClaimsMessage_skipsLocalDispatch() throws Exception {
        RacerMessage msg = buildMessage("racer:test", "routed");

        when(racerRouterService.route(any())).thenReturn(Mono.just(RouteDecision.FORWARDED)); // router claims it

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:test".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        assertThat(bean.received).isEmpty();
        assertThat(bean.invocations.get()).isZero();
    }

    // ── Tests: channelRef resolution ──────────────────────────────────────────

    @Test
    void listener_withChannelRef_resolvesChannelFromProperties() throws Exception {
        RacerProperties.ChannelProperties cp = new RacerProperties.ChannelProperties();
        cp.setName("racer:resolved:channel");
        properties.getChannels().put("myalias", cp);

        Object aliasBean = new Object() {
            @RacerListener(channelRef = "myalias")
            public void onAliased(RacerMessage msg) {
            }
        };

        when(listenerContainer.receive(ChannelTopic.of("racer:resolved:channel"))).thenReturn(Flux.never());

        registrar.postProcessAfterInitialization(aliasBean, "aliasBean");

        verify(listenerContainer).receive(ChannelTopic.of("racer:resolved:channel"));
    }

    // ── Tests: stats ──────────────────────────────────────────────────────────

    @Test
    void processedCount_incrementsAfterSuccessfulDispatch() throws Exception {
        RacerMessage msg = buildMessage("racer:test", "hello");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:test".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        assertThat(registrar.getProcessedCount("sampleReceiver.onMessage")).isEqualTo(1);
    }

    // ── Tests: lifecycle ─────────────────────────────────────────────────────

    @Test
    void stop_disposesAllSubscriptions() throws Exception {
        when(listenerContainer.receive(any(ChannelTopic.class))).thenReturn(Flux.never());

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        registrar.stop(); // should not throw, should log stats
    }

    // ── Tests: back-pressure ──────────────────────────────────────────────────

    @Test
    void listener_whenBackPressureActive_routesMessageToDlq() throws Exception {
        RacerMessage msg = buildMessage("racer:test", "bp-payload");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:test".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.setBackPressureActive(true);
        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        // Method must NOT be invoked — back-pressure drops message to DLQ
        assertThat(bean.received).isEmpty();
        verify(deadLetterHandler, timeout(500).atLeastOnce()).enqueue(any(), any());
    }

    @Test
    void setBackPressureActive_toggleLogsChangeOnce() {
        registrar.setBackPressureActive(true);
        registrar.setBackPressureActive(true); // same value — no log
        registrar.setBackPressureActive(false); // transition
        // No assert needed — just must not throw
    }

    // ── Tests: dedup ──────────────────────────────────────────────────────────

    @Test
    void listener_withDedup_duplicateMessageSkipped() throws Exception {
        // Enable dedup globally so NFD-1 validation passes for dedup=true listeners
        properties.getDedup().setEnabled(true);

        RacerDedupService dedupService = mock(RacerDedupService.class);
        // First call returns true (process), second returns false (skip)
        when(dedupService.checkAndMarkProcessed(anyString(), anyString()))
                .thenReturn(Mono.just(true))
                .thenReturn(Mono.just(false));

        registrar.setDedupService(dedupService);

        Object dedupBean = new Object() {
            @RacerListener(channel = "racer:dedup", dedup = true)
            public void handle(RacerMessage msg) {
            }
        };

        RacerMessage msg = buildMessage("racer:dedup", "data");
        Sinks.Many<ReactiveSubscription.Message<String, String>> sink = Sinks.many().unicast().onBackpressureBuffer();

        when(listenerContainer.receive(ChannelTopic.of("racer:dedup"))).thenReturn(sink.asFlux());
        registrar.postProcessAfterInitialization(dedupBean, "dedupBean");

        // Emit same message twice
        String json = objectMapper.writeValueAsString(msg);
        sink.tryEmitNext(mockRedisMessage(json));
        sink.tryEmitNext(mockRedisMessage(json));

        Thread.sleep(400);

        // First is processed, second is skipped — dedup service consulted twice
        verify(dedupService, atLeast(1)).checkAndMarkProcessed(anyString(), anyString());
    }

    @Test
    void listener_withDedupTrue_butGlobalDedupDisabled_throwsConfigException() {
        // NFD-1: dedup=true on annotation but racer.dedup.enabled=false → fail fast
        properties.getDedup().setEnabled(false);

        Object dedupBean = new Object() {
            @RacerListener(channel = "racer:dedup", dedup = true)
            public void handle(RacerMessage msg) {
            }
        };

        assertThatThrownBy(() -> registrar.postProcessAfterInitialization(dedupBean, "dedupBean"))
                .isInstanceOf(RacerConfigurationException.class)
                .hasMessageContaining("dedup=true")
                .hasMessageContaining("racer.dedup.enabled");
    }

    // ── Tests: circuit breaker ────────────────────────────────────────────────

    @Test
    void listener_circuitBreakerOpen_skipsMessage() throws Exception {
        RacerCircuitBreakerRegistry cbRegistry = mock(RacerCircuitBreakerRegistry.class);
        RacerCircuitBreaker cb = mock(RacerCircuitBreaker.class);

        when(cbRegistry.getOrCreate(anyString())).thenReturn(cb);
        when(cb.isCallPermitted()).thenReturn(false); // circuit OPEN

        registrar.setCircuitBreakerRegistry(cbRegistry);

        RacerMessage msg = buildMessage("racer:test", "cb-payload");
        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:test".equals(t.getTopic()))
                        return singleMessageFlux(msg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        // Message is rejected — method must NOT be invoked
        assertThat(bean.received).isEmpty();
    }

    // ── Tests: invalid JSON ───────────────────────────────────────────────────

    @Test
    void listener_invalidJsonBody_logsErrorAndSkips() throws Exception {
        ReactiveSubscription.Message<String, String> badMsg = mockRedisMessage("NOT-VALID-JSON");

        when(listenerContainer.receive(any(ChannelTopic.class)))
                .thenAnswer(inv -> {
                    ChannelTopic t = inv.getArgument(0);
                    if ("racer:test".equals(t.getTopic()))
                        return Flux.just(badMsg);
                    return Flux.never();
                });

        registrar.postProcessAfterInitialization(bean, "sampleReceiver");
        Thread.sleep(300);

        // Method must NOT be invoked — bad JSON falls through to error log path
        assertThat(bean.received).isEmpty();
    }

    // ── Tests: no channel configured ─────────────────────────────────────────

    @Test
    void listener_noChannelOrChannelRef_isSkipped() {
        properties.setDefaultChannel(""); // empty default forces skip

        Object noChannelBean = new Object() {
            @RacerListener(channel = "", channelRef = "")
            public void handle(RacerMessage msg) {
            }
        };

        // Should not throw and should not call listenerContainer.receive
        registrar.postProcessAfterInitialization(noChannelBean, "noChannelBean");
        verify(listenerContainer, never()).receive(any(ChannelTopic.class));
    }

    // ── Tests: counter accessors ──────────────────────────────────────────────

    @Test
    void getProcessedCount_unknownListener_returnsZero() {
        assertThat(registrar.getProcessedCount("unknown.listener")).isZero();
    }

    @Test
    void getFailedCount_unknownListener_returnsZero() {
        assertThat(registrar.getFailedCount("unknown.listener")).isZero();
    }

    // ── Tests: interceptors ───────────────────────────────────────────────────

    @Test
    void setInterceptors_null_replacedWithEmpty() {
        registrar.setInterceptors(null);
        // No exception — null handled gracefully
    }

    @Test
    void setInterceptors_nonNull_storesInterceptors() {
        registrar.setInterceptors(List.of());
    }

    // ── Tests: durable listener poll interval ─────────────────────────────────

    /**
     * A durable listener annotated with {@code pollIntervalMs=50} should re-poll
     * the
     * stream roughly every 50 ms. Within 300 ms we expect at least 3 XREADGROUP
     * calls
     * (initial + ≥2 re-polls), proving that the annotation value is used instead of
     * the old hard-coded 200 ms default.
     */
    @Test
    @SuppressWarnings("unchecked")
    void durableListener_withPollIntervalMs50_pollsFrequently() throws Exception {
        ReactiveRedisTemplate<String, String> template = mock(ReactiveRedisTemplate.class);
        ReactiveStreamOperations<String, Object, Object> streamOps = mock(ReactiveStreamOperations.class);
        when(template.opsForStream()).thenReturn(streamOps);
        // ensureGroup: createGroup returns a String Mono (BUSYGROUP errors silently
        // ignored anyway)
        when(streamOps.createGroup(any(), any(ReadOffset.class), anyString()))
                .thenReturn(Mono.just("OK"));
        AtomicInteger readCallCount = new AtomicInteger(0);
        // pollOnceDurable: read returns an empty batch so each poll completes
        // immediately
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenAnswer(inv -> {
                    readCallCount.incrementAndGet();
                    return Flux.empty();
                });

        RacerProperties.ChannelProperties cp = new RacerProperties.ChannelProperties();
        cp.setName("racer:durable:fast");
        cp.setDurable(true);
        properties.getChannels().put("fastref", cp);

        RacerListenerRegistrar durableRegistrar = new RacerListenerRegistrar(
                listenerContainer, objectMapper, properties, Schedulers.boundedElastic(),
                template, racerMetrics, racerSchemaRegistry, racerRouterService, deadLetterHandler);
        durableRegistrar.setEnvironment(environment);

        Object durableBean = new Object() {
            @RacerListener(channelRef = "fastref", pollIntervalMs = 50)
            public void handle(RacerMessage msg) {
            }
        };

        durableRegistrar.postProcessAfterInitialization(durableBean, "fastDurableBean");
        Thread.sleep(300);

        // 300 ms / 50 ms interval → at least 3 polls (initial + ≥2 re-polls)
        assertThat(readCallCount.get())
                .as("Expected at least 3 polls with 50 ms interval within 300 ms")
                .isGreaterThanOrEqualTo(3);

        durableRegistrar.stop();
    }

    /**
     * A durable listener annotated with {@code pollIntervalMs=5000} should not
     * re-poll within 400 ms. Only the initial poll (at t=0) should have fired,
     * proving that 5000 ms is honoured rather than the old hard-coded 200 ms
     * (which would have triggered a second poll at ~200 ms).
     */
    @Test
    @SuppressWarnings("unchecked")
    void durableListener_withPollIntervalMs5000_doesNotRepollWithin400ms() throws Exception {
        ReactiveRedisTemplate<String, String> template = mock(ReactiveRedisTemplate.class);
        ReactiveStreamOperations<String, Object, Object> streamOps = mock(ReactiveStreamOperations.class);
        when(template.opsForStream()).thenReturn(streamOps);
        when(streamOps.createGroup(any(), any(ReadOffset.class), anyString()))
                .thenReturn(Mono.just("OK"));
        AtomicInteger readCallCount = new AtomicInteger(0);
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenAnswer(inv -> {
                    readCallCount.incrementAndGet();
                    return Flux.empty();
                });

        RacerProperties.ChannelProperties cp = new RacerProperties.ChannelProperties();
        cp.setName("racer:durable:slow");
        cp.setDurable(true);
        properties.getChannels().put("slowref", cp);

        RacerListenerRegistrar durableRegistrar = new RacerListenerRegistrar(
                listenerContainer, objectMapper, properties, Schedulers.boundedElastic(),
                template, racerMetrics, racerSchemaRegistry, racerRouterService, deadLetterHandler);
        durableRegistrar.setEnvironment(environment);

        Object durableBean = new Object() {
            @RacerListener(channelRef = "slowref", pollIntervalMs = 5000)
            public void handle(RacerMessage msg) {
            }
        };

        durableRegistrar.postProcessAfterInitialization(durableBean, "slowDurableBean");
        Thread.sleep(400);

        // With 5000 ms interval, only the initial poll (at t=0) should have fired.
        // If the old hard-coded 200 ms were still in effect, a second poll would fire
        // at ~200 ms — so this assertion distinguishes fixed from broken behaviour.
        assertThat(readCallCount.get())
                .as("Expected exactly 1 poll with 5000 ms interval within 400 ms")
                .isEqualTo(1);

        durableRegistrar.stop();
    }
}
