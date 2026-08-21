package com.ethan.voxyworldgenv2.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for defect #3 (convergence-audit-reverse.md finding #3): unified declared
 * {@code generationPausedUntilMs} and {@link ChunkGenerationManager#isTransitionPaused()} but
 * nothing ever called the setter side, so the join/dimension-change generation pause was
 * permanently decorative. {@code pauseForTransition} + its {@code checkPlayerMovement} wiring were
 * ported from port/26.2, adapted to unified's own {@code lastPlayerLevels} bookkeeping (unified's
 * {@code PlayerTracker.lastDimension} is already primed at {@code addPlayer}, so it cannot itself
 * signal a join the way port/26.2's local map does).
 *
 * <p>{@code ChunkGenerationManager.getInstance()} needs a live {@code MinecraftServer} for almost
 * everything else, so this deliberately exercises only the pure pause-window arithmetic.
 * {@code pauseForTransition}'s only Minecraft-typed parameter is a {@code ResourceKey<Level>},
 * which -- like {@code LodMemoryTest}'s OVERWORLD/NETHER constants -- is real and lightweight and,
 * unlike a {@code ServerPlayer} or {@code ServerLevel}, does not require vanilla's
 * {@code Bootstrap.bootStrap()} to construct (see PlayerTrackerIdentityKeyingTest's javadoc for why
 * that matters in this harness). {@code isSingleplayer()} reads a null {@code server} field as
 * "not singleplayer" without touching it, so the dedicated-server config branch is exercised.
 */
class ChunkGenerationManagerTransitionPauseTest {

    private static final ResourceKey<Level> OVERWORLD =
        ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));

    private final ChunkGenerationManager manager = ChunkGenerationManager.getInstance();

    @BeforeEach
    void setUp() throws Exception {
        Config.DATA = new Config.ConfigData();
        setPausedUntil(0L);
    }

    @AfterEach
    void tearDown() throws Exception {
        setPausedUntil(0L);
    }

    @Test
    void freshInstanceIsNotPaused() {
        assertFalse(manager.isTransitionPaused());
    }

    @Test
    void pauseForTransitionArmsTheWindow() throws Exception {
        Config.DATA.dimensionChangePauseSeconds = 15;

        invokePauseForTransition("Steve", OVERWORLD, true);

        assertTrue(manager.isTransitionPaused(), "just paused -- the window must be open");
    }

    @Test
    void theWindowExpiresRatherThanStayingPausedForever() throws Exception {
        Config.DATA.dimensionChangePauseSeconds = 15;
        invokePauseForTransition("Steve", OVERWORLD, true);
        assertTrue(manager.isTransitionPaused(), "sanity: just paused");

        // Backdate the window the same way JoinGateTest backdates the join gate's arm time,
        // rather than sleeping past a real 15s window.
        setPausedUntil(System.currentTimeMillis() - 1);

        assertFalse(manager.isTransitionPaused(), "an expired window must not pause generation forever");
    }

    @Test
    void aZeroConfiguredPauseIsANoOp() throws Exception {
        Config.DATA.dimensionChangePauseSeconds = 0;

        invokePauseForTransition("Steve", OVERWORLD, true);

        assertFalse(manager.isTransitionPaused(),
            "0 means the feature is off, matching Config's own \"0 = no transition pause\" comment");
    }

    @Test
    void bothJoinAndDimensionChangeArmTheSameWindow() throws Exception {
        // pauseForTransition's isJoin parameter only changes the log message; the window it arms
        // is identical either way, so both call shapes checkPlayerMovement uses must pause.
        Config.DATA.dimensionChangePauseSeconds = 15;

        invokePauseForTransition("Steve", OVERWORLD, false); // dimension change, not a join

        assertTrue(manager.isTransitionPaused());
    }

    // --- reflection plumbing ---------------------------------------------------------------
    // pauseForTransition is private and generationPausedUntilMs has no public setter -- both are
    // reached exactly the way JoinGateTest reaches PlayerTracker's private timing state.

    private void invokePauseForTransition(String playerName, ResourceKey<Level> dim, boolean isJoin) throws Exception {
        Method m = ChunkGenerationManager.class.getDeclaredMethod(
            "pauseForTransition", String.class, ResourceKey.class, boolean.class);
        m.setAccessible(true);
        m.invoke(manager, playerName, dim, isJoin);
    }

    private void setPausedUntil(long value) throws Exception {
        Field f = ChunkGenerationManager.class.getDeclaredField("generationPausedUntilMs");
        f.setAccessible(true);
        f.set(manager, value);
    }
}
