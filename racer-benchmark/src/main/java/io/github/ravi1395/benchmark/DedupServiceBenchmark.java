package io.github.ravi1395.benchmark;

import java.util.UUID;
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

import com.cheetah.racer.config.RacerProperties;
import com.cheetah.racer.dedup.RacerDedupService;

/**
 * Measures the per-message overhead of {@link RacerDedupService} backed by
 * Redis.
 *
 * <p>
 * Deduplication is on the critical listen path when
 * {@code racer.dedup.enabled=true}.
 * The implementation uses {@code SET NX EX} (atomic check-and-set with TTL) so
 * every
 * message triggers one Redis round-trip.
 *
 * <p>
 * <b>Prerequisites:</b> a Redis instance must be running on
 * {@code localhost:6379}.
 *
 * <p>
 * Run with:
 * 
 * <pre>
 *   java -jar racer-benchmark/target/benchmarks.jar DedupServiceBenchmark
 * </pre>
 */
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class DedupServiceBenchmark {

    private RacerDedupService dedupService;
    private LettuceConnectionFactory connectionFactory;

    /** A fixed ID used to test the duplicate-detection fast path. */
    private final String knownDuplicateId = "dedup-bench-known-" + UUID.randomUUID();

    /**
     * Initialises a real Redis-backed {@link RacerDedupService} and pre-seeds
     * {@link #knownDuplicateId} so the duplicate path is exercised immediately.
     */
    @Setup
    public void setup() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();

        ReactiveRedisTemplate<String, String> template = new ReactiveRedisTemplate<>(
                connectionFactory,
                RedisSerializationContext.string());

        RacerProperties properties = new RacerProperties();
        dedupService = new RacerDedupService(template, properties);

        // Pre-seed one ID so the duplicate benchmark exercises the rejection path
        dedupService.checkAndMarkProcessed(knownDuplicateId).block();
    }

    /** Closes the Redis connection after all iterations are complete. */
    @TearDown
    public void tearDown() {
        connectionFactory.destroy();
    }

    /**
     * New-message path: the ID is unique on every call so the SET NX succeeds and
     * the message is allowed through (no duplicate).
     *
     * @return {@code true} — message is new (consumed by JMH to prevent
     *         optimisation)
     */
    @Benchmark
    public boolean checkNewMessage() {
        return Boolean.TRUE.equals(
                dedupService.checkAndMarkProcessed(UUID.randomUUID().toString()).block());
    }

    /**
     * Duplicate-message path: the fixed ID was already seeded during setup so the
     * SET NX fails and the message is rejected.
     *
     * @return {@code false} — message is a duplicate (consumed by JMH)
     */
    @Benchmark
    public boolean checkDuplicateMessage() {
        return Boolean.TRUE.equals(
                dedupService.checkAndMarkProcessed(knownDuplicateId).block());
    }
}
