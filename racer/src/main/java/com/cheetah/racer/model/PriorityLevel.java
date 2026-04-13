package com.cheetah.racer.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.extern.slf4j.Slf4j;

/**
 * Built-in message priority levels used by the Racer priority-channel feature (R-10).
 *
 * <p>The numeric {@link #weight} determines processing order: lower weight = higher priority.
 * Consumer implementations poll channels in ascending weight order.
 *
 * <h3>Sentinel value</h3>
 * {@link #NONE} is an annotation-default sentinel meaning "priority not explicitly specified".
 * It must never appear on the wire — {@link #fromString(String)} maps any {@code "NONE"} input
 * back to {@link #NORMAL}.
 *
 * <h3>Custom levels</h3>
 * These constants represent the default three-tier hierarchy.
 * Additional levels can be declared via {@code racer.priority.levels} configuration;
 * they are resolved by name at runtime.
 */
@Slf4j
public enum PriorityLevel {

    /**
     * Sentinel used as the default value for annotation attributes (e.g. {@code @PublishResult}).
     * Indicates that no explicit priority has been set. Never serialised to the wire.
     */
    NONE(Integer.MAX_VALUE),
    HIGH(0),
    NORMAL(1),
    LOW(2);

    /** Lower weight = processed first. */
    private final int weight;

    PriorityLevel(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }

    /**
     * Jackson-compatible deserialiser: maps a JSON string to a {@link PriorityLevel}.
     * Unknown values and the {@code "NONE"} sentinel both resolve to {@link #NORMAL}.
     *
     * @param name the string value from JSON
     * @return resolved {@link PriorityLevel}; never {@code null}
     */
    @JsonCreator
    public static PriorityLevel fromString(String name) {
        if (name == null || name.isBlank()) {
            return NORMAL;
        }
        String upper = name.trim().toUpperCase();
        // Prevent the annotation sentinel leaking onto the wire
        if ("NONE".equals(upper)) {
            return NORMAL;
        }
        try {
            return PriorityLevel.valueOf(upper);
        } catch (IllegalArgumentException ex) {
            log.warn("[racer] Unknown priority '{}' — defaulting to NORMAL", name);
            return NORMAL;
        }
    }

    /**
     * Resolves a priority level by name (case-insensitive), defaulting to {@link #NORMAL}
     * when the name cannot be matched.
     *
     * @deprecated Use {@link #fromString(String)} instead.
     */
    @Deprecated
    public static PriorityLevel of(String name) {
        return fromString(name);
    }
}
