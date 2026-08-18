package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-player overrides for the LOD bandwidth cap and send distance. An override entry always wins
 * over the global/singleplayer resolution; an absent entry falls back to it. Both maps are keyed
 * by UUID string because that is what survives a Gson round-trip of the config file (the same
 * reason {@code headlessPlayers} stores strings).
 */
class ConfigPerPlayerTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        Config.DATA = new Config.ConfigData();
    }

    // ---- rate limit ----

    @Test
    void rateLimitOverrideBeatsGlobal() {
        Config.DATA.maxMbpsPerPlayer = 2.0;
        Config.DATA.playerRateLimits.put(ALICE.toString(), 10.0);

        assertEquals(10.0, Config.getMaxMbpsForPlayer(ALICE, false), 0.001);
        assertEquals(2.0, Config.getMaxMbpsForPlayer(BOB, false), 0.001);
    }

    @Test
    void rateLimitZeroOverrideMeansUnlimitedForThatPlayer() {
        Config.DATA.maxMbpsPerPlayer = 2.0;
        Config.DATA.playerRateLimits.put(ALICE.toString(), 0.0);

        assertEquals(0.0, Config.getMaxMbpsForPlayer(ALICE, false), 0.001);
        assertEquals(2.0, Config.getMaxMbpsForPlayer(BOB, false), 0.001);
    }

    @Test
    void rateLimitOverrideBeatsTheSingleplayerProfileToo() {
        // The profile resolves to unlimited by default; an explicit per-player cap still wins.
        Config.DATA.playerRateLimits.put(ALICE.toString(), 5.0);

        assertEquals(5.0, Config.getMaxMbpsForPlayer(ALICE, true), 0.001);
        assertEquals(0.0, Config.getMaxMbpsForPlayer(BOB, true), 0.001);
    }

    @Test
    void rateLimitNullMapFallsBackToGlobal() {
        // A hand-edited config can carry "playerRateLimits": null.
        Config.DATA.playerRateLimits = null;

        assertEquals(2.0, Config.getMaxMbpsForPlayer(ALICE, false), 0.001);
    }

    // ---- send distance ----

    @Test
    void sendDistanceDefaultMatchesTheOldHardcodedCap() {
        // broadcastLODData used a hardcoded 4096-block (256-chunk) cap; the default must not
        // change behaviour for existing installs.
        assertEquals(256, Config.getSendDistanceForPlayer(ALICE, false));
    }

    @Test
    void sendDistanceOverrideBeatsGlobal() {
        Config.DATA.playerSendDistances.put(ALICE.toString(), 64);

        assertEquals(64, Config.getSendDistanceForPlayer(ALICE, false));
        assertEquals(256, Config.getSendDistanceForPlayer(BOB, false));
    }

    @Test
    void sendDistanceZeroMeansUnlimited() {
        Config.DATA.lodSendDistanceChunks = 0;
        assertEquals(0, Config.getSendDistanceForPlayer(ALICE, false));

        Config.DATA.lodSendDistanceChunks = 256;
        Config.DATA.playerSendDistances.put(ALICE.toString(), 0);
        assertEquals(0, Config.getSendDistanceForPlayer(ALICE, false));
    }

    @Test
    void sendDistanceSingleplayerProfileIsUnlimitedByDefault() {
        // Own machine, own bandwidth: the profile's 0-sentinel means unlimited, and an explicit
        // profile value is used verbatim like every other profile field.
        assertEquals(0, Config.getSendDistanceForPlayer(ALICE, true));

        Config.DATA.singleplayer.lodSendDistanceChunks = 128;
        assertEquals(128, Config.getSendDistanceForPlayer(ALICE, true));

        Config.DATA.singleplayer.enableSingleplayerDefaults = false;
        assertEquals(256, Config.getSendDistanceForPlayer(ALICE, true));
    }

    @Test
    void sendDistanceNullMapFallsBackToGlobal() {
        Config.DATA.playerSendDistances = null;

        assertEquals(256, Config.getSendDistanceForPlayer(ALICE, false));
    }

    // ---- hud stat toggles ----

    @Test
    void hudTogglesDefaultToEverythingVisible() {
        // The toggles exist to let the user hide stats, so a fresh install shows them all.
        assertTrue(Config.DATA.hudShowRaw);
        assertTrue(Config.DATA.hudShowCompressed);
        assertTrue(Config.DATA.hudShowSavings);
        assertTrue(Config.DATA.hudShowClientDisk);
    }
}
