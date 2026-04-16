package com.cheetah.racer.publisher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;

/**
 * Phase 4.1 – Consistent-hash ring for shard-key-to-shard-index routing.
 *
 * <p>
 * Each physical shard is represented by {@code virtualNodesPerShard} virtual
 * nodes distributed uniformly around a 32-bit integer ring. A shard key is
 * hashed with MD5 and the ring is walked clockwise to find the first virtual
 * node whose position is &ge; the key's hash; that node's shard index is
 * returned.
 *
 * <p>
 * This strategy minimises redistribution when shards are added or removed:
 * only the keys previously routed to the affected segment need to move.
 *
 * <p>
 * Thread-safety: the ring is immutable after construction and is safe for
 * concurrent reads without synchronisation.
 */
public class RacerConsistentHashRing {

    /** TreeMap from ring-position → shard index. */
    private final TreeMap<Integer, Integer> ring = new TreeMap<>();

    private final int shardCount;

    /**
     * Build a new ring.
     *
     * @param shardCount           number of physical shards (must be &gt; 0)
     * @param virtualNodesPerShard virtual nodes per physical shard (must be &gt; 0)
     */
    public RacerConsistentHashRing(int shardCount, int virtualNodesPerShard) {
        if (shardCount <= 0)
            throw new IllegalArgumentException("shardCount must be > 0");
        if (virtualNodesPerShard <= 0)
            throw new IllegalArgumentException("virtualNodesPerShard must be > 0");

        this.shardCount = shardCount;

        for (int shard = 0; shard < shardCount; shard++) {
            for (int v = 0; v < virtualNodesPerShard; v++) {
                String vKey = "shard-" + shard + "-vnode-" + v;
                int position = md5Int(vKey);
                // Resolve collisions by linear probing (wrap around on overflow)
                while (ring.containsKey(position)) {
                    if (position == Integer.MAX_VALUE) {
                        position = Integer.MIN_VALUE;
                    } else {
                        position++;
                    }
                }
                ring.put(position, shard);
            }
        }
    }

    /**
     * Return the primary shard index for the given routing key.
     *
     * @param key routing key (e.g. a message channel name or entity id)
     * @return shard index in {@code [0, shardCount)}
     */
    public int getShardFor(String key) {
        return getShardInternal(key).shardIndex();
    }

    /**
     * Return a failover shard index for the given routing key — the first shard
     * in the ring that is different from the primary shard.
     *
     * <p>
     * If all virtual nodes belong to the same physical shard (e.g. {@code
     * shardCount == 1}) the primary shard is returned unchanged.
     *
     * @param key routing key
     * @return failover shard index in {@code [0, shardCount)}
     */
    public int getFailoverShardFor(String key) {
        if (shardCount == 1 || ring.isEmpty()) {
            return 0;
        }
        // Reuse the already-computed ring position — avoids a second MD5 call (#15).
        ShardResult primary = getShardInternal(key);
        Integer pos = primary.ringPosition();
        // Walk the ring clockwise until we find a different shard
        int checked = 0;
        int totalNodes = ring.size();
        while (checked < totalNodes) {
            Integer nextPos = ring.higherKey(pos);
            if (nextPos == null) {
                nextPos = ring.firstKey();
            }
            int candidateShard = ring.get(nextPos);
            if (candidateShard != primary.shardIndex()) {
                return candidateShard;
            }
            pos = nextPos;
            checked++;
        }
        // Fallback: rotate primary by 1 mod shardCount
        return (primary.shardIndex() + 1) % shardCount;
    }

    /** Exposed for testing – number of virtual nodes in the ring. */
    int ringSize() {
        return ring.size();
    }

    // ── Internal ring lookup ────────────────────────────────────────────────────

    /**
     * Encapsulates both the shard index and the resolved ring position so callers
     * can reuse the MD5-computed position for ring walking without rehashing.
     */
    private record ShardResult(int shardIndex, int ringPosition) {
    }

    /**
     * Core ring lookup: hashes {@code key} once and returns both the target shard
     * index and the ring position.
     *
     * <p>
     * Both {@link #getShardFor} and {@link #getFailoverShardFor} delegate here
     * so the MD5 computation is performed exactly once per lookup (#15).
     */
    private ShardResult getShardInternal(String key) {
        if (ring.isEmpty()) {
            return new ShardResult(0, 0);
        }
        int hash = md5Int(key == null ? "" : key);
        Integer pos = ring.ceilingKey(hash);
        if (pos == null) {
            pos = ring.firstKey();
        }
        return new ShardResult(ring.get(pos), pos);
    }

    // ── Hashing ───────────────────────────────────────────────────────────────

    /**
     * Per-thread cached {@link MessageDigest} for MD5.
     *
     * <p>
     * {@code MessageDigest} is not thread-safe, so a {@link ThreadLocal} is used
     * to avoid allocating a new instance on every call to {@link #md5Int(String)}.
     * On the hot routing path (every {@code publishToShard()} call) this eliminates
     * the {@code Security.getProvider()} lookup and factory dispatch that
     * {@code getInstance()} would otherwise perform.
     */
    private static final ThreadLocal<MessageDigest> MD5_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            // MD5 is mandated by the JVM spec, this cannot happen
            throw new IllegalStateException("MD5 not available", e);
        }
    });

    private static int md5Int(String input) {
        MessageDigest md = MD5_DIGEST.get();
        // reset() clears any leftover state from a prior call on this thread
        md.reset();
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        // Take first 4 bytes as a signed int — gives uniform 32-bit distribution
        return ((digest[0] & 0xFF) << 24)
                | ((digest[1] & 0xFF) << 16)
                | ((digest[2] & 0xFF) << 8)
                | (digest[3] & 0xFF);
    }
}
