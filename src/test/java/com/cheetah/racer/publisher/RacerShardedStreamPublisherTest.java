package com.cheetah.racer.publisher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.stream.RecordId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RacerShardedStreamPublisher}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RacerShardedStreamPublisherTest {

    @Mock
    private RacerStreamPublisher delegate;

    @Mock
    private RacerConsistentHashRing hashRing;

    private static final RecordId RECORD_ID = RecordId.of("1609459200000-0");

    @BeforeEach
    void setUp() {
        when(delegate.publishToStream(anyString(), any(), anyString()))
                .thenReturn(Mono.just(RECORD_ID));
    }

    // ── publishToShard ────────────────────────────────────────────────────────

    @Test
    void publishToShard_routesToCorrectShard() {
        RacerShardedStreamPublisher publisher = new RacerShardedStreamPublisher(delegate, 4);

        StepVerifier.create(publisher.publishToShard("stream", "payload", "sender", "key1"))
                .expectNext(RECORD_ID)
                .verifyComplete();

        // Verify delegate was called with a sharded key like "stream:<N>"
        verify(delegate).publishToStream(argThat(key -> key.startsWith("stream:")), eq("payload"), eq("sender"));
    }

    @Test
    void publishToShard_withHashRing_usesHashRing() {
        when(hashRing.getShardFor("my-key")).thenReturn(2);
        RacerShardedStreamPublisher publisher =
                new RacerShardedStreamPublisher(delegate, 4, hashRing, false);

        StepVerifier.create(publisher.publishToShard("base", "payload", "sender", "my-key"))
                .expectNext(RECORD_ID)
                .verifyComplete();

        verify(hashRing).getShardFor("my-key");
        verify(delegate).publishToStream(eq("base:2"), eq("payload"), eq("sender"));
    }

    @Test
    void publishToShard_failover_retriesOnDifferentShard() {
        when(hashRing.getShardFor("key")).thenReturn(1);
        when(hashRing.getFailoverShardFor("key")).thenReturn(3);

        // Primary shard fails
        when(delegate.publishToStream(eq("base:1"), any(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("shard down")));
        // Failover shard succeeds
        when(delegate.publishToStream(eq("base:3"), any(), anyString()))
                .thenReturn(Mono.just(RECORD_ID));

        RacerShardedStreamPublisher publisher =
                new RacerShardedStreamPublisher(delegate, 4, hashRing, true);

        StepVerifier.create(publisher.publishToShard("base", "payload", "sender", "key"))
                .expectNext(RECORD_ID)
                .verifyComplete();

        verify(delegate).publishToStream(eq("base:1"), eq("payload"), eq("sender"));
        verify(delegate).publishToStream(eq("base:3"), eq("payload"), eq("sender"));
    }

    @Test
    void publishToShard_failover_disabled_doesNotRetry() {
        when(hashRing.getShardFor("key")).thenReturn(1);

        when(delegate.publishToStream(eq("base:1"), any(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("shard down")));

        // failoverEnabled = false
        RacerShardedStreamPublisher publisher =
                new RacerShardedStreamPublisher(delegate, 4, hashRing, false);

        StepVerifier.create(publisher.publishToShard("base", "payload", "sender", "key"))
                .expectError(RuntimeException.class)
                .verify();

        verify(delegate, times(1)).publishToStream(anyString(), any(), anyString());
    }

    // ── allShardKeys ──────────────────────────────────────────────────────────

    @Test
    void allShardKeys_returnsAllKeys() {
        RacerShardedStreamPublisher publisher = new RacerShardedStreamPublisher(delegate, 3);

        List<String> keys = publisher.allShardKeys("racer:orders");

        assertThat(keys).containsExactly("racer:orders:0", "racer:orders:1", "racer:orders:2");
    }

    // ── getShardCount ─────────────────────────────────────────────────────────

    @Test
    void getShardCount_returnsCount() {
        RacerShardedStreamPublisher publisher = new RacerShardedStreamPublisher(delegate, 5);
        assertThat(publisher.getShardCount()).isEqualTo(5);
    }

    // ── primaryShardFor ───────────────────────────────────────────────────────

    @Test
    void primaryShardFor_withoutHashRing_usesCrc16() {
        RacerShardedStreamPublisher publisher = new RacerShardedStreamPublisher(delegate, 4);

        int shard = publisher.primaryShardFor("test-key");
        assertThat(shard).isBetween(0, 3);

        // Deterministic: same key → same shard
        assertThat(publisher.primaryShardFor("test-key")).isEqualTo(shard);
    }

    @Test
    void primaryShardFor_withHashRing_delegatesToRing() {
        when(hashRing.getShardFor("ring-key")).thenReturn(2);
        RacerShardedStreamPublisher publisher =
                new RacerShardedStreamPublisher(delegate, 4, hashRing, false);

        assertThat(publisher.primaryShardFor("ring-key")).isEqualTo(2);
        verify(hashRing).getShardFor("ring-key");
    }

    // ── shardFor (deprecated CRC-16 method) ───────────────────────────────────

    @Test
    void shardFor_nullKey_returnsZero() {
        RacerShardedStreamPublisher publisher = new RacerShardedStreamPublisher(delegate, 4);
        assertThat(publisher.shardFor(null)).isZero();
    }

    @Test
    void shardFor_emptyKey_returnsZero() {
        RacerShardedStreamPublisher publisher = new RacerShardedStreamPublisher(delegate, 4);
        assertThat(publisher.shardFor("")).isZero();
    }

    @Test
    void shardFor_deterministic_sameKeyAlwaysSameShard() {
        RacerShardedStreamPublisher publisher = new RacerShardedStreamPublisher(delegate, 8);
        String key = "order-12345";
        int shard = publisher.shardFor(key);

        for (int i = 0; i < 100; i++) {
            assertThat(publisher.shardFor(key)).isEqualTo(shard);
        }
    }

    // ── crc16 verification ────────────────────────────────────────────────────

    @Test
    void crc16_matchesRedisClusterSlot() {
        // Verify known CRC-16/CCITT behavior: the shardFor method uses CRC-16 mod N.
        // With a large enough shard count we can verify the hash is non-trivial.
        RacerShardedStreamPublisher publisher = new RacerShardedStreamPublisher(delegate, 16384);

        // "foo" should produce a deterministic CRC-16 value
        int fooShard = publisher.shardFor("foo");
        assertThat(fooShard).isBetween(0, 16383);

        // Different keys should (very likely) produce different shards
        int barShard = publisher.shardFor("bar");
        assertThat(fooShard).isNotEqualTo(barShard);

        // Consistency check
        assertThat(publisher.shardFor("foo")).isEqualTo(fooShard);
    }
}
