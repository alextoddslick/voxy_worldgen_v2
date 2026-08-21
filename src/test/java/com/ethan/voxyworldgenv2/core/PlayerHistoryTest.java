package com.ethan.voxyworldgenv2.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The admin Players tab shows every player who has ever joined — including offline ones — so
 * their name, last-seen time, lifetime traffic and last disk report must survive a restart.
 * Overrides already persist in the config; this sidecar carries the rest.
 */
class PlayerHistoryTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void recordsAndRoundTripsThroughDisk(@TempDir Path dir) {
        Path file = dir.resolve("players.json");
        PlayerHistory history = new PlayerHistory(file);
        history.record(ALICE, "VoxyTester", 9_650_000L, 165_000L, 1000L);
        history.record(BOB, "Player977", 12_000_000L, -1L, 2000L);
        history.save();

        PlayerHistory reloaded = new PlayerHistory(file);
        PlayerHistory.Entry alice = reloaded.get(ALICE);
        assertNotNull(alice);
        assertEquals("VoxyTester", alice.name);
        assertEquals(9_650_000L, alice.wireBytes);
        assertEquals(165_000L, alice.diskBytes);
        assertEquals(1000L, alice.lastSeenMs);
        assertEquals(2, reloaded.entries().size());
    }

    @Test
    void reRecordingUpdatesInsteadOfDuplicating(@TempDir Path dir) {
        PlayerHistory history = new PlayerHistory(dir.resolve("players.json"));
        history.record(ALICE, "VoxyTester", 100L, -1L, 1000L);
        history.record(ALICE, "VoxyTester", 900L, 55L, 2000L);

        assertEquals(1, history.entries().size());
        PlayerHistory.Entry e = history.get(ALICE);
        assertEquals(900L, e.wireBytes);
        assertEquals(55L, e.diskBytes);
        assertEquals(2000L, e.lastSeenMs);
    }

    @Test
    void aMissingDiskReportNeverErasesAKnownOne(@TempDir Path dir) {
        // Disk reports arrive on their own cadence; a session that ends before the first report
        // must not wipe the figure the last session established.
        PlayerHistory history = new PlayerHistory(dir.resolve("players.json"));
        history.record(ALICE, "VoxyTester", 100L, 3_100_000_000L, 1000L);
        history.record(ALICE, "VoxyTester", 200L, -1L, 2000L);

        assertEquals(3_100_000_000L, history.get(ALICE).diskBytes);
    }

    @Test
    void missingFileMeansEmptyHistoryNotAnError(@TempDir Path dir) {
        PlayerHistory history = new PlayerHistory(dir.resolve("never-written.json"));
        assertTrue(history.entries().isEmpty());
        assertNull(history.get(ALICE));
    }

    @Test
    void sessionRecordingAccumulatesAcrossSessionsWithoutDoubleCounting(@TempDir Path dir) {
        // The live queue counts bytes since server start; recordSession is called repeatedly
        // (snapshot builds, disconnect) with that same running figure, so it must add only the
        // delta since its last call — and a fresh server run starts a fresh delta baseline.
        PlayerHistory history = new PlayerHistory(dir.resolve("players.json"));
        history.recordSession(ALICE, "VoxyTester", 100L, -1L, 1000L);
        history.recordSession(ALICE, "VoxyTester", 250L, -1L, 2000L);
        assertEquals(250L, history.get(ALICE).wireBytes, "same-run calls must not double count");
        history.save();

        // New server run: live counter restarts at 0 and grows to 40.
        PlayerHistory next = new PlayerHistory(dir.resolve("players.json"));
        next.recordSession(ALICE, "VoxyTester", 40L, -1L, 3000L);
        assertEquals(290L, next.get(ALICE).wireBytes, "new run adds on top of the stored total");
    }
}
