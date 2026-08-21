package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Coverage for defect #4 (convergence-audit-reverse.md finding #4): {@code Config.stuckTaskTimeoutSeconds}
 * was declared but read nowhere, so a generation task whose future never resolved (chunk system
 * parked the dimension, a wedged {@code getChunkFutureMainThread}, etc.) held its throttle permit
 * forever with no recovery path. {@code reapStuckTasks()} was ported from port/26.2 and is now
 * called every ~1s from {@code tick()} alongside {@code PlayerTracker.reconcile}.
 *
 * <p>{@code reapStuckTasks}'s actual reaping logic walks {@code dimensionStates}, whose values hold
 * a real {@code ServerLevel} -- not constructible in this harness (see
 * PlayerTrackerIdentityKeyingTest's javadoc for why). {@code dimensionStates} is empty on a
 * never-initialized singleton, which cannot exercise the reap itself, but it is exactly the state
 * {@code reapStuckTasks} sees on every call before the world loads and on every call in a
 * dimension with no in-flight tasks -- the overwhelmingly common case in production. This locks in
 * that both the config-off guard and the empty-state walk are safe to call from {@code tick()}
 * without throwing, which is what every ~1s call site actually needs.
 */
class ChunkGenerationManagerStuckTaskReaperTest {

    private final ChunkGenerationManager manager = ChunkGenerationManager.getInstance();

    @BeforeEach
    void setUp() {
        Config.DATA = new Config.ConfigData();
    }

    @Test
    void aZeroTimeoutShortCircuitsWithoutTouchingAnyState() {
        Config.DATA.stuckTaskTimeoutSeconds = 0;

        assertDoesNotThrow(this::invokeReapStuckTasks,
            "0 means the feature is off (Config's own default-comment convention), must not throw");
    }

    @Test
    void aPositiveTimeoutWithNoTrackedDimensionsIsANoOp() {
        Config.DATA.stuckTaskTimeoutSeconds = 60;

        assertDoesNotThrow(this::invokeReapStuckTasks,
            "no dimension has been set up yet (dimensionStates is empty) -- must not throw, "
                + "since tick() calls this once a second regardless of world state");
    }

    private void invokeReapStuckTasks() throws Exception {
        Method m = ChunkGenerationManager.class.getDeclaredMethod("reapStuckTasks");
        m.setAccessible(true);
        try {
            m.invoke(manager);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
