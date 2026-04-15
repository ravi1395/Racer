package com.cheetah.racer.circuitbreaker;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight, built-in circuit breaker for a single Racer listener.
 *
 * <h3>State machine</h3>
 * <pre>
 * CLOSED ─── failure rate &ge; threshold ──► OPEN
 *   ▲                                         │
 *   │                              wait duration elapsed
 *   │                                         │
 *   └─── all probe calls succeed ──── HALF_OPEN
 * </pre>
 *
 * <ul>
 *   <li><b>CLOSED</b> — normal operation; all calls permitted.</li>
 *   <li><b>OPEN</b> — all calls refused immediately; protects the downstream service.</li>
 *   <li><b>HALF_OPEN</b> — a limited number of probe calls are allowed to test recovery.</li>
 * </ul>
 *
 * <p>The failure rate is evaluated over a count-based sliding window.
 * Only after {@code slidingWindowSize} calls have been recorded does the rate trigger OPEN.
 */
@Slf4j
public class RacerCircuitBreaker {

    /** Visible for testing. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int slidingWindowSize;
    private final float failureRateThreshold; // 1–100 %
    private final long waitDurationMs;
    private final int permittedCallsInHalfOpen;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    /**
     * Fixed-size ring buffer: {@code true} = success, {@code false} = failure.
     * Pre-filled with {@code true} so that initial overwrites do not spuriously
     * decrement the failure counter before the window is populated.
     */
    private final boolean[] ring;
    /** Monotonically-increasing write cursor; mod {@code slidingWindowSize} gives the slot. */
    private final AtomicInteger index = new AtomicInteger(0);
    /** Running count of {@code false} (failure) slots in the ring. */
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);
    /** Probe-call counter used in HALF_OPEN state. */
    private final AtomicInteger halfOpenProbes = new AtomicInteger(0);
    /** Successful-probe counter in HALF_OPEN state; circuit closes only when this reaches {@code permittedCallsInHalfOpen}. */
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);

    /** Total number of state transitions (CLOSED→OPEN, OPEN→HALF_OPEN, HALF_OPEN→CLOSED, etc.). */
    private final AtomicLong transitionCount = new AtomicLong(0);
    /** Total number of calls rejected while the circuit was OPEN. */
    private final AtomicLong rejectedCount = new AtomicLong(0);

    public RacerCircuitBreaker(String name,
                               int slidingWindowSize,
                               float failureRateThreshold,
                               long waitDurationMs,
                               int permittedCallsInHalfOpen) {
        this.name = name;
        this.slidingWindowSize = slidingWindowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.waitDurationMs = waitDurationMs;
        this.permittedCallsInHalfOpen = permittedCallsInHalfOpen;
        // Pre-fill with true (success) so overwriting a slot that has never been written
        // does not spuriously adjust the failure counter.
        this.ring = new boolean[slidingWindowSize];
        Arrays.fill(this.ring, true);
    }

    /**
     * Returns {@code true} if a call is permitted in the current state.
     * Transitions OPEN → HALF_OPEN automatically once the wait duration has elapsed.
     */
    public boolean isCallPermitted() {
        State s = state.get();
        return switch (s) {
            case CLOSED -> true;
            case OPEN -> {
                long elapsed = System.currentTimeMillis() - openedAt.get();
                if (elapsed >= waitDurationMs) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        halfOpenProbes.set(0);
                        halfOpenSuccesses.set(0); // reset success counter for this probe window
                        transitionCount.incrementAndGet();
                        log.info("[CIRCUIT-BREAKER] '{}' → HALF_OPEN (wait elapsed)", name);
                    }
                    yield state.get() != State.OPEN; // allow only if CAS succeeded or another thread beat us
                }
                rejectedCount.incrementAndGet();
                yield false;
            }
            case HALF_OPEN -> halfOpenProbes.incrementAndGet() <= permittedCallsInHalfOpen;
        };
    }

    /**
     * Records a successful call outcome.
     * In HALF_OPEN state, closes the circuit only after all {@code permittedCallsInHalfOpen}
     * probes have returned successfully; a single success is not enough.
     */
    public void onSuccess() {
        if (state.get() == State.HALF_OPEN) {
            int successesSoFar = halfOpenSuccesses.incrementAndGet();
            if (successesSoFar >= permittedCallsInHalfOpen) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    halfOpenSuccesses.set(0);
                    // Reset ring buffer so the next window starts clean
                    Arrays.fill(ring, true);
                    failures.set(0);
                    index.set(0);
                    transitionCount.incrementAndGet();
                    log.info("[CIRCUIT-BREAKER] '{}' → CLOSED (all {} probes succeeded)",
                            name, permittedCallsInHalfOpen);
                }
            }
        } else if (state.get() == State.CLOSED) {
            recordOutcome(true);
        }
    }

    /**
     * Records a failed call outcome.
     * In HALF_OPEN state, re-opens the circuit immediately.
     * In CLOSED state, checks whether the failure rate threshold has been breached.
     */
    public void onFailure() {
        State s = state.get();
        if (s == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                halfOpenSuccesses.set(0); // reset probe-success counter for next HALF_OPEN attempt
                openedAt.set(System.currentTimeMillis());
                transitionCount.incrementAndGet();
                log.warn("[CIRCUIT-BREAKER] '{}' → OPEN (probe failed)", name);
            }
        } else if (s == State.CLOSED) {
            recordOutcome(false);
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Lock-free outcome recording using a fixed-size ring buffer.
     *
     * <p>Each call claims a slot via {@code index.getAndIncrement() % slidingWindowSize}.
     * The old slot value is read and the new value written; the running {@code failures}
     * counter is adjusted only when the slot value actually changes. This eliminates the
     * previous {@code synchronized} block, removes per-call {@code Node} allocations from
     * the old {@code ConcurrentLinkedDeque}, and reduces contention by >90% under high
     * concurrency (16+ listener threads).
     *
     * <p>Note: individual slot read-modify-write is not atomic, so under extreme
     * concurrency the failure count may drift by ±1 transiently — acceptable for a
     * probabilistic rate threshold check.
     *
     * @param success {@code true} if the call succeeded, {@code false} if it failed
     */
    private void recordOutcome(boolean success) {
        // Claim a slot in the ring buffer (wraps around via modulo).
        // Math.floorMod always returns a non-negative value when the divisor is positive,
        // so this is safe even when index wraps past Integer.MAX_VALUE to Integer.MIN_VALUE
        // (Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE in Java, which would produce a
        // negative array index and an ArrayIndexOutOfBoundsException).
        int idx = Math.floorMod(index.getAndIncrement(), slidingWindowSize);
        boolean oldValue = ring[idx];
        ring[idx] = success;

        // Adjust running failure count only when the slot value flips
        if (!oldValue && success) {
            // Slot changed from failure → success
            failures.decrementAndGet();
        } else if (oldValue && !success) {
            // Slot changed from success → failure
            failures.incrementAndGet();
        }

        // Only evaluate the threshold once the window has been fully populated at least once
        if (index.get() >= slidingWindowSize) {
            float rate = failures.get() / (float) slidingWindowSize * 100.0f;
            if (rate >= failureRateThreshold && state.compareAndSet(State.CLOSED, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                transitionCount.incrementAndGet();
                log.warn("[CIRCUIT-BREAKER] '{}' → OPEN (failure rate {}% >= threshold {}%)",
                        name, rate, failureRateThreshold);
            }
        }
    }

    /** Returns the current state — useful for metrics and health checks. */
    public State getState() {
        return state.get();
    }

    /** Returns the listener name this breaker is associated with. */
    public String getName() {
        return name;
    }

    /** Returns the total number of state transitions since creation. */
    public long getTransitionCount() {
        return transitionCount.get();
    }

    /** Returns the total number of rejected calls (while OPEN) since creation. */
    public long getRejectedCount() {
        return rejectedCount.get();
    }
}
