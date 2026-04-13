package com.cheetah.racer.processor;

import com.cheetah.racer.annotation.RacerPublisher;
import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.exception.RacerConfigurationException;
import com.cheetah.racer.publisher.RacerChannelPublisher;
import com.cheetah.racer.publisher.RacerPublisherRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.util.ReflectionUtils;

/**
 * Spring {@link BeanPostProcessor} that injects a {@link RacerChannelPublisher}
 * into every field annotated with {@link RacerPublisher}.
 *
 * <p>The registry is resolved lazily from the {@link ApplicationContext} to avoid
 * circular initialization issues between {@code BeanPostProcessor} beans and
 * regular beans.
 *
 * <p>Registered automatically by {@link com.cheetah.racer.config.RacerAutoConfiguration}.
 */
@Slf4j
public class RacerPublisherFieldProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext ctx) throws BeansException {
        this.applicationContext = ctx;
    }

    @Override
    public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        ReflectionUtils.doWithFields(
                bean.getClass(),
                field -> {
                    RacerPublisher annotation = field.getAnnotation(RacerPublisher.class);
                    if (annotation == null) {
                        return;
                    }
                    if (!RacerChannelPublisher.class.isAssignableFrom(field.getType())) {
                        log.warn("[racer] @RacerPublisher on field '{}' requires type RacerChannelPublisher — skipping.",
                                field.getName());
                        return;
                    }
                    String alias = annotation.value();
                    RacerPublisherRegistry registry = applicationContext.getBean(RacerPublisherRegistry.class);

                    // In strict mode, validate the alias eagerly here so the error message
                    // includes the declaring class and field name for fast diagnosis.
                    if (!alias.isBlank()) {
                        RacerProperties props = applicationContext.getBean(RacerProperties.class);
                        if (props.isStrictChannelValidation()
                                && !props.getChannels().containsKey(alias)) {
                            throw new RacerConfigurationException(
                                    "@RacerPublisher(\"" + alias + "\") on "
                                    + bean.getClass().getSimpleName() + "." + field.getName()
                                    + " — alias not found in racer.channels.*. "
                                    + "Defined aliases: " + props.getChannels().keySet() + ".");
                        }
                    }

                    RacerChannelPublisher publisher = registry.getPublisher(alias);

                    field.setAccessible(true);
                    try {
                        field.set(bean, publisher);
                        log.debug("[racer] Injected RacerChannelPublisher alias='{}' channel='{}' into {}.{}",
                                alias.isBlank() ? "__default__" : alias,
                                publisher.getChannelName(),
                                bean.getClass().getSimpleName(),
                                field.getName());
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException("Failed to inject @RacerPublisher into " + field, e);
                    }
                },
                field -> field.isAnnotationPresent(RacerPublisher.class));

        return bean;
    }
}
