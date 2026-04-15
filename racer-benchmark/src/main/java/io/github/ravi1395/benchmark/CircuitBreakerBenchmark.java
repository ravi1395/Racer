package io.github.ravi1395.benchmark;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import com.cheetah.racer.circuitbreaker.RacerCircuitBreaker;

/**
 * Measures the per-call overhead of the built-in {@link RacerCircuitBreaker}.
 *
 * <p>
 * The circuit breaker is consulted for every dispatched message. This benchmark
 * isolates the cost of the three hot operations:
 * <ul>
 * <li>{@link RacerCircuitBreaker#isCallPermitted()} — gate check on the hot
 * path</li>
 * <li>{@link RacerCircuitBreaker#onSuccess()} — recorded after each successful
 * invocation</li>
 * <li>{@link RacerCircuitBreaker#onFailure()} — recorded on listener
 * exception</li>
 * </ul>
 *
 * <p>
 * Run with:
 * 
 * <pre>
 *   java -jar racer-benchmark/target/benchmarks.jar CircuitBreakerBenchmark
 * </pre>
 */
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class CircuitBreakerBenchmark {

    private RacerCircuitBreaker breaker;

    /**
     * Creates a circuit breaker in CLOSED state with a 10-call sliding window
     * and 50% failure threshold — representative of a production configuration.
     */
    @Setup
    public void setup() {
        breaker = new RacerCircuitBreaker(
                "benchmark-cb",
                /* slidingWindowSize */ 10,
                /* failureRateThreshold (%) */ 50.0f,
                /* waitDurationMs */ 5000L,
                /* permittedCallsInHalfOpen */ 3);
    }

    /**
     * Gate-check cost: called before every message dispatch to decide whether
     * the circuit is closed (permit) or open (reject without invocation).
     *
     * @return permit decision (consumed by JMH)
     */
    @Benchmark
    public boolean isCallPermitted() {
        return breaker.isCallPermitted();
    }

    /**
     * Success-recording cost: called after each successful listener invocation
     * to update the sliding-window ring buffer.
     */
    @Benchmark
    public void recordSuccess() {
        breaker.onSuccess();
    }

    /**
     * Failure-recording cost: called when a listener throws an exception,
     * potentially causing a CLOSED→OPEN state transition.
     */
    @Benchmark
    public void recordFailure() {
        breaker.onFailure();
    }
}
