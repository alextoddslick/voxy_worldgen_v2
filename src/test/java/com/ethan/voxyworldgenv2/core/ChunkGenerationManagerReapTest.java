package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The empty-player-list interaction between {@code reapStuckTasks} and spawn pre-generation.
 *
 * <p>Before spawn pregen existed, an empty dimension was always a dimension the chunk system had
 * parked -- nobody was going to dispatch new work into it, so {@code reapStuckTasks} gave its
 * in-flight tasks only a short 5-second grace before reclaiming their permits. Spawn pregen breaks
 * that assumption: it deliberately dispatches player-anchored work when the player list is empty
 * (see {@code ChunkGenerationManager.dispatchSpawnPregen}), so on a server with nobody online at
 * all, the overworld's in-flight pregen tasks are NOT dead weight -- they are the only work
 * running. Judging them against the short grace would reap and restart them in a loop, since real
 * chunk generation routinely takes longer than 5 seconds under load, and an empty BMC3 server would
 * again generate nothing, just less obviously than before.
 *
 * <p>{@code getsFullStuckTaskTimeout} is the extracted decision this test exercises directly,
 * without needing a live {@code MinecraftServer}/{@code PlayerTracker}.
 */
class ChunkGenerationManagerReapTest {

    @Test
    void anOccupiedDimensionAlwaysGetsTheFullTimeout() {
        assertTrue(ChunkGenerationManager.getsFullStuckTaskTimeout(true, true),
            "a dimension someone is standing in is never treated as parked");
    }

    @Test
    void anUnoccupiedDimensionGetsTheShortGraceWhenSomeoneIsOnlineElsewhere() {
        // The ordinary case the short grace exists for: a player teleported away from a dimension
        // that still has in-flight tasks, and generation is needed where the player actually is.
        assertFalse(ChunkGenerationManager.getsFullStuckTaskTimeout(true, false),
            "a dimension nobody occupies, while someone is online elsewhere, is the short-grace case");
    }

    @Test
    void anEmptyServerAlwaysGetsTheFullTimeoutEvenForAnUnoccupiedDimension() {
        // The fix: with nobody online ANYWHERE, there is no occupied dimension whose permits need
        // protecting from an idle one -- so even a dimension nobody is standing in (which is
        // exactly where the spawn pregen anchor runs) must not be judged against the short grace.
        assertTrue(ChunkGenerationManager.getsFullStuckTaskTimeout(false, false),
            "with no players online anywhere, the spawn pregen anchor's own tasks must get the full timeout");
    }

    @Test
    void anyoneOnlineAloneDoesNotOverrideOccupancyEitherDirection() {
        // Sanity: the two inputs are independent; "anyone online" only matters when it's false.
        assertTrue(ChunkGenerationManager.getsFullStuckTaskTimeout(false, true));
    }
}
