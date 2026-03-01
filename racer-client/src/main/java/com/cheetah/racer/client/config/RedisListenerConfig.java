package com.cheetah.racer.client.config;

import com.cheetah.racer.common.RedisChannels;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;

/**
 * Configures the reactive Redis message listener container for Pub/Sub subscription.
 */
@Configuration
public class RedisListenerConfig {

    @Bean
    public ReactiveRedisMessageListenerContainer reactiveRedisMessageListenerContainer(
            ReactiveRedisConnectionFactory connectionFactory) {
        ReactiveRedisMessageListenerContainer container = new ReactiveRedisMessageListenerContainer(connectionFactory);
        return container;
    }

    @Bean
    public ChannelTopic messageChannelTopic() {
        return new ChannelTopic(RedisChannels.MESSAGE_CHANNEL);
    }

    @Bean
    public ChannelTopic notificationChannelTopic() {
        return new ChannelTopic(RedisChannels.NOTIFICATION_CHANNEL);
    }
}
