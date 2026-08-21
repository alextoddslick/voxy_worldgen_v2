package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The spawn anchor's configuration surface. The anchor itself (ChunkGenerationManager's
 * dispatchSpawnPregen) needs a live MinecraftServer to exercise, but its enable flag and radius
 * are what an operator actually touches, and a wrong default here is the difference between a
 * server that quietly pre-generates while empty and one that does nothing at all.
 */
class SpawnPregenConfigTest {

    @BeforeEach
    void setUp() {
        Config.DATA = new Config.ConfigData();
    }

    @Test
    void spawnPregenIsOnByDefaultAtRadius128() {
        assertTrue(Config.DATA.spawnPregenEnabled,
            "an empty server that generates nothing is the failure this feature exists to fix");
        assertEquals(128, Config.DATA.spawnPregenRadius);
    }

    /**
     * Disabling must be a true rollback: dispatchSpawnPregen's very first check is
     * {@code !Config.DATA.spawnPregenEnabled}, so a disabled anchor never even reaches
     * getOrSetupState and never takes a ticket. TicketType.FORCED persists into the world's
     * chunk_tickets SavedData and is reactivated by prepareLevels(), which blocks boot until every
     * chunk reaches FULL -- so a disabled anchor that still pinned chunks would show up as a hung
     * start, not as wasted work.
     */
    @Test
    void disablingIsATrueRollback() {
        Config.DATA.spawnPregenEnabled = false;
        assertFalse(Config.DATA.spawnPregenEnabled);
    }

    /**
     * The radius is clamped to at least 1 at the use site (dispatchSpawnPregen does
     * {@code Math.max(1, Config.DATA.spawnPregenRadius)}) rather than validated here, because a
     * hand-edited 0 must not turn into "generate nothing forever" silently.
     */
    @Test
    void aZeroRadiusStillMeansAtLeastOneChunk() {
        Config.DATA.spawnPregenRadius = 0;
        assertEquals(1, Math.max(1, Config.DATA.spawnPregenRadius));
    }

    /** A negative hand-edited radius must not sail past the floor either. */
    @Test
    void aNegativeRadiusStillMeansAtLeastOneChunk() {
        Config.DATA.spawnPregenRadius = -5;
        assertEquals(1, Math.max(1, Config.DATA.spawnPregenRadius));
    }
}
