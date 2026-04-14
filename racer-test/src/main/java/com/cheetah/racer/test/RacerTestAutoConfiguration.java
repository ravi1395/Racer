package com.cheetah.racer.test;

import java.util.Optional;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;

import com.cheetah.racer.config.RacerAutoConfiguration;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.config.RedisConfig;
import com.cheetah.racer.listener.RacerListenerRegistrar;
import com.cheetah.racer.stream.RacerStreamListenerRegistrar;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring Boot auto-configuration for the Racer test support module.
 *
 * <p>Activated automatically when {@link RacerTest @RacerTest} is applied to a test
 * class (via {@code @ImportAutoConfiguration}).  Provides the following beans:
 *
 * <ul>
 *   <li><strong>{@link InMemoryRacerPublisherRegistry} ({@code @Primary})</strong> —
 *       replaces the real {@link com.cheetah.racer.publisher.RacerPublisherRegistry} so
 *       all publish calls capture messages in memory rather than sending them to Redis.</li>
 *   <li><strong>No-op {@link ReactiveRedisTemplate}</strong> ({@code @ConditionalOnMissingBean})
 *       — a Mockito-backed stub that returns empty {@link Mono}/{@link Flux} for every
 *       Redis operation.  Only activated when no real Redis template is already present
 *       in the context (i.e. when Redis is not configured).</li>
 *   <li><strong>No-op {@link ReactiveRedisMessageListenerContainer}</strong>
 *       ({@code @ConditionalOnMissingBean}) — a Mockito stub whose {@code receive()}
 *       method returns {@link Flux#never()} so that {@link RacerListenerRegistrar}
 *       subscriptions never emit real messages.  Tests inject messages directly via
 *       {@link RacerTestHarness#fireAt}.</li>
 *   <li><strong>{@link RacerTestHarness}</strong> — registered when
 *       {@link RacerListenerRegistrar} is present.  Provides {@code fireAt()} and
 *       {@code fireAtStream()} for injecting synthetic messages.</li>
 * </ul>
 *
 * <h3>Ordering</h3>
 * <p>Declared {@code @AutoConfigureAfter} {@link RacerAutoConfiguration} so that
 * {@link com.cheetah.racer.config.RacerProperties} and
 * {@link RacerListenerRegistrar} are already registered when the
 * {@code @ConditionalOnBean} guards are evaluated.  Also declared
 * {@code @AutoConfigureBefore} {@link RedisConfig} so that the no-op Redis stubs are
 * registered before {@link RedisConfig} can create real infrastructure beans.
 * {@link RedisConfig} must therefore be annotated with {@code @ConditionalOnMissingBean}
 * on its template beans (see {@code RedisConfig.java}) so it skips creation when the
 * test stubs are already present.
 *
 * <h3>Real Redis in tests</h3>
 * <p>If a real Redis is available in the test environment (e.g. via Testcontainers),
 * the {@code @ConditionalOnMissingBean} guards ensure the no-op stubs are NOT activated.
 * In that case the real Redis infrastructure is used and
 * {@link InMemoryRacerPublisherRegistry} ({@code @Primary}) still replaces the publisher
 * registry so that publish assertions work without Redis.
 *
 * @see RacerTest
 * @see InMemoryRacerPublisherRegistry
 * @see RacerTestHarness
 */
@Slf4j
@Configuration
@AutoConfigureBefore(RedisConfig.class)
@AutoConfigureAfter(RacerAutoConfiguration.class)
public class RacerTestAutoConfiguration {

    // ── No-op Redis infrastructure stubs ─────────────────────────────────────

    /**
     * Provides a no-op {@link ReactiveRedisTemplate} when no real template is present.
     *
     * <p>All reactive methods ({@link Mono}-returning and {@link Flux}-returning) return
     * empty publishers.  Methods returning interface types (e.g. {@code opsForList()})
     * return a Mockito mock of that interface using the same empty-reactive answer,
     * so chained calls do not throw {@link NullPointerException}.
     *
     * <p>This bean is skipped when a real {@code ReactiveRedisTemplate} already exists
     * in the context — for example when the test uses a Testcontainers Redis.
     *
     * @return a no-op reactive Redis template stub
     */
    @Bean("reactiveStringRedisTemplate")
    @ConditionalOnMissingBean(name = "reactiveStringRedisTemplate")
    @SuppressWarnings("unchecked")
    public ReactiveRedisTemplate<String, String> noOpReactiveStringRedisTemplate() {
        log.info("[RACER-TEST] No real ReactiveRedisTemplate found — registering no-op stub.");
        return (ReactiveRedisTemplate<String, String>)
                Mockito.mock(ReactiveRedisTemplate.class, new ReactiveEmptyAnswer());
    }

    /**
     * Provides a no-op {@link ReactiveRedisMessageListenerContainer} when no real
     * container is present.
     *
     * <p>Uses the same {@link ReactiveEmptyAnswer} as the Redis template stub, so
     * {@code receive(ChannelTopic...)} and {@code receive(PatternTopic...)} both return
     * {@link Flux#empty()}.  The {@link RacerListenerRegistrar} subscription completes
     * immediately without emitting messages, which is the desired behaviour: tests inject
     * messages directly via {@link RacerTestHarness#fireAt} instead.
     *
     * <p>Using {@code Flux.empty()} rather than {@code Flux.never()} avoids Mockito
     * ambiguity between the two varargs {@code receive()} overloads at compile time while
     * still preventing any real messages from arriving at the handler.
     *
     * @return a no-op listener container stub
     */
    @Bean
    @ConditionalOnMissingBean(ReactiveRedisMessageListenerContainer.class)
    public ReactiveRedisMessageListenerContainer noOpReactiveRedisMessageListenerContainer() {
        log.info("[RACER-TEST] No real ReactiveRedisMessageListenerContainer found — "
                + "registering no-op stub (subscriptions will use RacerTestHarness.fireAt).");
        // ReactiveEmptyAnswer returns Flux.empty() for Flux-returning methods, including
        // both overloads of receive() — no Mockito type-ambiguity at compile time.
        return Mockito.mock(ReactiveRedisMessageListenerContainer.class, new ReactiveEmptyAnswer());
    }

    // ── In-memory publisher registry ──────────────────────────────────────────

    /**
     * Registers an {@link InMemoryRacerPublisherRegistry} as the {@code @Primary}
     * {@link com.cheetah.racer.publisher.RacerPublisherRegistry} bean.
     *
     * <p>Any bean that injects {@code RacerPublisherRegistry} — including
     * {@link com.cheetah.racer.aspect.PublishResultAspect} and
     * {@link com.cheetah.racer.processor.RacerPublisherFieldProcessor} — will receive
     * this in-memory variant and therefore capture published messages rather than
     * sending them to Redis.
     *
     * @param racerProperties Racer configuration properties
     * @param objectMapper    Jackson mapper for payload equality comparisons
     * @return the in-memory publisher registry
     */
    @Bean
    @Primary
    public InMemoryRacerPublisherRegistry inMemoryRacerPublisherRegistry(
            RacerProperties racerProperties, ObjectMapper objectMapper) {
        log.info("[RACER-TEST] Registering InMemoryRacerPublisherRegistry as @Primary.");
        return new InMemoryRacerPublisherRegistry(racerProperties, objectMapper);
    }

    // ── Test harness ──────────────────────────────────────────────────────────

    /**
     * Registers the {@link RacerTestHarness} bean when a {@link RacerListenerRegistrar}
     * is present in the application context.
     *
     * <p>The harness requires the listener registrar to be fully initialized so that all
     * {@link com.cheetah.racer.annotation.RacerListener}-annotated methods have been
     * discovered and registered.
     *
     * @param listenerRegistrar   the Pub/Sub listener registrar (always present when
     *                            a {@link ReactiveRedisMessageListenerContainer} bean exists)
     * @param streamRegistrar     the stream listener registrar (absent when no
     *                            {@link com.cheetah.racer.annotation.RacerStreamListener}
     *                            methods are defined)
     * @return a configured test harness
     */
    @Bean
    public RacerTestHarness racerTestHarness(
            RacerListenerRegistrar listenerRegistrar,
            Optional<RacerStreamListenerRegistrar> streamRegistrar) {
        log.info("[RACER-TEST] Registering RacerTestHarness.");
        return new RacerTestHarness(listenerRegistrar, streamRegistrar.orElse(null));
    }

    // ── Internal: no-op Mockito answer ────────────────────────────────────────

    /**
     * Mockito {@link Answer} that returns empty reactive publishers for
     * {@link Mono}/{@link Flux}-returning methods, and nested mocks (using the same
     * answer) for interface-returning methods such as {@code opsForList()}.
     *
     * <p>This prevents {@link NullPointerException} in reactive chains that chain
     * multiple operations on the Redis template (e.g.
     * {@code template.opsForList().leftPush(...).subscribe()}).
     */
    private static final class ReactiveEmptyAnswer implements Answer<Object> {

        /**
         * Returns an appropriate empty/stub value for the given invocation's return type.
         *
         * <ul>
         *   <li>{@link Mono} → {@code Mono.empty()}</li>
         *   <li>{@link Flux} → {@code Flux.empty()}</li>
         *   <li>{@code interface} types (e.g. {@code ReactiveListOperations}) → a Mockito
         *       mock of that interface using the same answer recursively</li>
         *   <li>{@link Long}/{@code long} → {@code 0L}</li>
         *   <li>{@link Boolean}/{@code boolean} → {@code false}</li>
         *   <li>Everything else → {@code null}</li>
         * </ul>
         *
         * @param invocation the Mockito invocation
         * @return appropriate stub value for the return type
         */
        @Override
        public Object answer(InvocationOnMock invocation) {
            return forType(invocation.getMethod().getReturnType());
        }

        /**
         * Produces a default stub value for the given {@code returnType}.
         * Extracted as a named method so that nested mock creation can reference
         * {@code this} (the same {@link ReactiveEmptyAnswer} instance) for recursive
         * stubbing.
         *
         * @param returnType the method return type
         * @return appropriate stub value
         */
        private Object forType(Class<?> returnType) {
            // Reactive publishers — return empty, non-erroring publishers
            if (Mono.class.isAssignableFrom(returnType)) return Mono.empty();
            if (Flux.class.isAssignableFrom(returnType)) return Flux.empty();
            // Common primitive wrapper returns
            if (Long.class == returnType || long.class == returnType) return 0L;
            if (Boolean.class == returnType || boolean.class == returnType) return Boolean.FALSE;
            if (String.class == returnType) return "";
            // For Spring Data Redis ops interfaces (ReactiveListOperations, ReactiveValueOperations, etc.)
            // return a nested mock using the same answer so chained calls also return empty publishers.
            if (returnType.isInterface()) {
                return Mockito.mock(returnType, this);
            }
            return null;
        }
    }
}
