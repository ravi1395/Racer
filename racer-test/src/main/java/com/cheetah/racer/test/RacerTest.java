package com.cheetah.racer.test;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composite annotation for Spring Boot integration tests that exercise
 * {@link com.cheetah.racer.annotation.RacerListener} and
 * {@link com.cheetah.racer.annotation.RacerStreamListener} handlers and
 * {@link com.cheetah.racer.publisher.RacerChannelPublisher} publish behaviour
 * without a real Redis connection.
 *
 * <h3>What this annotation activates</h3>
 * <ol>
 *   <li>A full Spring {@link SpringBootTest} application context.</li>
 *   <li>{@link RacerTestAutoConfiguration}, which registers:
 *     <ul>
 *       <li>{@link InMemoryRacerPublisherRegistry} ({@code @Primary}) — captures all
 *           published messages in memory; use
 *           {@link InMemoryRacerPublisherRegistry#getTestPublisher(String)} and
 *           {@link InMemoryRacerPublisher#assertMessageCount(int)} /
 *           {@link InMemoryRacerPublisher#assertPayload(int, Object)} for assertions.</li>
 *       <li>No-op stub for {@link org.springframework.data.redis.core.ReactiveRedisTemplate}
 *           (when no real Redis is available) — returns empty {@link reactor.core.publisher.Mono}/
 *           {@link reactor.core.publisher.Flux} for all operations.</li>
 *       <li>No-op stub for
 *           {@link org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer}
 *           (when no real Redis is available) — subscriptions never emit messages;
 *           inject messages via {@link RacerTestHarness#fireAt} instead.</li>
 *       <li>{@link RacerTestHarness} — for injecting synthetic messages directly into
 *           listener processing pipelines (bypassing Redis transport).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>Spring Boot application class requirement</h3>
 * <p>{@code @SpringBootTest} requires a {@code @SpringBootApplication} class on the
 * classpath (or test classpath) to drive context loading.  Create a minimal one in
 * your test source root:
 * <pre>
 * {@literal @}SpringBootApplication
 * {@literal @}EnableRacer
 * public class TestApplication { }
 * </pre>
 *
 * <h3>Typical usage (no real Redis)</h3>
 * <pre>
 * {@literal @}RacerTest
 * class OrderListenerTest {
 *
 *     {@literal @}Autowired RacerTestHarness harness;
 *     {@literal @}Autowired InMemoryRacerPublisherRegistry registry;
 *
 *     {@literal @}BeforeEach
 *     void reset() { registry.resetAll(); }
 *
 *     {@literal @}Test
 *     void handleOrder_publishesConfirmation() {
 *         RacerMessage msg = RacerMessageBuilder.forChannel("racer:orders")
 *                 .payload(new Order(42, "Widget"))
 *                 .build();
 *
 *         harness.fireAt("orderService.handleOrder", msg).block();
 *
 *         registry.getTestPublisher("confirmations")
 *                 .assertMessageCount(1)
 *                 .assertPayload(0, new OrderConfirmation(42));
 *     }
 * }
 * </pre>
 *
 * <h3>Usage with a real Redis (Testcontainers)</h3>
 * <p>When a real {@link org.springframework.data.redis.core.ReactiveRedisTemplate} and
 * {@link org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer}
 * are present in the context (e.g. provided by Testcontainers), the no-op stubs are
 * automatically skipped (via {@code @ConditionalOnMissingBean}).
 * {@link InMemoryRacerPublisherRegistry} remains active as a {@code @Primary} bean so
 * publish assertions still work without inspecting Redis state.
 *
 * <h3>Excluding Redis auto-configuration</h3>
 * <p>For pure unit-style tests without any Redis, add the following to your test's
 * {@code application.properties} (or override the properties attribute):
 * <pre>
 * spring.autoconfigure.exclude=\
 *   org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,\
 *   org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,\
 *   org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
 * </pre>
 *
 * @see RacerTestAutoConfiguration
 * @see RacerTestHarness
 * @see InMemoryRacerPublisherRegistry
 * @see RacerMessageBuilder
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@ImportAutoConfiguration(RacerTestAutoConfiguration.class)
public @interface RacerTest {

    /**
     * Additional channel aliases to register in the {@link InMemoryRacerPublisherRegistry}
     * beyond those already declared via {@code racer.channels.*} in
     * {@code application.properties}.
     *
     * <p>Each alias is registered with a channel name equal to the alias itself.
     * Useful when a test does not load a real {@code application.properties} or
     * needs channels that are not configured in the main properties.
     *
     * <p>Defaults to an empty array; all channels come from the standard Racer
     * properties configuration in that case.
     *
     * <p><strong>Note:</strong> this attribute is informational metadata — the actual
     * publisher registration uses {@link InMemoryRacerPublisherRegistry#init()}, which
     * reads from {@link com.cheetah.racer.config.RacerProperties}.  To make additional
     * channels available, configure them via {@code application.properties} or inline
     * {@code @TestPropertySource} rather than relying on this attribute alone.
     */
    String[] channels() default {};
}
