package com.ethan.voxyworldgenv2.client;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LodMemory} was the third component that shipped entirely inert (HANDOFF.md: "orphaned --
 * no record, tick or onDisconnect caller, so no known-chunks payload was EVER sent"). Those
 * callers now exist (VoxyWorldGenV2Client, NetworkClientHandler), but the class itself is still
 * unexercised end to end -- and it cannot be driven through a real {@code tick(Minecraft)} in this
 * harness: {@code switchTo}/{@code worldKeyFor} need a live {@code Minecraft} instance, and even
 * the parts that only need {@code FabricLoader} fail here -- {@code FabricLoader.getInstance()}
 * resolves under Loom's test classpath, but {@code getGameDir()} throws
 * {@code IllegalStateException: invoked too early?} (verified empirically), which is exactly the
 * failure class the "never call Config.load/save from a test" rule in HANDOFF.md is about.
 *
 * <p>So this file tests the one thing that is both real logic and reachable without a client: the
 * public, stateless-argument entry points {@link LodMemory#record} and
 * {@link LodMemory#onDisconnect}, with the private static fields normally set by {@code switchTo}
 * seeded directly via reflection. {@code onDisconnect} always calls {@code flush()} first, and
 * {@code flush()} calls the FabricLoader-backed {@code fileFor()} OUTSIDE its own try/catch when
 * {@code dirty} is true -- so every test here leaves {@code dirty == false} before calling
 * {@code onDisconnect}, exactly like a session with no unflushed changes. A test that seeded
 * {@code dirty = true} and called {@code onDisconnect} would not fail cleanly, it would blow up on
 * the same "invoked too early" the probe above hit; that path is genuinely unreachable outside a
 * running game and is NOT covered here.
 *
 * <p>Not covered by this file, and not verifiable without a real client: {@code tick}'s dimension
 * change detection and its upload backoff schedule (15s/30s/60s/120s, {@code MAX_UPLOAD_ATTEMPTS}),
 * {@code switchTo} loading a persisted region file, {@code worldKeyFor}/{@code singleplayerLevelId}
 * hashing a real server address or save id, and {@code flush} actually writing bytes to disk.
 */
class LodMemoryTest {

    private static final ResourceKey<Level> OVERWORLD =
        ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
    private static final ResourceKey<Level> NETHER =
        ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:the_nether"));

    @BeforeEach
    void resetToNeverConnected() throws Exception {
        setStatic("worldKey", null);
        setStatic("dimensionId", null);
        setStatic("dimension", null);
        setStatic("dirty", false);
        setStatic("pendingUpload", false);
        setStatic("uploadAttempts", 0);
        setStatic("unidentifiedWorldLogged", false);
        regions().clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        regions().clear();
    }

    @Test
    void recordDoesNothingBeforeAWorldIsIdentified() throws Exception {
        // worldKey/dimensionId/dimension are all null until switchTo() has run once, which only
        // happens from a live tick(Minecraft). A payload arriving before that must be dropped, not
        // attributed to some default dimension.
        LodMemory.record(OVERWORLD, 5, 9);

        assertTrue(regions().isEmpty(), "nothing recorded with no identified world");
        assertFalse(isDirty());
    }

    @Test
    void recordIgnoresAChunkForADimensionOtherThanTheCurrentOne() throws Exception {
        seedIdentifiedWorld(OVERWORLD);

        // A LOD payload for the dimension the client just left, arriving in the one-tick window
        // before LodMemory notices the switch -- must not be attributed to the new dimension.
        LodMemory.record(NETHER, 1, 1);

        assertTrue(regions().isEmpty(), "a payload for a different dimension than the current one must be dropped");
        assertFalse(isDirty());
    }

    @Test
    void recordingANewChunkMarksTheStoreDirty() throws Exception {
        seedIdentifiedWorld(OVERWORLD);

        LodMemory.record(OVERWORLD, 5, 9);

        assertFalse(regions().isEmpty(), "a matching-dimension chunk must be recorded");
        assertTrue(isDirty(), "new data must be flagged for the next flush");
    }

    /**
     * Mirrors the comment on {@code record}: a chunk already set in its region mask must not
     * re-flip dirty. If it did, LodMemory would flush on every single re-ingest of terrain it
     * already knows about, not just on genuinely new data.
     */
    @Test
    void recordingTheSameChunkTwiceIsNotDirtyTheSecondTime() throws Exception {
        seedIdentifiedWorld(OVERWORLD);
        LodMemory.record(OVERWORLD, 5, 9);
        setStatic("dirty", false); // simulate: the first record was already flushed

        LodMemory.record(OVERWORLD, 5, 9); // same chunk again

        assertFalse(isDirty(), "re-recording an already-known chunk must not mark the store dirty again");
    }

    @Test
    void recordingADifferentChunkStillMarksDirty() throws Exception {
        seedIdentifiedWorld(OVERWORLD);
        LodMemory.record(OVERWORLD, 5, 9);
        setStatic("dirty", false);

        LodMemory.record(OVERWORLD, 6, 9); // a genuinely new chunk

        assertTrue(isDirty(), "a distinct chunk must mark the store dirty even if others were already known");
    }

    /**
     * onDisconnect must leave the class ready for an entirely new world on the next join --
     * nothing about the previous server/save may leak into the next connection.
     */
    @Test
    void onDisconnectClearsEveryFieldTiedToTheOldConnection() throws Exception {
        seedIdentifiedWorld(OVERWORLD);
        LodMemory.record(OVERWORLD, 1, 1);
        setStatic("dirty", false); // sidestep flush()'s FabricLoader call -- see class Javadoc
        setStatic("pendingUpload", true);
        setStatic("uploadAttempts", 2);
        setStatic("unidentifiedWorldLogged", true);

        LodMemory.onDisconnect();

        assertNull(getStatic("worldKey"));
        assertNull(getStatic("dimensionId"));
        assertNull(getStatic("dimension"));
        assertTrue(regions().isEmpty(), "the known-chunk set for the old world must not survive into the next connection");
        assertEquals(Boolean.FALSE, getStatic("pendingUpload"));
        assertEquals(0, getStatic("uploadAttempts"));
        assertEquals(Boolean.FALSE, getStatic("unidentifiedWorldLogged"));
    }

    /** After onDisconnect, record() must behave exactly as it does on a class that never connected. */
    @Test
    void afterOnDisconnectRecordIsANoOpAgain() throws Exception {
        seedIdentifiedWorld(OVERWORLD);
        LodMemory.record(OVERWORLD, 1, 1);
        setStatic("dirty", false);

        LodMemory.onDisconnect();
        LodMemory.record(OVERWORLD, 2, 2);

        assertTrue(regions().isEmpty(), "with no identified world, record() must drop the chunk exactly as before any connection");
    }

    // --- reflection plumbing -------------------------------------------------------------------
    // LodMemory's only entry point for worldKey/dimensionId/dimension is the private switchTo(),
    // which requires a live Minecraft client. Seeding these fields directly is the only way to
    // reach record()/onDisconnect()'s real logic without one.

    private void seedIdentifiedWorld(ResourceKey<Level> dim) throws Exception {
        setStatic("worldKey", "test-world-key");
        setStatic("dimensionId", dim.identifier().toString());
        setStatic("dimension", dim);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, byte[]> regions() throws Exception {
        Field f = LodMemory.class.getDeclaredField("regions");
        f.setAccessible(true);
        return (Map<Long, byte[]>) f.get(null);
    }

    private static boolean isDirty() throws Exception {
        return (boolean) getStatic("dirty");
    }

    private static Object getStatic(String name) throws Exception {
        Field f = LodMemory.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setStatic(String name, Object value) throws Exception {
        Field f = LodMemory.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
}
