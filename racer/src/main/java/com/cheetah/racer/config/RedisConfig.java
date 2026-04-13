package com.cheetah.racer.config;

import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Shared Redis configuration providing reactive templates with JSON serialization.
 */
@Configuration
public class RedisConfig {

    /**
     * Fall-back {@link ObjectMapper} used only when no other ObjectMapper bean is
     * present in the application context (e.g. when Spring Boot's
     * {@code JacksonAutoConfiguration} is not active). In a normal Spring Boot
     * application the auto-configured mapper takes precedence.
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    /**
     * Primary {@link ReactiveRedisTemplate} used throughout the Racer library for all
     * Redis Pub/Sub and key-value operations.
     *
     * <p>{@code @ConditionalOnMissingBean} allows the {@code racer-test} module to
     * register a no-op stub bean first (under the same name), preventing this method
     * from attempting to acquire a {@link ReactiveRedisConnectionFactory} in test
     * environments that have no real Redis configured.
     *
     * <p>{@code @ConditionalOnBean(ReactiveRedisConnectionFactory.class)} ensures this
     * bean is only created when a connection factory is actually available — it is skipped
     * gracefully when Redis auto-configuration is excluded (e.g. in unit tests).
     */
    @Bean
    @ConditionalOnMissingBean(name = "reactiveStringRedisTemplate")
    @ConditionalOnBean(ReactiveRedisConnectionFactory.class)
    public ReactiveRedisTemplate<String, String> reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveRedisTemplate<>(connectionFactory,
                RedisSerializationContext.string());
    }

    /**
     * Secondary template used for typed {@link RacerMessage} serialization (e.g. in
     * health checks and DLQ introspection utilities).
     *
     * <p>{@code @ConditionalOnBean(ReactiveRedisConnectionFactory.class)} guards creation
     * so the bean is skipped gracefully when Redis auto-configuration is excluded in tests.
     */
    @Bean
    @ConditionalOnBean(ReactiveRedisConnectionFactory.class)
    public ReactiveRedisTemplate<String, RacerMessage> reactiveRacerMessageRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {

        Jackson2JsonRedisSerializer<RacerMessage> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, RacerMessage.class);

        RedisSerializationContext<String, RacerMessage> context =
                RedisSerializationContext.<String, RacerMessage>newSerializationContext(new StringRedisSerializer())
                        .value(serializer)
                        .hashValue(serializer)
                        .build();

        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }
}
