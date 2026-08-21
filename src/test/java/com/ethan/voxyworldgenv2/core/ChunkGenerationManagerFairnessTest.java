package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code rotated}, the fairness-cursor helper: without it a plain first-match scan over the player
 * list always gives every frontier generation batch to whichever player happens to be first in
 * {@code PlayerTracker}'s iteration order, and everyone after them gets zero new generation for as
 * long as that first player still has an unfilled frontier.
 */
class ChunkGenerationManagerFairnessTest {

    @Test
    void offsetZeroReturnsTheOriginalOrder() {
        List<String> players = List.of("alice", "bob", "carol");
        assertEquals(players, ChunkGenerationManager.rotated(players, 0));
    }

    @Test
    void rotatesLeftByTheGivenOffset() {
        List<String> players = List.of("alice", "bob", "carol", "dave");
        assertEquals(List.of("bob", "carol", "dave", "alice"), ChunkGenerationManager.rotated(players, 1));
        assertEquals(List.of("carol", "dave", "alice", "bob"), ChunkGenerationManager.rotated(players, 2));
    }

    @Test
    void wrapsAroundForAnOffsetLargerThanTheListSize() {
        List<String> players = List.of("alice", "bob", "carol");
        // offset 4 on a 3-player list is the same as offset 1
        assertEquals(List.of("bob", "carol", "alice"), ChunkGenerationManager.rotated(players, 4));
    }

    @Test
    void everyPlayerEventuallyReachesTheFrontOfTheRotationOverASequenceOfPasses() {
        // This is the actual fairness guarantee: cycling the cursor 0..n-1 must put every player
        // first exactly once, so nobody is permanently stuck behind another player's frontier.
        List<String> players = List.of("alice", "bob", "carol", "dave");
        var seenFirst = new java.util.HashSet<String>();
        for (int cursor = 0; cursor < players.size(); cursor++) {
            seenFirst.add(ChunkGenerationManager.rotated(players, cursor).get(0));
        }
        assertEquals(new java.util.HashSet<>(players), seenFirst);
    }

    @Test
    void aSingletonListIsUnaffectedByAnyOffset() {
        List<String> solo = List.of("alice");
        assertEquals(solo, ChunkGenerationManager.rotated(solo, 0));
        assertEquals(solo, ChunkGenerationManager.rotated(solo, 5));
    }
}
