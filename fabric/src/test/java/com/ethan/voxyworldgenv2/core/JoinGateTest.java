package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The join gate: the time-boxed window where a player's LOD sends are withheld while the server
 * waits for their known-chunks upload (see HANDOFF.md, "the modded gate is gone" / plan 3). This
 * is the one piece of protocol-5 behaviour that runs entirely server-side on plain UUID + a
 * dimension-id string -- {@link PlayerTracker#armGate}, {@link PlayerTracker#isGated} and
 * {@link PlayerTracker#clearGate} need no {@code ServerPlayer}, so the gate's state machine is
 * unit-testable even though no real client has ever driven it end to end.
 *
 * <p>{@link #theGateExpiresOpenNotClosed()} backdates the recorded arm time via reflection rather
 * than sleeping past {@code knownChunksTimeoutSeconds} -- the field is seconds-granularity, so a
 * real-time test would need to block for over a second to observe the boundary.
 */
class JoinGateTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private final PlayerTracker tracker = PlayerTracker.getInstance();

    @BeforeEach
    void setUp() {
        Config.DATA = new Config.ConfigData();
        tracker.clear();
    }

    @AfterEach
    void tearDown() {
        tracker.clear();
    }

    @Test
    void aFreshPlayerIsNotGatedUntilArmed() {
        UUID uuid = UUID.randomUUID();
        assertFalse(tracker.isGated(uuid, OVERWORLD), "never armed -> nothing to wait for");
    }

    @Test
    void armingTheGateWithholdsSendsImmediately() {
        Config.DATA.knownChunksTimeoutSeconds = 10;
        UUID uuid = UUID.randomUUID();

        tracker.armGate(uuid, OVERWORLD);

        assertTrue(tracker.isGated(uuid, OVERWORLD), "just armed and well inside the timeout -> gated");
    }

    /**
     * The exact behaviour the HANDOFF calls out: the gate must expire OPEN. A vanilla client (or a
     * modded one whose upload never lands) must eventually be served terrain rather than being
     * withheld forever -- expiring closed would silently reintroduce the pre-plan-2 "modded gate".
     */
    @Test
    void theGateExpiresOpenNotClosed() throws Exception {
        Config.DATA.knownChunksTimeoutSeconds = 10;
        UUID uuid = UUID.randomUUID();
        tracker.armGate(uuid, OVERWORLD);
        assertTrue(tracker.isGated(uuid, OVERWORLD), "sanity: armed and fresh");

        backdateGateArmTime(uuid, OVERWORLD, 11_000);

        assertFalse(tracker.isGated(uuid, OVERWORLD),
            "past the timeout the gate must open, not stay shut -- a stuck-closed gate is a permanent hole");
    }

    /** Right at the boundary the gate is still shut; only strictly past it does it open. */
    @Test
    void theGateIsStillShutExactlyAtTheTimeoutBoundary() throws Exception {
        Config.DATA.knownChunksTimeoutSeconds = 10;
        UUID uuid = UUID.randomUUID();
        tracker.armGate(uuid, OVERWORLD);

        backdateGateArmTime(uuid, OVERWORLD, 10_000);

        assertTrue(tracker.isGated(uuid, OVERWORLD), "exactly at the timeout has not yet exceeded it");
    }

    @Test
    void aZeroTimeoutDisablesTheGateEntirely() {
        Config.DATA.knownChunksTimeoutSeconds = 0;
        UUID uuid = UUID.randomUUID();

        tracker.armGate(uuid, OVERWORLD);

        assertFalse(tracker.isGated(uuid, OVERWORLD), "0 means the feature is off, not an instant timeout");
    }

    @Test
    void disablingRememberSentChunksDisablesTheGateRegardlessOfArming() {
        Config.DATA.rememberSentChunks = false;
        UUID uuid = UUID.randomUUID();

        tracker.armGate(uuid, OVERWORLD); // armGate itself is a no-op when the feature is off

        assertFalse(tracker.isGated(uuid, OVERWORLD));
    }

    /** The upload finishing (finishKnownChunksUpload's caller) clears the gate via clearGate, before any timeout. */
    @Test
    void clearGateOpensItImmediatelyRegardlessOfTimeRemaining() {
        Config.DATA.knownChunksTimeoutSeconds = 300;
        UUID uuid = UUID.randomUUID();
        tracker.armGate(uuid, OVERWORLD);
        assertTrue(tracker.isGated(uuid, OVERWORLD));

        tracker.clearGate(uuid, OVERWORLD);

        assertFalse(tracker.isGated(uuid, OVERWORLD), "an explicit clear must not wait for the timeout");
    }

    /** A portal trip re-arms the gate for the new dimension without touching the old one's state. */
    @Test
    void gateIsPerDimensionSoAPortalTripDoesNotGateTheDimensionLeftBehind() {
        Config.DATA.knownChunksTimeoutSeconds = 300;
        UUID uuid = UUID.randomUUID();

        tracker.armGate(uuid, OVERWORLD);
        tracker.clearGate(uuid, OVERWORLD); // overworld upload already finished before the portal trip
        tracker.armGate(uuid, NETHER);      // entering the nether re-arms only the nether's gate

        assertFalse(tracker.isGated(uuid, OVERWORLD), "the finished dimension must not be re-gated by a different one arming");
        assertTrue(tracker.isGated(uuid, NETHER));
    }

    /** Backdates the recorded arm time via reflection so expiry is testable without sleeping. */
    @SuppressWarnings("unchecked")
    private void backdateGateArmTime(UUID uuid, String dimensionId, long millisAgo) throws Exception {
        Field f = PlayerTracker.class.getDeclaredField("awaitingKnownSet");
        f.setAccessible(true);
        Map<UUID, Map<String, Long>> awaiting = (Map<UUID, Map<String, Long>>) f.get(tracker);
        Map<String, Long> byDim = awaiting.get(uuid);
        assertNotNull(byDim, "test bug: gate was never armed for this player");
        byDim.put(dimensionId, System.currentTimeMillis() - millisAgo);
    }
}
