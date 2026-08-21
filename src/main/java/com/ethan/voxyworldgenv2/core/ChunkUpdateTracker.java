package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks which chunk sections changed so a block-update resend has something to bound itself by. */
public class ChunkUpdateTracker {
    private static final ChunkUpdateTracker INSTANCE = new ChunkUpdateTracker();

    // dimension -> (chunk key -> set of dirty section y-levels)
    private final Map<ResourceKey<Level>, Map<Long, IntSet>> dirty = new ConcurrentHashMap<>();
    private final Map<ResourceKey<Level>, Long> lastProcessTimes = new ConcurrentHashMap<>();

    // Cap chunks handled per cycle so a burst of activity (tnt, fluids, fire) can't spike the tick.
    private static final int MAX_CHUNKS_PER_CYCLE = 64;
    // Floor so a very small update_interval can't hammer the tick.
    private static final long MIN_PROCESS_INTERVAL_MS = 500;

    private ChunkUpdateTracker() {}

    public static ChunkUpdateTracker getInstance() {
        return INSTANCE;
    }

    /** How often to flush dirty sections, driven by the (previously unread) update_interval config. */
    private static long processIntervalMs() {
        return Math.max(MIN_PROCESS_INTERVAL_MS, Config.DATA.update_interval * 50L);
    }

    /** Marks the section containing {@code blockY} dirty for this chunk. */
    public void markDirty(LevelChunk chunk, int blockY) {
        // This fires for every block change (fluids, fire, redstone), so bail before any map work
        // when nobody is online to receive a resend. Safe with spawn pre-generation running: that
        // path delivers via broadcastLODData/onChunkLoad directly, not through this dirty-block
        // tracker, so a player-count guard here does not affect it.
        if (PlayerTracker.getInstance().getPlayerCount() == 0) return;

        long key = chunk.getPos().toLong();
        Map<Long, IntSet> levelDirty = dirty.computeIfAbsent(chunk.getLevel().dimension(), k -> new ConcurrentHashMap<>());

        // Cap the backlog so a redstone/fluid storm can't grow it forever; a dropped chunk gets
        // re-marked the next time something in it changes.
        int cap = Config.DATA.maxQueueSize;
        if (cap > 0 && !levelDirty.containsKey(key) && levelDirty.size() >= cap) return;

        int sectionY = SectionPos.blockToSectionCoord(blockY);
        IntSet set = levelDirty.computeIfAbsent(key, k -> new IntOpenHashSet());
        synchronized (set) {
            set.add(sectionY);
        }
    }

    public void processDirty(ServerLevel level) {
        if (level == null) return;

        Map<Long, IntSet> levelDirty = dirty.get(level.dimension());
        if (levelDirty == null || levelDirty.isEmpty()) return;

        long now = System.currentTimeMillis();
        long lastTime = lastProcessTimes.getOrDefault(level.dimension(), 0L);
        if (now - lastTime < processIntervalMs()) return;
        lastProcessTimes.put(level.dimension(), now);

        int processed = 0;
        Iterator<Map.Entry<Long, IntSet>> it = levelDirty.entrySet().iterator();
        while (it.hasNext() && processed < MAX_CHUNKS_PER_CYCLE) {
            Map.Entry<Long, IntSet> entry = it.next();
            it.remove();

            long posLong = entry.getKey();
            IntSet src = entry.getValue();
            ChunkPos pos = new ChunkPos(posLong);

            // Snapshot the dirty set so a concurrent markDirty can't mutate it mid-read.
            IntSet sectionYs;
            synchronized (src) {
                sectionYs = new IntOpenHashSet(src);
            }

            // processDirty runs on the server tick thread, so this must never block: getChunk's
            // false only skips adding a ticket, and under C2ME it still parks the main thread on
            // an incomplete FULL future that nothing will drive. A chunk that isn't resident is
            // simply skipped; BlockUpdateMixin re-marks it dirty on the next block change.
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk != null) {
                NetworkHandler.broadcastLODData(chunk, sectionYs);
                processed++;
            }
        }
    }
}
