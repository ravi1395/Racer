package com.cheetah.racer;

import java.beans.BeanProperty;
import java.beans.JavaBean;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import com.cheetah.racer.receiver.Receiver;
import java.lang.InterruptedException;


@Slf4j
@SpringBootApplication
public class RacerApplication {

	@Bean
	public RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
			MessageListenerAdapter listenerAdapter) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(listenerAdapter, new org.springframework.data.redis.listener.ChannelTopic("chat"));
		return container;
	}	

	@Bean
	public Receiver receiver() {
		return new Receiver();
	}

	@Bean
	public MessageListenerAdapter listenerAdapter(Receiver receiver) {
		return new MessageListenerAdapter(receiver, "receiveMessage");
	}

	@Bean
	public StringRedisTemplate template(RedisConnectionFactory connectionFactory) {
		return new StringRedisTemplate(connectionFactory);
	}
	

	public static void main(String[] args) {
		ApplicationContext context = SpringApplication.run(RacerApplication.class, args);
		Receiver receiver = context.getBean(Receiver.class);
		StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
		try {
					while (receiver.getCount() == 0) {

			log.info("Sending message...");
			template.convertAndSend("chat", "Hello from Redis!");
			Thread.sleep(500L);
		}
		log.info("Message received!");
		System.exit(0);
		} catch (InterruptedException e) {
			log.error("Error occurred: InterruptedException", e);
			System.exit(1);
		}

	}

}
