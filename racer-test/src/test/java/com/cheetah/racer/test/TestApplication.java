package com.cheetah.racer.test;

import com.cheetah.racer.annotation.EnableRacer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Minimal Spring Boot application used exclusively by the racer-test module's
 * own test suite.
 *
 * <p>Provides the {@code @SpringBootApplication} entry point that {@code @SpringBootTest}
 * requires to bootstrap a full application context. {@link EnableRacer} activates Racer's
 * auto-configuration so that {@link InMemoryRacerPublisherRegistry},
 * {@link RacerTestHarness}, and the no-op Redis stubs from
 * {@link RacerTestAutoConfiguration} are all available.
 *
 * <p>This class is intentionally kept minimal — no {@code main} method, because
 * racer-test is a library, not a runnable application.
 */
@SpringBootApplication
@EnableRacer
public class TestApplication {

    /**
     * Provides a fallback {@link ObjectMapper} for the racer-test module's own test suite.
     *
     * <p>Spring Boot's {@code JacksonAutoConfiguration} requires
     * {@code Jackson2ObjectMapperBuilder} (from {@code spring-web}), which is not on
     * this module's test classpath. Without this bean,
     * {@link com.cheetah.racer.poll.RacerPollRegistrar} — a
     * {@link org.springframework.beans.factory.config.BeanPostProcessor} that Spring
     * creates eagerly — would fail to start because {@code ObjectMapper} cannot be found.
     *
     * @return a Jackson mapper pre-configured with JSR-310 (Java-time) support
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper testObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Provides a no-op {@link MeterRegistry} for the racer-test module's own test suite.
     *
     * <p>{@code micrometer-core} is added as a test-scope dependency (it is
     * {@code optional} in the {@code racer} module and therefore not transitive).
     * Its presence on the classpath causes {@code RacerAutoConfiguration}'s
     * {@code @ConditionalOnClass(MeterRegistry.class)} guard on {@code racerMetrics}
     * to pass, which then requires an actual {@link MeterRegistry} bean. Without one,
     * context startup fails because {@code racerPollRegistrar} — a {@code BeanPostProcessor}
     * Spring creates eagerly — transitively depends on {@code racerMetrics}.
     *
     * @return a simple in-memory registry sufficient for wiring the context
     */
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry testMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}

