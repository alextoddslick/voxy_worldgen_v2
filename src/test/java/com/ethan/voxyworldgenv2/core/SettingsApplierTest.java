package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The settings screen sends string-keyed ops; this applier gives them exactly the semantics and
 * clamps of the equivalent /voxygen commands. Unknown keys are ignored (a newer client must not
 * break an older server), malformed values are skipped, and the return value counts only what
 * actually applied.
 */
class SettingsApplierTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        Config.DATA = new Config.ConfigData();
    }

    private static SettingsApplier.Op op(String key, String value) {
        return new SettingsApplier.Op(key, value);
    }

    @Test
    void appliesGlobalSettingsWithCommandClamps() {
        int applied = SettingsApplier.apply(List.of(
            op("enabled", "false"),
            op("radius", "128"),
            op("tasks", "40"),
            op("genrate", "200"),
            op("ratelimit", "10.5"),
            op("senddistance", "512")
        ), false);

        assertEquals(6, applied);
        assertFalse(Config.DATA.enabled);
        assertEquals(128, Config.DATA.generationRadius);
        assertEquals(40, Config.DATA.maxActiveTasks);
        assertEquals(200, Config.DATA.maxChunksPerSecond);
        assertEquals(10.5, Config.DATA.maxMbpsPerPlayer, 0.001);
        assertEquals(512, Config.DATA.lodSendDistanceChunks);
    }

    @Test
    void clampsOutOfRangeValuesLikeTheCommandsDo() {
        SettingsApplier.apply(List.of(
            op("radius", "9999"),   // command range is 1..512
            op("tasks", "0"),       // command range is 1..128
            op("ratelimit", "-5")   // negative means unlimited, stored as 0
        ), false);

        assertEquals(512, Config.DATA.generationRadius);
        assertEquals(1, Config.DATA.maxActiveTasks);
        assertEquals(0.0, Config.DATA.maxMbpsPerPlayer, 0.001);
    }

    @Test
    void singleplayerProfileReceivesTheEditsWhenActive() {
        // Mirrors spActive() in the commands: an active profile holds the values the player is
        // actually experiencing, so that is what the screen must edit.
        int applied = SettingsApplier.apply(List.of(op("radius", "256")), true);

        assertEquals(1, applied);
        assertEquals(256, Config.DATA.singleplayer.generationRadius);
        assertEquals(64, Config.DATA.generationRadius, "base value must stay untouched");
    }

    @Test
    void perPlayerOverridesApplyAndReset() {
        int applied = SettingsApplier.apply(List.of(
            op("player." + ALICE + ".ratelimit", "10"),
            op("player." + ALICE + ".senddistance", "64")
        ), false);

        assertEquals(2, applied);
        assertEquals(10.0, Config.getMaxMbpsForPlayer(ALICE, false), 0.001);
        assertEquals(64, Config.getSendDistanceForPlayer(ALICE, false));

        applied = SettingsApplier.apply(List.of(op("player." + ALICE + ".reset", "")), false);

        assertEquals(1, applied);
        assertEquals(2.0, Config.getMaxMbpsForPlayer(ALICE, false), 0.001);
        assertEquals(256, Config.getSendDistanceForPlayer(ALICE, false));
    }

    @Test
    void hudTogglesApply() {
        int applied = SettingsApplier.apply(List.of(
            op("hud.compressed", "false"),
            op("hud.savings", "false"),
            op("hud.clientdisk", "true")
        ), false);

        assertEquals(3, applied);
        assertFalse(Config.DATA.hudShowCompressed);
        assertFalse(Config.DATA.hudShowSavings);
        assertTrue(Config.DATA.hudShowClientDisk);
    }

    @Test
    void unknownKeysAndMalformedValuesAreSkippedNotFatal() {
        int applied = SettingsApplier.apply(List.of(
            op("some.future.setting", "42"),
            op("radius", "not-a-number"),
            op("player.not-a-uuid.ratelimit", "5"),
            op("tasks", "30")
        ), false);

        assertEquals(1, applied, "only the valid op applies");
        assertEquals(30, Config.DATA.maxActiveTasks);
        assertEquals(64, Config.DATA.generationRadius, "malformed radius must not corrupt the value");
    }
}
