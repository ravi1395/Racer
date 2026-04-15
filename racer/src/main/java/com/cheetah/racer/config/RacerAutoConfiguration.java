package com.cheetah.racer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;

import com.cheetah.racer.publisher.IdGenerator;
import com.cheetah.racer.publisher.MessageEnvelopeBuilder;

/**
 * Core Racer auto-configuration entry point.
 *
 * <p>
 * Imported by {@link com.cheetah.racer.annotation.EnableRacer} and by
 * Spring Boot via {@code META-INF/spring/*.AutoConfiguration.imports}.
 * Delegates all feature-specific beans to five focused configuration classes:
 * <ul>
 * <li>{@link RacerObservabilityAutoConfiguration} — Micrometer metrics,
 * tracing, DLQ gauge</li>
 * <li>{@link RacerPublishingAutoConfiguration} — publishers, router, schema,
 * rate limiting, polling</li>
 * <li>{@link RacerListenerAutoConfiguration} — thread pool, Pub/Sub registrar,
 * DLQ, retention</li>
 * <li>{@link RacerStreamAutoConfiguration} — Streams consumer groups, lag
 * monitoring</li>
 * <li>{@link RacerResilienceAutoConfiguration} — circuit breaker, dedup,
 * back-pressure</li>
 * </ul>
 */
@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(RacerProperties.class)
@Import({
                RacerObservabilityAutoConfiguration.class,
                RacerPublishingAutoConfiguration.class,
                RacerListenerAutoConfiguration.class,
                RacerStreamAutoConfiguration.class,
                RacerResilienceAutoConfiguration.class
})
public class RacerAutoConfiguration {

        /**
         * Validates numeric configuration properties at startup and throws
         * {@link IllegalStateException} on invalid values.
         */
        @Bean
        public Object racerPropertiesValidator(RacerProperties props) {
                // ── Circuit breaker ──────────────────────────────────────────────
                RacerProperties.CircuitBreakerProperties cb = props.getCircuitBreaker();
                if (cb.isEnabled()) {
                        check(cb.getFailureRateThreshold() >= 1 && cb.getFailureRateThreshold() <= 100,
                                        "racer.circuit-breaker.failure-rate-threshold must be between 1 and 100, got "
                                                        + cb.getFailureRateThreshold());
                        check(cb.getSlidingWindowSize() >= 1,
                                        "racer.circuit-breaker.sliding-window-size must be >= 1, got "
                                                        + cb.getSlidingWindowSize());
                        check(cb.getWaitDurationInOpenStateSeconds() >= 1,
                                        "racer.circuit-breaker.wait-duration-in-open-state-seconds must be >= 1, got "
                                                        + cb.getWaitDurationInOpenStateSeconds());
                        check(cb.getPermittedCallsInHalfOpenState() >= 1,
                                        "racer.circuit-breaker.permitted-calls-in-half-open-state must be >= 1, got "
                                                        + cb.getPermittedCallsInHalfOpenState());
                }
                // ── Dedup ────────────────────────────────────────────────────────
                RacerProperties.DedupProperties dd = props.getDedup();
                if (dd.isEnabled()) {
                        check(dd.getTtlSeconds() >= 1,
                                        "racer.dedup.ttl-seconds must be >= 1, got " + dd.getTtlSeconds());
                        check(dd.getKeyPrefix() != null && !dd.getKeyPrefix().isBlank(),
                                        "racer.dedup.key-prefix must not be blank");
                }
                // ── DLQ ──────────────────────────────────────────────────────────
                check(props.getDlq().getMaxSize() >= 1,
                                "racer.dlq.max-size must be >= 1, got " + props.getDlq().getMaxSize());
                // ── Rate Limit (4.3) ─────────────────────────────────────────────
                RacerProperties.RateLimitProperties rl = props.getRateLimit();
                if (rl.isEnabled()) {
                        check(rl.getDefaultCapacity() >= 1,
                                        "racer.rate-limit.default-capacity must be >= 1, got "
                                                        + rl.getDefaultCapacity());
                        check(rl.getDefaultRefillRate() >= 1,
                                        "racer.rate-limit.default-refill-rate must be >= 1, got "
                                                        + rl.getDefaultRefillRate());
                        check(rl.getKeyPrefix() != null && !rl.getKeyPrefix().isBlank(),
                                        "racer.rate-limit.key-prefix must not be blank");
                }
                return new Object(); // sentinel bean
        }

        private static void check(boolean condition, String message) {
                if (!condition) {
                        throw new IllegalStateException("[racer] Invalid configuration: " + message);
                }
        }

        /**
         * Creates the {@link IdGenerator} bean based on {@code racer.id-strategy}.
         *
         * <p>
         * Currently supports {@code "uuid"} (default), which uses a
         * {@code ThreadLocalRandom}-backed UUID generator (~5-10x faster than
         * {@code SecureRandom} under high concurrency while preserving the standard
         * UUID wire format). The selected generator is immediately wired into
         * {@link MessageEnvelopeBuilder} via its static setter so all publish paths
         * benefit without requiring call-site changes.
         */
        @Bean
        public IdGenerator racerIdGenerator(RacerProperties racerProperties) {
                // Only "uuid" (Option A) is implemented; property is reserved for future
                // strategies.
                // The generator is wired into MessageEnvelopeBuilder so all static publish
                // paths
                // use it without requiring call-site changes.
                IdGenerator generator = MessageEnvelopeBuilder.fastUuidGenerator();
                MessageEnvelopeBuilder.setIdGenerator(generator);
                return generator;
        }

}
