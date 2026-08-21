package com.ethan.voxyworldgenv2.core;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * findWork must eventually run out of work.
 *
 * <p>The graph records completion in 4x4 batches and {@code markChunkCompleted} flags a batch full
 * only once all sixteen of its bits are set. So any batch findWork hands out in part can never be
 * marked full, and findWork offers it again on the very next call. dispatchBatch then finds every
 * chunk in it already generated, returns 0, and dispatchGeneration's loop -- which only advances on
 * a non-zero return, unlike dispatchSpawnPregen which has carried a sent == 0 guard since it was
 * written -- spins forever.
 *
 * <p>That is not hypothetical. On 2026-08-20 it pinned the live 26.2 server's Voxy-WorldGen-Worker
 * at 99% of a core with generation frozen for an hour: 145,091 chunks done, 145,091 queued,
 * 0 active tasks, every thread dump inside DistanceGraph.findWork. Because the worker never
 * returned from dispatchGeneration it also never re-read the player list, so it stayed anchored to
 * a player who had since disconnected and ignored two teleports into fresh terrain.
 *
 * <p>These tests drive findWork exactly the way dispatchBatch does, including the untracking that
 * puts a batch straight back into play, and assert the loop terminates.
 */
class DistanceGraphWorkTerminationTest {

    /** maxActiveTasks-sized passes over a radius-128 ring need a few thousand calls, never 100,000. */
    private static final int CALL_LIMIT = 100_000;

    @Test
    void findWorkRunsOutOfWorkAtTheRadiusBoundary() {
        drainRadius(new ChunkPos(0, 0), 128);
    }

    /**
     * The boundary ring depends on where the centre sits inside its own batch, so a centre that is
     * not batch-aligned exercises a different set of partial batches.
     */
    @Test
    void findWorkRunsOutOfWorkFromAnUnalignedCentre() {
        drainRadius(new ChunkPos(-313, 311), 128);
    }

    /** A small radius has proportionally more boundary than interior, so it fails faster. */
    @Test
    void findWorkRunsOutOfWorkAtASmallRadius() {
        drainRadius(new ChunkPos(0, 0), 12);
    }

    /**
     * A batch can also end up permanently below 0xFFFF without any geometry being involved -- a
     * lost bit in recursiveMark's mask update leaves it fully generated but not full. dispatchBatch
     * (here, ChunkGenerationManager.onSuccess) re-asserts markChunkCompleted for anything already
     * in completedChunks precisely so the graph heals itself on the batch's first offer rather than
     * being re-offered forever.
     *
     * <p>dispatchBatch needs a live server, so this specifies the policy rather than calling it.
     * Verified against a negative control: with the re-assert removed the batch is re-offered
     * 100,001 times, the same unbounded loop this test guards against.
     */
    @Test
    void aBatchWhoseGraphBitWasLostIsRetiredOnItsFirstOffer() {
        DistanceGraph graph = new DistanceGraph();
        Set<Long> trackedBatches = ConcurrentHashMap.newKeySet();
        Set<Long> completedChunks = new HashSet<>();
        ChunkPos centre = new ChunkPos(0, 0);
        int radius = 12;

        // generate the whole radius, but drop one chunk's bit on the floor on the way into the
        // graph -- it is in completedChunks, so it is never dispatched again to re-mark itself
        ChunkPos lost = new ChunkPos(5, 5);
        int calls = 0;
        List<ChunkPos> batch;
        while ((batch = graph.findWork(centre, radius, trackedBatches)) != null) {
            assertTrue(++calls <= CALL_LIMIT, "did not converge while seeding the graph");
            ChunkPos head = batch.get(0);
            long batchKey = DistanceGraph.getBatchKey(head.x(), head.z());
            for (ChunkPos pos : batch) {
                long key = pos.pack();
                if (completedChunks.add(key)) {
                    boolean isTheLostOne = pos.x() == lost.x() && pos.z() == lost.z();
                    if (!isTheLostOne) {
                        graph.markChunkCompleted(pos.x(), pos.z());
                    }
                }
            }
            trackedBatches.remove(batchKey);
            if (calls > 4000) break;
        }

        // now drain again with the onSuccess re-assert in place
        int sterile = 0;
        calls = 0;
        while ((batch = graph.findWork(centre, radius, trackedBatches)) != null) {
            calls++;
            assertTrue(calls <= CALL_LIMIT, "a batch with a lost graph bit was re-offered "
                + calls + " times; the re-assert in onSuccess is what retires it");
            ChunkPos head = batch.get(0);
            long batchKey = DistanceGraph.getBatchKey(head.x(), head.z());
            boolean anyNewWork = false;
            for (ChunkPos pos : batch) {
                long key = pos.pack();
                if (completedChunks.contains(key)) {
                    // ChunkGenerationManager.onSuccess: completedChunks is the source of truth, so
                    // re-assert it into the derived index
                    graph.markChunkCompleted(pos.x(), pos.z());
                } else {
                    completedChunks.add(key);
                    graph.markChunkCompleted(pos.x(), pos.z());
                    anyNewWork = true;
                }
            }
            if (!anyNewWork) sterile++;
            trackedBatches.remove(batchKey);
        }
        assertTrue(sterile <= 1, "the lost-bit batch should be retired on its first offer, "
            + "but it came back " + sterile + " times");
    }

    /**
     * Re-enacts ChunkGenerationManager's worker loop: take whatever findWork offers, generate it,
     * mark it, and release the batch tracking the way decrementBatch and onSuccess do.
     */
    private void drainRadius(ChunkPos centre, int radius) {
        DistanceGraph graph = new DistanceGraph();
        Set<Long> trackedBatches = ConcurrentHashMap.newKeySet();
        Set<Long> completedChunks = new HashSet<>();

        int calls = 0;
        int sterileBatches = 0;
        List<ChunkPos> batch;
        while ((batch = graph.findWork(centre, radius, trackedBatches)) != null) {
            calls++;
            assertTrue(calls <= CALL_LIMIT, "findWork never ran out of work around " + centre
                + " at radius " + radius + ": " + calls + " calls, " + sterileBatches
                + " of them offering a batch that was already fully generated. This is the loop that"
                + " froze generation on the live server.");

            ChunkPos head = batch.get(0);
            long batchKey = DistanceGraph.getBatchKey(head.x(), head.z());

            boolean anyNewWork = false;
            for (ChunkPos pos : batch) {
                if (completedChunks.add(pos.pack())) {
                    graph.markChunkCompleted(pos.x(), pos.z());
                    anyNewWork = true;
                }
            }
            if (!anyNewWork) sterileBatches++;

            // both the completed path (decrementBatch drains the counter) and the
            // nothing-to-do path (onSuccess), drop the key, so the batch is immediately
            // eligible again -- which is what makes the spin unbounded
            trackedBatches.remove(batchKey);
        }

        assertEquals(0, sterileBatches, "findWork offered " + sterileBatches
            + " batches whose chunks were all already generated; dispatchBatch returns 0 for each of"
            + " those and dispatchGeneration makes no progress");
    }
}
