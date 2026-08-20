package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.network.RegionBitmask;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which chunks one player is believed to already hold, keyed by dimension.
 *
 * <p>Keyed by dimension because the previous flat set made overworld and nether chunks at the
 * same coordinates share a key — visiting one suppressed the other. That self-hid while the set
 * died on disconnect; persisting it across sessions would have made the missing terrain permanent.
 *
 * <p>Chunk packing matches {@code ChunkPos.asLong} exactly (low 32 bits x, high 32 bits z) so
 * values are interchangeable with the rest of the codebase, but is reimplemented here to keep the
 * class free of Minecraft types and therefore testable.
 */
public final class SyncedChunkStore {
    private final Map<String, LongSet> byDimension = new ConcurrentHashMap<>();

    /**
     * Chunks the worker claimed but could not actually deliver, because the chunk was not resident
     * when the send reached the main thread.
     *
     * <p>The catch-up loop marks a whole batch synced on the worker thread before dispatching, so
     * it does not re-collect the same unloaded chunks on every iteration. For a chunk that turns
     * out not to be resident, that mark means "claimed", not "delivered" — nothing was sent. This
     * set is what carries that distinction: {@code ServerEventHandler.onChunkLoad} treats a chunk
     * that is synced-but-deferred as not synced and sends it when it loads, which is exactly the
     * recovery the unconditional pre-branch onChunkLoad used to provide. Without it a
     * pre-generated world loses that terrain permanently — the client never receives it, so it is
     * not in next session's upload either.
     */
    private final Map<String, LongSet> deferredByDimension = new ConcurrentHashMap<>();

    public static long packChunk(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    public static int unpackChunkX(long packed) {
        return (int) packed;
    }

    public static int unpackChunkZ(long packed) {
        return (int) (packed >>> 32);
    }

    /** Live view for the worker catch-up loop, which passes it straight to DistanceGraph. */
    public LongSet setFor(String dimensionId) {
        return byDimension.computeIfAbsent(dimensionId,
            k -> LongSets.synchronize(new LongOpenHashSet()));
    }

    public boolean isSynced(String dimensionId, long chunkPos) {
        LongSet set = byDimension.get(dimensionId);
        return set != null && set.contains(chunkPos);
    }

    public void markSynced(String dimensionId, long chunkPos) {
        setFor(dimensionId).add(chunkPos);
    }

    public void markUnsynced(String dimensionId, long chunkPos) {
        LongSet set = byDimension.get(dimensionId);
        if (set != null) set.remove(chunkPos);
    }

    /** Records that this chunk was marked synced without being sent. */
    public void markDeferred(String dimensionId, long chunkPos) {
        deferredByDimension.computeIfAbsent(dimensionId,
            k -> LongSets.synchronize(new LongOpenHashSet())).add(chunkPos);
    }

    /** True while this chunk is claimed but undelivered, i.e. must still be sent when it loads. */
    public boolean isDeferred(String dimensionId, long chunkPos) {
        LongSet set = deferredByDimension.get(dimensionId);
        return set != null && set.contains(chunkPos);
    }

    /** Called once the chunk has actually been handed to the send path. */
    public void clearDeferred(String dimensionId, long chunkPos) {
        LongSet set = deferredByDimension.get(dimensionId);
        if (set != null) set.remove(chunkPos);
    }

    /** @return how many chunks were forgotten */
    public int forgetAll(String dimensionId) {
        LongSet set = byDimension.get(dimensionId);
        if (set == null) return 0;
        synchronized (set) {
            int n = set.size();
            set.clear();
            return n;
        }
    }

    /**
     * Forgets every chunk within a circular radius, matching how DistanceGraph measures range so
     * the catch-up loop re-sends exactly what was forgotten.
     *
     * @return how many chunks were forgotten
     */
    public int forgetWithin(String dimensionId, int centerChunkX, int centerChunkZ, int radiusChunks) {
        LongSet set = byDimension.get(dimensionId);
        if (set == null) return 0;
        long maxDistSq = (long) radiusChunks * radiusChunks;
        int removed = 0;
        synchronized (set) {
            for (LongIterator it = set.iterator(); it.hasNext(); ) {
                long packed = it.nextLong();
                long dx = unpackChunkX(packed) - (long) centerChunkX;
                long dz = unpackChunkZ(packed) - (long) centerChunkZ;
                if (dx * dx + dz * dz <= maxDistSq) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Adds every chunk named by a set of region bitmasks. Additive by design: a client uploads its
     * known set in several packets, and each is merged as it arrives.
     *
     * @return how many chunks were newly added
     */
    public int applyRegions(String dimensionId, Map<Long, byte[]> regions) {
        LongSet set = setFor(dimensionId);
        int added = 0;
        synchronized (set) {
            for (Map.Entry<Long, byte[]> entry : regions.entrySet()) {
                int baseX = RegionBitmask.regionBaseChunkX(entry.getKey());
                int baseZ = RegionBitmask.regionBaseChunkZ(entry.getKey());
                byte[] mask = entry.getValue();
                if (mask.length != RegionBitmask.MASK_BYTES) continue;
                for (int dz = 0; dz < 32; dz++) {
                    for (int dx = 0; dx < 32; dx++) {
                        if (RegionBitmask.get(mask, baseX + dx, baseZ + dz)
                            && set.add(packChunk(baseX + dx, baseZ + dz))) {
                            added++;
                        }
                    }
                }
            }
        }
        return added;
    }

    public int size(String dimensionId) {
        LongSet set = byDimension.get(dimensionId);
        return set == null ? 0 : set.size();
    }
}
