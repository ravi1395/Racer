package com.cheetah.racer.common.annotation;

import com.cheetah.racer.common.config.RacerAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Activates the Racer annotation-driven messaging infrastructure.
 *
 * <p>Place on any {@code @SpringBootApplication} or {@code @Configuration} class:
 * <pre>
 * {@literal @}SpringBootApplication
 * {@literal @}EnableRacer
 * public class MyApp { ... }
 * </pre>
 *
 * <p>This enables:
 * <ul>
 *   <li>{@link RacerPublisher} field injection</li>
 *   <li>{@link PublishResult} method interception</li>
 *   <li>Multi-channel support via {@code racer.channels.*} properties</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(RacerAutoConfiguration.class)
public @interface EnableRacer {
}
