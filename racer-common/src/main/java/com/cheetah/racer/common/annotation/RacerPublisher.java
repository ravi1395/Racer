package com.cheetah.racer.common.annotation;

import java.lang.annotation.*;

/**
 * Injects a {@link com.cheetah.racer.common.publisher.RacerChannelPublisher}
 * configured for the named channel alias defined in {@code application.properties}.
 *
 * <h3>Usage</h3>
 * <pre>
 * // application.properties:
 * racer.channels.orders.name=racer:orders
 * racer.channels.orders.async=true
 *
 * // Service:
 * {@literal @}Service
 * public class OrderService {
 *
 *     {@literal @}RacerPublisher("orders")
 *     private RacerChannelPublisher ordersPublisher;
 *
 *     public void placeOrder(Order order) {
 *         ordersPublisher.publishAsync(order).subscribe();
 *     }
 * }
 * </pre>
 *
 * <p>If the value is empty (default), the default channel
 * ({@code racer.default-channel}) is used.
 *
 * <p>Requires {@link EnableRacer} to be active.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RacerPublisher {

    /**
     * The channel alias as defined under {@code racer.channels.<alias>}.
     * Leave empty to use the default channel ({@code racer.default-channel}).
     */
    String value() default "";
}
