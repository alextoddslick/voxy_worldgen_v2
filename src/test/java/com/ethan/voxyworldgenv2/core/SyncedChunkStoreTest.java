package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.network.RegionBitmask;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SyncedChunkStoreTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void marksAndReadsBack() {
        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(4, 9));
        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(4, 9)));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(9, 4)));

        store.markUnsynced(OVERWORLD, SyncedChunkStore.packChunk(4, 9));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(4, 9)));
    }

    /** The bug this class exists to fix: one flat set made these two the same chunk. */
    @Test
    void dimensionsDoNotCollide() {
        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(0, 0));
        assertFalse(store.isSynced(NETHER, SyncedChunkStore.packChunk(0, 0)));
    }

    @Test
    void chunkPackingRoundTripsIncludingNegatives() {
        for (int[] c : new int[][]{{0, 0}, {1, -1}, {-2000, 3000}, {-1, -1}, {Integer.MIN_VALUE / 2, 7}}) {
            long packed = SyncedChunkStore.packChunk(c[0], c[1]);
            assertEquals(c[0], SyncedChunkStore.unpackChunkX(packed));
            assertEquals(c[1], SyncedChunkStore.unpackChunkZ(packed));
        }
    }

    @Test
    void forgetAllClearsOnlyTheNamedDimension() {
        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(1, 1));
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(2, 2));
        store.markSynced(NETHER, SyncedChunkStore.packChunk(1, 1));

        assertEquals(2, store.forgetAll(OVERWORLD));
        assertEquals(0, store.size(OVERWORLD));
        assertEquals(1, store.size(NETHER));
    }

    @Test
    void forgetWithinUsesACircularRadiusAndSparesChunksOutside() {
        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(0, 0));    // distance 0
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(3, 4));    // distance 5, inside r=5
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(6, 0));    // distance 6, outside
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(-5, 0));   // distance 5, inside

        assertEquals(3, store.forgetWithin(OVERWORLD, 0, 0, 5));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(0, 0)));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(3, 4)));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(-5, 0)));
        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(6, 0)));
    }

    @Test
    void forgetWithinOnAnUnknownDimensionIsANoOp() {
        SyncedChunkStore store = new SyncedChunkStore();
        assertEquals(0, store.forgetWithin("minecraft:the_end", 0, 0, 16));
        assertEquals(0, store.forgetAll("minecraft:the_end"));
    }

    @Test
    void applyRegionsSetsExactlyTheChunksTheBitmaskNames() {
        Map<Long, byte[]> regions = new HashMap<>();
        byte[] mask = new byte[RegionBitmask.MASK_BYTES];
        RegionBitmask.set(mask, 5, 6);
        RegionBitmask.set(mask, 31, 31);
        regions.put(RegionBitmask.regionKey(5, 6), mask);

        SyncedChunkStore store = new SyncedChunkStore();
        assertEquals(2, store.applyRegions(OVERWORLD, regions));
        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(5, 6)));
        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(31, 31)));
        assertFalse(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(7, 7)));
    }

    @Test
    void applyRegionsHandlesNegativeRegionsAndIsAdditive() {
        Map<Long, byte[]> regions = new HashMap<>();
        byte[] mask = new byte[RegionBitmask.MASK_BYTES];
        RegionBitmask.set(mask, -1, -1);
        regions.put(RegionBitmask.regionKey(-1, -1), mask);

        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(100, 100));
        assertEquals(1, store.applyRegions(OVERWORLD, regions));

        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(-1, -1)));
        assertTrue(store.isSynced(OVERWORLD, SyncedChunkStore.packChunk(100, 100)));
        assertEquals(0, store.applyRegions(OVERWORLD, regions), "re-applying adds nothing new");
    }

    @Test
    void setForReturnsALiveViewUsableByTheCatchUpLoop() {
        SyncedChunkStore store = new SyncedChunkStore();
        store.markSynced(OVERWORLD, SyncedChunkStore.packChunk(1, 2));
        assertTrue(store.setFor(OVERWORLD).contains(SyncedChunkStore.packChunk(1, 2)));
        assertTrue(store.setFor("minecraft:the_end").isEmpty());
    }
}
