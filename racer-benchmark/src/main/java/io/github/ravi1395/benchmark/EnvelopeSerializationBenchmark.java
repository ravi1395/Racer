package io.github.ravi1395.benchmark;

import java.time.Instant;
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
import org.openjdk.jmh.annotations.Warmup;

import com.cheetah.racer.model.RacerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Measures serialization and deserialization throughput for
 * {@link RacerMessage}.
 *
 * <p>
 * Racer wraps every payload in a {@code RacerMessage} envelope before
 * publishing.
 * This benchmark quantifies the raw ObjectMapper cost for that envelope, which
 * is
 * on the critical path for every message both published and consumed.
 *
 * <p>
 * Run with:
 * 
 * <pre>
 *   mvn -pl racer-benchmark clean package
 *   java -jar racer-benchmark/target/benchmarks.jar EnvelopeSerializationBenchmark
 * </pre>
 */
@Fork(1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class EnvelopeSerializationBenchmark {

    private ObjectMapper objectMapper;
    private RacerMessage message;
    private String serialized;

    /**
     * Builds a reusable {@link RacerMessage} and pre-serializes it for the
     * deserialization benchmark.
     */
    @Setup
    public void setup() throws Exception {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();

        message = RacerMessage.builder()
                .id(UUID.randomUUID().toString())
                .channel("racer:orders")
                .sender("order-service")
                .payload("{\"order_id\": 123, \"amount\": 99.99}")
                .timestamp(Instant.now())
                .build();

        serialized = objectMapper.writeValueAsString(message);
    }

    /**
     * Serializes a {@link RacerMessage} envelope to JSON.
     *
     * @return serialized JSON string (consumed by JMH to prevent dead-code
     *         elimination)
     * @throws Exception on ObjectMapper error
     */
    @Benchmark
    public String serialize() throws Exception {
        return objectMapper.writeValueAsString(message);
    }

    /**
     * Deserializes a JSON string back into a {@link RacerMessage} envelope.
     *
     * @return deserialized message (consumed by JMH to prevent dead-code
     *         elimination)
     * @throws Exception on ObjectMapper error
     */
    @Benchmark
    public RacerMessage deserialize() throws Exception {
        return objectMapper.readValue(serialized, RacerMessage.class);
    }
}
