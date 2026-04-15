package io.github.ravi1395.benchmark;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Measures the end-to-end latency of the Racer publish/subscribe pipeline.
 *
 * <p>
 * This benchmark publishes a {@link RacerMessage} to a Redis Pub/Sub channel
 * and
 * measures the time until the subscription callback fires, capturing both the
 * serialization cost and the Redis network round-trip.
 *
 * <p>
 * <b>Prerequisites:</b> a Redis instance must be running on
 * {@code localhost:6379}.
 *
 * <p>
 * Run with:
 * 
 * <pre>
 *   java -jar racer-benchmark/target/benchmarks.jar EndToEndLatencyBenchmark
 * </pre>
 */
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode({ Mode.AverageTime, Mode.SampleTime })
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class EndToEndLatencyBenchmark {

    private static final String BENCH_CHANNEL = "racer:bench:e2e";

    private ReactiveStringRedisTemplate publishTemplate;
    private ReactiveStringRedisTemplate subscribeTemplate;
    private LettuceConnectionFactory publishFactory;
    private LettuceConnectionFactory subscribeFactory;
    private ObjectMapper objectMapper;
    private BlockingQueue<Long> receivedAt;
    private String payload;

    /**
     * Starts two distinct Redis connections (one for publishing, one for
     * subscribing),
     * registers the subscription, and pre-builds the benchmark payload.
     */
    @Setup(Level.Trial)
    public void setup() throws Exception {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();

        publishFactory = new LettuceConnectionFactory("localhost", 6379);
        publishFactory.afterPropertiesSet();
        publishTemplate = new ReactiveStringRedisTemplate(publishFactory);

        subscribeFactory = new LettuceConnectionFactory("localhost", 6379);
        subscribeFactory.afterPropertiesSet();
        subscribeTemplate = new ReactiveStringRedisTemplate(subscribeFactory);

        receivedAt = new ArrayBlockingQueue<>(1);

        // Subscribe on a separate connection; capture arrival timestamp
        subscribeTemplate.listenToChannel(BENCH_CHANNEL)
                .subscribe(msg -> receivedAt.offer(System.nanoTime()));

        // Build the envelope JSON once to avoid allocations in the measured loop
        RacerMessage message = RacerMessage.builder()
                .id(UUID.randomUUID().toString())
                .channel(BENCH_CHANNEL)
                .sender("benchmark")
                .payload("{\"order_id\": 1}")
                .timestamp(Instant.now())
                .build();
        payload = objectMapper.writeValueAsString(message);
    }

    /**
     * Publishes one envelope to the benchmark channel and waits up to 5 seconds
     * for the subscription callback to fire.
     *
     * @return round-trip latency in nanoseconds (consumed by JMH)
     * @throws InterruptedException if the receive wait is interrupted
     */
    @Benchmark
    public long publishAndReceiveLatency() throws InterruptedException {
        receivedAt.clear();
        long publishedAt = System.nanoTime();
        publishTemplate.convertAndSend(BENCH_CHANNEL, payload).block();
        Long arrival = receivedAt.poll(5, TimeUnit.SECONDS);
        return arrival != null ? arrival - publishedAt : -1L;
    }
}
