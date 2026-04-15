package io.github.ravi1395.benchmark;

import java.util.Collections;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import com.cheetah.racer.ratelimit.RacerRateLimiter;

/**
 * Measures token-bucket rate-limiter throughput via {@link RacerRateLimiter}.
 *
 * <p>
 * The rate limiter is on the critical publish path when
 * {@code racer.rate-limit.enabled=true}. Each call executes a Lua script inside
 * Redis that atomically inspects and updates the token bucket.
 *
 * <p>
 * <b>Prerequisites:</b> a Redis instance must be running on
 * {@code localhost:6379}.
 *
 * <p>
 * Run with:
 * 
 * <pre>
 *   java -jar racer-benchmark/target/benchmarks.jar RateLimiterBenchmark
 * </pre>
 */
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class RateLimiterBenchmark {

    private RacerRateLimiter limiter;
    private LettuceConnectionFactory connectionFactory;

    /**
     * Initialises a {@link RacerRateLimiter} backed by a live Redis instance.
     * Capacity is set high (100 000) so requests are never rejected during the
     * benchmark, isolating the Lua-script round-trip cost rather than rejection
     * logic.
     */
    @Setup
    public void setup() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();

        ReactiveRedisTemplate<String, String> template = new ReactiveRedisTemplate<>(
                connectionFactory,
                RedisSerializationContext.string());

        limiter = new RacerRateLimiter(
                template,
                /* defaultCapacity */ 100_000L,
                /* defaultRefillRate */ 100_000L,
                /* keyPrefix */ "racer:bench:ratelimit:",
                /* channelOverrides */ Collections.emptyMap());
    }

    /** Closes the Redis connection after all iterations are complete. */
    @TearDown
    public void tearDown() {
        connectionFactory.destroy();
    }

    /**
     * Allowed-request path: checks whether the {@code racer:orders} channel has
     * available tokens. With a capacity of 100 000, every call succeeds.
     */
    @Benchmark
    public void allowedRequest() {
        limiter.checkLimit("racer:orders").block();
    }
}
