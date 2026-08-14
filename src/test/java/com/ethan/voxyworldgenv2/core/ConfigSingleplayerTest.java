package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSingleplayerTest {

    @BeforeEach
    void setUp() {
        Config.DATA = new Config.ConfigData();
    }

    @Test
    void singleplayerAutoDefaultsAreGenerous() {
        assertTrue(Config.DATA.singleplayer.enableSingleplayerDefaults);
        // base radius 64 / tasks 20 -> auto floors them up to 128
        assertEquals(128, Config.getGenerationRadius(true));
        assertEquals(128, Config.getMaxActiveTasks(true));
        assertEquals(0.0, Config.getMaxMbpsPerPlayer(true), 0.001);
        assertEquals(0, Config.getDimensionChangePauseSeconds(true));
        assertEquals(0, Config.getMaxChunksPerSecond(true)); // unlimited
    }

    @Test
    void chunksPerSecondCapIsPerProfile() {
        // The FPS-drain knob: capping singleplayer must not slow a dedicated server, and
        // a server cap must not leak into singleplayer.
        Config.DATA.singleplayer.maxChunksPerSecond = 50;
        Config.DATA.maxChunksPerSecond = 400;

        assertEquals(50, Config.getMaxChunksPerSecond(true));
        assertEquals(400, Config.getMaxChunksPerSecond(false));

        Config.DATA.singleplayer.enableSingleplayerDefaults = false;
        assertEquals(400, Config.getMaxChunksPerSecond(true));
    }

    @Test
    void autoNeverLimitsBelowTheConfiguredBaseValues() {
        // The whole point of auto: a user who raised the base radius must never be
        // clamped back down just because they opened a singleplayer world.
        Config.DATA.generationRadius = 512;
        Config.DATA.maxActiveTasks = 150;

        assertEquals(512, Config.getGenerationRadius(true));
        assertEquals(150, Config.getMaxActiveTasks(true));
    }

    @Test
    void multiplayerDefaultsAreStandard() {
        assertEquals(64, Config.getGenerationRadius(false));
        assertEquals(20, Config.getMaxActiveTasks(false));
        assertEquals(2.0, Config.getMaxMbpsPerPlayer(false), 0.001);
        assertEquals(15, Config.getDimensionChangePauseSeconds(false));
    }

    @Test
    void disablingSingleplayerDefaultsFallsBackToMultiplayerValues() {
        Config.DATA.singleplayer.enableSingleplayerDefaults = false;

        assertEquals(64, Config.getGenerationRadius(true));
        assertEquals(20, Config.getMaxActiveTasks(true));
        assertEquals(2.0, Config.getMaxMbpsPerPlayer(true), 0.001);
        assertEquals(15, Config.getDimensionChangePauseSeconds(true));
    }

    @Test
    void explicitSingleplayerValuesAreRespectedVerbatim() {
        Config.DATA.singleplayer.generationRadius = 256;
        Config.DATA.singleplayer.maxActiveTasks = 64;
        Config.DATA.singleplayer.maxMbpsPerPlayer = 100.0;
        Config.DATA.singleplayer.dimensionChangePauseSeconds = 2;

        assertEquals(256, Config.getGenerationRadius(true));
        assertEquals(64, Config.getMaxActiveTasks(true));
        assertEquals(100.0, Config.getMaxMbpsPerPlayer(true), 0.001);
        assertEquals(2, Config.getDimensionChangePauseSeconds(true));
    }

    @Test
    void explicitSingleplayerValuesMayGoBelowTheBase() {
        // A setting, not a force: if the user deliberately wants a smaller
        // singleplayer radius than their server profile, that choice sticks.
        Config.DATA.generationRadius = 256;
        Config.DATA.singleplayer.generationRadius = 32;

        assertEquals(32, Config.getGenerationRadius(true));
    }

    @Test
    void nullSingleplayerBlockFallsBackToMultiplayerValues() {
        // A hand-edited config can carry "singleplayer": null; the accessors
        // must not throw and must behave as if the profile were disabled.
        Config.DATA.singleplayer = null;

        assertEquals(64, Config.getGenerationRadius(true));
        assertEquals(20, Config.getMaxActiveTasks(true));
        assertEquals(2.0, Config.getMaxMbpsPerPlayer(true), 0.001);
        assertEquals(15, Config.getDimensionChangePauseSeconds(true));
    }
}
