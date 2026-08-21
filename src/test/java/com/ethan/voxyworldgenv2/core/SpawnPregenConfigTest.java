package com.ethan.voxyworldgenv2.core;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins spawn pre-generation's config surface (ported from unified's {@code dispatchSpawnPregen},
 * commit 3f25000). {@code ChunkGenerationManager.dispatchSpawnPregen} reads
 * {@code Config.DATA.spawnPregenEnabled}/{@code spawnPregenRadius} directly rather than through a
 * {@code getXxx(isSingleplayer)}-style accessor -- unlike {@code generationRadius} there is no
 * singleplayer-profile override for these two, since spawn pregen only ever runs while the server
 * is otherwise idle regardless of which profile is active.
 */
class SpawnPregenConfigTest {

    @BeforeEach
    void setUp() {
        Config.DATA = new Config.ConfigData();
    }

    @Test
    void spawnPregenIsEnabledByDefaultWithA128ChunkRadius() {
        assertTrue(Config.DATA.spawnPregenEnabled);
        assertEquals(128, Config.DATA.spawnPregenRadius);
    }

    @Test
    void spawnPregenCanBeDisabled() {
        Config.DATA.spawnPregenEnabled = false;
        assertFalse(Config.DATA.spawnPregenEnabled);
    }

    @Test
    void spawnPregenRadiusIsIndependentlyConfigurable() {
        Config.DATA.spawnPregenRadius = 32;
        assertEquals(32, Config.DATA.spawnPregenRadius);
    }

    @Test
    void configRoundTripsThroughGsonWithSpawnPregenFields() {
        // dispatchSpawnPregen reads Config.DATA directly, which is loaded by Config.load() via a
        // straight Gson round trip -- a non-default choice must survive being written then reread.
        Config.DATA.spawnPregenEnabled = false;
        Config.DATA.spawnPregenRadius = 64;

        Gson gson = new Gson();
        String json = gson.toJson(Config.DATA);
        Config.ConfigData reread = gson.fromJson(json, Config.ConfigData.class);

        assertFalse(reread.spawnPregenEnabled);
        assertEquals(64, reread.spawnPregenRadius);
    }

    @Test
    void anOlderConfigJsonWithoutSpawnPregenKeysStillDeserializesToTheDefaults() {
        // Simulates a config.json saved by a build that predates these two fields: Config.load()
        // must not NPE or silently zero them out, the way a hand-edited "singleplayer": null
        // block is already guarded against.
        Gson gson = new Gson();
        Config.ConfigData reread = gson.fromJson("{}", Config.ConfigData.class);

        assertTrue(reread.spawnPregenEnabled);
        assertEquals(128, reread.spawnPregenRadius);
    }
}
