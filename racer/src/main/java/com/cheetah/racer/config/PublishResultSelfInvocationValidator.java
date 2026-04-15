package com.cheetah.racer.config;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.lang.NonNull;

import com.cheetah.racer.annotation.PublishResult;
import com.cheetah.racer.annotation.PublishResults;

import lombok.extern.slf4j.Slf4j;

/**
 * CUX-1: Best-effort startup heuristic that warns when a {@code @PublishResult}
 * method exists on a concrete class that does not inject itself, making
 * self-invocation ({@code this.method()}) likely to bypass the AOP proxy.
 *
 * <p>
 * The check is intentionally conservative: it only fires when the class has
 * {@code @PublishResult} methods <b>and</b> has other
 * non-{@code @PublishResult}
 * methods that could potentially call them via {@code this}. This covers the
 * most common failure pattern without producing false positives on
 * single-method
 * beans.
 */
@Slf4j
public class PublishResultSelfInvocationValidator implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) throws BeansException {
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);

        // Skip interfaces, abstract classes, and Spring internals
        if (targetClass.isInterface() || Modifier.isAbstract(targetClass.getModifiers())
                || targetClass.getName().startsWith("org.springframework.")) {
            return bean;
        }

        List<String> publishResultMethods = new ArrayList<>();
        int totalMethods = 0;

        for (Method method : targetClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            totalMethods++;
            if (method.isAnnotationPresent(PublishResult.class)
                    || method.isAnnotationPresent(PublishResults.class)) {
                publishResultMethods.add(method.getName());
            }
        }

        // Only warn when the class has both @PublishResult methods AND other methods
        // that could perform self-invocation (i.e. the class has more methods than
        // just the annotated ones).
        if (!publishResultMethods.isEmpty() && totalMethods > publishResultMethods.size()) {
            // Check if the bean has a self-injection field (heuristic: field of same type)
            boolean hasSelfInjection = false;
            for (var field : targetClass.getDeclaredFields()) {
                if (field.getType().isAssignableFrom(targetClass)) {
                    hasSelfInjection = true;
                    break;
                }
            }
            if (!hasSelfInjection) {
                log.warn("[RACER] Bean '{}' ({}) has @PublishResult on method(s) {} "
                        + "but no self-injection field detected. "
                        + "If any other method in this class calls these methods via this-reference, "
                        + "the AOP proxy will be bypassed and the publish will not fire. "
                        + "See @PublishResult Javadoc for workarounds.",
                        beanName, targetClass.getSimpleName(), publishResultMethods);
            }
        }

        return bean;
    }
}
