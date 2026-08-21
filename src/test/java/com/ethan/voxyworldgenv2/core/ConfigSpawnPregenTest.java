package com.ethan.voxyworldgenv2.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spawn-anchored pre-generation config: what keeps an empty BMC3-style dedicated server filling a
 * radius around world spawn instead of doing nothing, ever, for lack of a player to anchor the
 * worker to (see ChunkGenerationManager.dispatchSpawnPregen).
 */
class ConfigSpawnPregenTest {

    @BeforeEach
    void setUp() {
        Config.DATA = new Config.ConfigData();
    }

    @Test
    void defaultsOnByDefaultWithA128ChunkRadius() {
        // On by default: the whole point is that an empty server does not need an operator to
        // opt in before it starts filling terrain around spawn.
        assertTrue(Config.DATA.spawnPregenEnabled);
        assertEquals(128, Config.DATA.spawnPregenRadius);
    }

    @Test
    void survivesAGsonRoundTripLikeTheRestOfTheConfigFile() {
        Config.DATA.spawnPregenEnabled = false;
        Config.DATA.spawnPregenRadius = 64;

        Gson gson = new GsonBuilder().create();
        String json = gson.toJson(Config.DATA);
        Config.ConfigData loaded = gson.fromJson(json, Config.ConfigData.class);

        assertFalse(loaded.spawnPregenEnabled);
        assertEquals(64, loaded.spawnPregenRadius);
    }

    @Test
    void anEmptyOrHandEditedConfigFileStillDefaultsToEnabled() {
        // A config predating this feature (or a hand-edited file missing the key) parses through
        // Gson with the field left at its Java default (0/false for a primitive), NOT the
        // in-class initializer -- this is exactly the trap that made stuckTaskTimeoutSeconds and
        // generationPausedUntilMs silently inert until they were wired up. Gson only preserves
        // declared field initializers when it constructs the object without invoking the
        // constructor for missing keys IF using field-based instantiation, which is what actually
        // happens here: confirm that behaviour explicitly rather than assuming it.
        Gson gson = new GsonBuilder().create();
        Config.ConfigData loaded = gson.fromJson("{}", Config.ConfigData.class);

        assertTrue(loaded.spawnPregenEnabled,
            "a config file with no spawnPregenEnabled key must still enable the feature");
        assertEquals(128, loaded.spawnPregenRadius,
            "a config file with no spawnPregenRadius key must still fall back to 128");
    }
}
