package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import com.ethan.voxyworldgenv2.integration.tellus.TellusIntegration;

import com.ethan.voxyworldgenv2.mixin.ServerChunkCacheMixin;
import com.ethan.voxyworldgenv2.stats.GenerationStats;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.UUID;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkGenerationManager {
    private static final ChunkGenerationManager INSTANCE = new ChunkGenerationManager();
    
    private static class DimensionState {
        final ServerLevel level;
        final LongSet completedChunks = LongSets.synchronize(new LongOpenHashSet());
        final LongSet trackedChunks = LongSets.synchronize(new LongOpenHashSet());
        final DistanceGraph distanceGraph = new DistanceGraph();
        final Set<Long> trackedBatches = ConcurrentHashMap.newKeySet();
        final Map<Long, AtomicInteger> batchCounters = new ConcurrentHashMap<>();
        final Map<Long, Long> taskStartMs = new ConcurrentHashMap<>();
        final AtomicInteger remainingInRadius = new AtomicInteger(0);
        // chunks that failed to finish, retry count so findWork stops looping on the same batch
        // forever instead of re-offering a chunk that keeps failing.
        final Map<Long, Integer> failCounts = new ConcurrentHashMap<>();
        boolean tellusActive = false;
        boolean loaded = false;

        DimensionState(ServerLevel level) {
            this.level = level;
        }
    }

    // give up on a chunk after this many failed tries, forcing its batch bit so findWork
    // doesn't re-offer it forever (see onFailure).
    private static final int MAX_CHUNK_RETRIES = 3;

    private final Map<ResourceKey<Level>, DimensionState> dimensionStates = new ConcurrentHashMap<>();
    
    // global state
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);

    // Catch-up force-loads chunks off disk, and it runs every worker iteration (10ms apart when it
    // has work). Without a ceiling on outstanding loads it force-loads faster than the chunk system
    // retires them and the heap is gone in seconds - the generator has maxActiveTasks for exactly
    // this reason, and this path needs its own.
    private static final int MAX_CATCHUP_LOADS_IN_FLIGHT = 128;
    private final AtomicInteger catchUpLoadsInFlight = new AtomicInteger(0);
    private final GenerationStats stats = new GenerationStats();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean configReloadScheduled = new AtomicBoolean(false);
    private int playerReconcileTicks = 0;
    
    // components
    private final TpsMonitor tpsMonitor = new TpsMonitor();
    private Semaphore throttle;
    private MinecraftServer server;
    private ResourceKey<Level> currentDimensionKey = null;
    private ServerLevel currentLevel = null;
    private final java.util.Map<java.util.UUID, ChunkPos> lastPlayerPositions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, ResourceKey<Level>> lastPlayerLevels = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.BooleanSupplier pauseCheck = () -> false;

    // While set in the future, the worker dispatches nothing — used to yield the chunk system
    // to a player joining or changing dimension (see Config.dimensionChangePauseSeconds).
    private volatile long generationPausedUntilMs = 0;

    // periodic console progress logging. lastLoggedCompleted starts equal to the stats counter
    // (0) so an idle server does not produce a spurious "generating ... 0 done" line on boot.
    private long lastProgressLogMs = 0;
    private long lastLoggedCompleted = 0;
    private boolean wasGenerating = false;

    // worker
    private Thread workerThread;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    // rotated every pass so one player with a big frontier can't starve the others: without
    // this, a plain first-match scan over the player list gives every frontier batch to
    // whichever player happens to be first, and everyone after them gets zero new generation.
    private int fairnessCursor = 0;
    
    // c2me compatibility - queue ticket operations to process at safe time
    private record TicketOp(ServerLevel level, ChunkPos pos, boolean add) {}
    private final ConcurrentLinkedQueue<TicketOp> pendingTicketOps = new ConcurrentLinkedQueue<>();

    // Every FORCED ticket currently outstanding, so a graceful shutdown can release all of them
    // regardless of whether each chunk's own generation future had already completed and called
    // cleanupTask. Matters more now that spawn pre-generation can leave many more tickets
    // outstanding for much longer than the old player-anchored-only worker ever did: FORCED
    // carries FLAG_PERSIST, so a ticket left behind is written into the world's chunk_tickets
    // SavedData and reactivated by MinecraftServer.prepareLevels() on the next boot, which blocks
    // "Loading initial chunks" until every one of them reaches FULL.
    private final Map<ServerLevel, LongSet> appliedTickets = new ConcurrentHashMap<>();

    private ChunkGenerationManager() {}
    
    public static ChunkGenerationManager getInstance() {
        return INSTANCE;
    }

    private DimensionState getOrSetupState(ServerLevel level) {
        return dimensionStates.computeIfAbsent(level.dimension(), k -> {
            DimensionState state = new DimensionState(level);
            state.tellusActive = TellusIntegration.isTellusWorld(level);
            return state;
        });
    }

    public ServerLevel getCurrentLevel() {
        return currentLevel;
    }

    public boolean isSingleplayer() {
        return server != null && server.isSingleplayer();
    }
    
    public void initialize(MinecraftServer server) {
        this.server = server;
        this.running.set(true);
        // unpaused by default
        this.pauseCheck = () -> false; 
        Config.load();
        this.throttle = new Semaphore(Config.getMaxActiveTasks(isSingleplayer()));
        com.ethan.voxyworldgenv2.network.LodSendQueue.getInstance().start();
        startWorker();
        VoxyWorldGenV2.LOGGER.info("voxy world gen initialized");
    }
    
    public void shutdown() {
        running.set(false);
        stopWorker();
        com.ethan.voxyworldgenv2.network.LodSendQueue.getInstance().shutdown();
        TellusIntegration.shutdown();

        // clear our tickets or the world can hang on the save/stopping screen, and a leftover
        // FORCED ticket would otherwise persist into the next boot (see appliedTickets javadoc).
        releaseAllTickets();

        for (var entry : dimensionStates.entrySet()) {
            DimensionState state = entry.getValue();
            if (state.loaded) {
                ChunkPersistence.save(state.level, entry.getKey(), state.completedChunks);
            }
        }
        
        com.ethan.voxyworldgenv2.command.TabHud.clear();
        dimensionStates.clear();
        pendingTicketOps.clear();
        server = null;
        stats.reset();
        activeTaskCount.set(0);
        catchUpLoadsInFlight.set(0);
        tpsMonitor.reset();
        currentDimensionKey = null;
        currentLevel = null;
        lastPlayerPositions.clear();
        lastPlayerLevels.clear();
        generationPausedUntilMs = 0;
        lastProgressLogMs = 0;
        lastLoggedCompleted = 0;
        wasGenerating = false;
    }

    private void startWorker() {
        if (workerRunning.getAndSet(true)) return;
        workerThread = new Thread(this::workerLoop, "Voxy-WorldGen-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void stopWorker() {
        workerRunning.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                // wait up to 5 seconds for worker to die
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    /**
     * Streams already-generated-but-unsynced chunks near each player, nearest first (one batch of
     * up to 64 per call, one player per call). Returns true if a batch was dispatched. Bandwidth
     * stays bounded by LodSendQueue's per-player token bucket regardless of call frequency.
     */
    private boolean dispatchCatchUpSync(List<ServerPlayer> players) {
        // Every player gets a pass, not just the first one with work: the outstanding-load
        // ceiling below is the real back-pressure, so there is no reason to make every other
        // player wait a full worker iteration behind whichever player happens to be first in
        // the list.
        boolean dispatchedAny = false;
        for (ServerPlayer player : players) {
            var synced = PlayerTracker.getInstance()
                .getSyncedChunks(player.getUUID(), player.level().dimension());
            if (synced == null) continue;

            DimensionState ds = getOrSetupState((ServerLevel) player.level());
            String dimId = PlayerTracker.dimensionId(player.level().dimension());
            int genRadius = Config.getGenerationRadius(isSingleplayer());
            int baseRadius = ds.tellusActive
                ? Math.max(genRadius, 128) : genRadius;
            // A /voxygen refresh can ask for a wider sweep than generationRadius. Widening
            // the catch-up radius is what re-sends those chunks without a second send path.
            int refreshOverride = PlayerTracker.getInstance()
                .getRefreshRadius(player.getUUID(), dimId);
            // ...but never wider than the player's send distance: chunks the broadcast path
            // refuses must not be claimable here, or they'd be marked synced and never arrive.
            int radius = com.ethan.voxyworldgenv2.network.NetworkHandler.capCatchUpRadius(
                Math.max(baseRadius, refreshOverride),
                Config.getSendDistanceForPlayer(player.getUUID(), isSingleplayer()));

            // Never claim more than the outstanding-load ceiling allows: the batch is pre-marked
            // synced below, so anything claimed and then dropped for lack of budget would be lost.
            int loadBudget = MAX_CATCHUP_LOADS_IN_FLIGHT - catchUpLoadsInFlight.get();
            if (loadBudget <= 0) return dispatchedAny;

            List<ChunkPos> syncBatch = new ArrayList<>();
            ds.distanceGraph.collectCompletedInRange(
                player.chunkPosition(), radius, synced, syncBatch, Math.min(64, loadBudget));

            if (syncBatch.isEmpty() && refreshOverride > 0) {
                PlayerTracker.getInstance().clearRefreshRadius(player.getUUID(), dimId);
            }

            if (!syncBatch.isEmpty()) {
                final List<ChunkPos> finalSyncBatch = new ArrayList<>(syncBatch);
                final ServerLevel level = ds.level;
                final UUID playerUUID = player.getUUID();
                // mark all as synced now so we don't retry unloaded chunks in a tight loop;
                // chunks that turn out not to be resident are additionally recorded as
                // deferred below, which is what makes this mark mean "claimed" rather
                // than "delivered" and lets onChunkLoad still send them.
                for (ChunkPos syncPos : finalSyncBatch) {
                    synced.add(syncPos.toLong());
                }
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayer(playerUUID);
                    if (p != null) {
                        var store = PlayerTracker.getInstance().getStore(playerUUID);
                        ServerChunkCache cache = level.getChunkSource();
                        List<ChunkPos> notResident = new ArrayList<>();

                        for (ChunkPos syncPos : finalSyncBatch) {
                            // getChunkNow, not getChunk(x, z, false): the false only skips adding a
                            // ticket, it does NOT make the call non-blocking. Under C2ME a holder can
                            // sit at FULL ticket level with an incomplete FULL future that nothing will
                            // ever drive, and getChunk then parks the main thread until the watchdog
                            // kills the server (BMC3 17:03 crash)
                            LevelChunk c = cache.getChunkNow(syncPos.x, syncPos.z);
                            if (c != null) {
                                com.ethan.voxyworldgenv2.network.NetworkHandler.sendLODData(p, c);
                            } else {
                                notResident.add(syncPos);
                            }
                        }

                        // A world generated by earlier play has its chunks on disk, not in memory, so
                        // getChunkNow misses nearly all of them. Deferring those to onChunkLoad only
                        // delivers them once the player walks in, which leaves exactly the hole this
                        // catch-up exists to fill. Pull them in off-thread instead, the same way the
                        // generator does below: FORCED ticket, then the main-thread chunk future.
                        // Bounded by the 64-per-batch cap and the worker's 3/4-full send-queue
                        // backpressure.
                        if (!notResident.isEmpty()) {
                            catchUpLoadsInFlight.addAndGet(notResident.size());
                            for (ChunkPos pos : notResident) {
                                queueTicketAdd(level, pos);
                            }
                            processPendingTickets();

                            for (ChunkPos pos : notResident) {
                                ((ServerChunkCacheMixin) cache)
                                    .invokeGetChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true)
                                    .whenCompleteAsync((result, throwable) -> {
                                        ServerPlayer target = server.getPlayerList().getPlayer(playerUUID);
                                        // Track whether the send ACTUALLY happened. Attaching the
                                        // deferred-mark to an else-if on the outer condition let a
                                        // successful load whose send was skipped -- player logged off
                                        // mid-load, or an empty chunk -- fall through both branches:
                                        // not sent, not deferred, still claimed from the pre-mark
                                        // above. That chunk was then lost for the rest of the session.
                                        boolean sent = false;
                                        if (throwable == null && result != null && result.isSuccess()
                                                && result.orElse(null) instanceof LevelChunk chunk) {
                                            if (target != null && !chunk.isEmpty()) {
                                                com.ethan.voxyworldgenv2.network.NetworkHandler.sendLODData(target, chunk);
                                                sent = true;
                                            }
                                        }
                                        if (!sent && store != null && Config.DATA.rememberSentChunks) {
                                            // Claimed but not delivered; onChunkLoad re-sends it on
                                            // the next load.
                                            store.markDeferred(dimId, pos.toLong());
                                        }
                                        // Release the ticket only; this chunk was never a generation
                                        // task, so the generation bookkeeping in cleanupTask/onSuccess
                                        // must not run for it.
                                        queueTicketRemove(level, pos);
                                        catchUpLoadsInFlight.decrementAndGet();
                                    }, server);
                            }
                        }
                    }
                });
                dispatchedAny = true;
            }
        }
        return dispatchedAny;
    }

    private void workerLoop() {
        while (workerRunning.get() && running.get()) {
            try {
                if (!Config.DATA.enabled || server == null) {
                    Thread.sleep(100);
                    continue;
                }

                if (!VoxyIntegration.isVoxyRenderingEnabled()) {
                    Thread.sleep(500);
                    continue;
                }

                if (tpsMonitor.isThrottled() || pauseCheck.getAsBoolean()) {
                    Thread.sleep(500);
                    continue;
                }

                // Ease off gradually between the soft (~22 tps) and hard (~13 tps) floors instead
                // of running flat-out until isThrottled() trips at the hard one and then stopping
                // dead. loadFactor is 1.0 while healthy, so this costs nothing on a healthy server.
                double loadFactor = tpsMonitor.loadFactor();
                if (loadFactor < 1.0) {
                    Thread.sleep((long) (500 * (1.0 - loadFactor)));
                }

                // Yield entirely while a player is loading into a dimension (join or teleport).
                if (System.currentTimeMillis() < generationPausedUntilMs) {
                    Thread.sleep(250);
                    continue;
                }

                // Backpressure: generating faster than the LOD sender can push to clients just
                // fills its bounded queue and forces drops/retries. Pause dispatch (generation AND
                // catch-up sync) while the queue is over 3/4 full and let the sender drain.
                var sendQueue = com.ethan.voxyworldgenv2.network.LodSendQueue.getInstance();
                if (sendQueue.getQueuedJobs() > sendQueue.getMaxQueuedJobs() * 3 / 4) {
                    Thread.sleep(200);
                    continue;
                }
                
                List<ServerPlayer> players = new ArrayList<>(PlayerTracker.getInstance().getPlayers());
                if (players.isEmpty()) {
                    // Nobody online. The chunk system still ticks (ServerChunkCache.tick runs
                    // unconditionally in ServerLevel.tick), so the only thing stopping progress is
                    // that every work source above is player-anchored. Fall through to the spawn
                    // anchor rather than idling -- this is what lets an empty BMC3-style server
                    // keep generating instead of doing nothing, ever, by design.
                    // Its own budget: the player budget below is computed from the player list.
                    // The throttle semaphore still bounds concurrency, and the sleep paces passes
                    // so an empty server fills steadily rather than spinning.
                    int idleBudget = Math.max(1, Config.getMaxActiveTasks(isSingleplayer()) / 2);
                    Thread.sleep(dispatchSpawnPregen(idleBudget) ? 50 : 1000);
                    continue;
                }

                // Rotate the scan order every pass so one player with a big unfilled frontier
                // can't starve the others: a plain first-match scan always favours whoever
                // happens to be first in PlayerTracker's iteration order.
                fairnessCursor = (fairnessCursor + 1) % players.size();
                players = rotated(players, fairnessCursor);

                List<ChunkPos> batch = null;
                DimensionState activeState = null;

                // try to find work around any player in their respective dimension
                for (ServerPlayer player : players) {
                    DimensionState ds = getOrSetupState((ServerLevel) player.level());
                    int genRadius = Config.getGenerationRadius(isSingleplayer());
                    int radius = ds.tellusActive ? Math.max(genRadius, 128) : genRadius;
                    batch = ds.distanceGraph.findWork(player.chunkPosition(), radius, ds.trackedBatches);
                    if (batch != null) {
                        activeState = ds;
                        break;
                    }
                }
                
                // Catch-up sync runs EVERY iteration, not only when generation is idle: on a
                // server whose near area was already generated by normal play, findWork only
                // finds frontier chunks far away, so gating this behind batch == null starved
                // the near ring forever -- clients saw LODs only at the generation frontier.
                boolean syncDispatched = dispatchCatchUpSync(players);

                if (batch == null) {
                    if (syncDispatched) {
                        Thread.sleep(10); // small delay to prevent overwhelming network/server tasks
                        continue;
                    }
                    Thread.sleep(100);
                    continue;
                }
                
                dispatchBatch(activeState, batch, Integer.MAX_VALUE);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("error in worker loop", e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * Dispatches up to {@code limit} chunks from one batch: filters out chunks already completed
     * or in flight, acquires the throttle/rate budget for the rest, and hands anything actually
     * ready to the main thread to generate. Extracted so the player-anchored frontier scan above
     * and {@link #dispatchSpawnPregen} share one dispatch path rather than drifting apart --
     * {@code limit} is unbounded ({@code Integer.MAX_VALUE}) for the former and the idle budget
     * for the latter.
     *
     * @return how many chunks were actually taken from the batch (queued or handed off)
     */
    private int dispatchBatch(DimensionState finalState, List<ChunkPos> batch, int limit) {
        long batchKey = DistanceGraph.getBatchKey(batch.get(0).x, batch.get(0).z);
        finalState.batchCounters.put(batchKey, new AtomicInteger(batch.size()));

        // skip if already tracked locally
        List<ChunkPos> preFiltered = new ArrayList<>(batch.size());
        for (ChunkPos pos : batch) {
            long key = pos.toLong();
            if (finalState.completedChunks.contains(key) || finalState.trackedChunks.contains(key)) {
                onSuccess(finalState, pos);
            } else {
                preFiltered.add(pos);
            }
        }

        if (preFiltered.isEmpty()) {
            finalState.trackedBatches.remove(batchKey);
            finalState.batchCounters.remove(batchKey);
            return 0;
        }

        // dispatch tasks
        List<ChunkPos> readyToGenerate = new ArrayList<>();
        int processedCount = 0;
        for (ChunkPos pos : preFiltered) {
            if (!workerRunning.get() || processedCount >= limit) break;

            boolean acquired = false;
            try {
                // Rate cap first, semaphore second: a permit held while sleeping off the
                // rate budget would starve in-flight tasks of nothing, but it would make
                // getActiveTaskCount lie about how much work is actually running.
                awaitGenerationRate();
                acquired = throttle.tryAcquire(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (!acquired) break;

            processedCount++;
            if (finalState.trackedChunks.add(pos.toLong())) {
                finalState.taskStartMs.put(pos.toLong(), System.currentTimeMillis());
                activeTaskCount.incrementAndGet();
                stats.incrementQueued();

                if (finalState.tellusActive) {
                    TellusIntegration.enqueueGenerate(finalState.level, pos, () -> {
                        onSuccess(finalState, pos);
                        completeTask(finalState, pos);
                    });
                    continue;
                }

                readyToGenerate.add(pos);
            } else {
                throttle.release();
                onFailure(finalState, pos);
            }
        }

        if (processedCount < preFiltered.size()) {
            finalState.trackedBatches.remove(batchKey);
            finalState.batchCounters.remove(batchKey);
        }

        if (!readyToGenerate.isEmpty()) {
            server.execute(() -> {
                ServerChunkCache cache = finalState.level.getChunkSource();
                List<ChunkPos> actuallyGenerate = new ArrayList<>();

                for (ChunkPos pos : readyToGenerate) {
                    // getChunkNow never blocks: hasChunk+getChunk could park the main thread in
                    // getChunkBlocking when the FULL future isn't actually complete (C2ME), and a
                    // single stuck worldgen worker then becomes a watchdog kill (BMC3 18:14 crash)
                    LevelChunk existingChunk = cache.getChunkNow(pos.x, pos.z);
                    if (existingChunk != null) {
                        if (!existingChunk.isEmpty()) {
                            VoxyIntegration.ingestChunk(existingChunk);
                            com.ethan.voxyworldgenv2.network.NetworkHandler.broadcastLODData(existingChunk);
                        }
                        onSuccess(finalState, pos);
                        completeTask(finalState, pos);
                    } else {
                        queueTicketAdd(finalState.level, pos);
                        actuallyGenerate.add(pos);
                    }
                }

                if (!actuallyGenerate.isEmpty()) {
                    // apply tickets immediately to ensure DistanceManager is aware of them, keeps stuff nice and clean
                    processPendingTickets();

                    for (ChunkPos pos : actuallyGenerate) {
                        ((ServerChunkCacheMixin) cache).invokeGetChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true)
                            .whenCompleteAsync((result, throwable) -> {
                                if (throwable == null && result != null && result.isSuccess() && result.orElse(null) instanceof LevelChunk chunk) {
                                    onSuccess(finalState, pos);
                                    if (!chunk.isEmpty()) {
                                        VoxyIntegration.ingestChunk(chunk);
                                        com.ethan.voxyworldgenv2.network.NetworkHandler.broadcastLODData(chunk);
                                    }
                                } else {
                                    onFailure(finalState, pos);
                                }
                                cleanupTask(finalState.level, pos);
                            }, server);
                    }
                }
            });
        }
        return processedCount;
    }

    /**
     * Fills a radius around world spawn while the server is empty.
     *
     * <p>Routed through {@link #getOrSetupState} rather than a fresh state: setupLevel is the sole
     * caller of {@link ChunkPersistence#load} and the only place {@code state.loaded} is set, and
     * shutdown() persists only loaded states. An anchor that bypassed it would re-generate
     * everything on every restart and then silently discard its own progress at shutdown.
     *
     * <p>Chunk tickets are taken by {@link #dispatchBatch} exactly as for a player, and released
     * the same way (cleanupTask, reapStuckTasks, or SERVER_STOPPING). That matters: FORCED tickets
     * carry FLAG_PERSIST, so one left behind is written into the world's chunk_tickets SavedData
     * and reactivated by MinecraftServer.prepareLevels(), which blocks boot until every chunk
     * reaches FULL. A kill -9 mid-run would otherwise hang the next start on "Loading initial
     * chunks".
     *
     * <p>Unlike unified/26.2, this version has no whole-server auto-pause to fight here: confirmed
     * via javap against the actual 1.21.1 game jar that {@code MinecraftServer} has no
     * {@code emptyTicks} field (pause-when-empty-seconds is a later-version feature), so the chunk
     * system already keeps ticking unconditionally on an empty 1.21.1 dedicated server.
     */
    private boolean dispatchSpawnPregen(int budget) {
        if (!Config.DATA.spawnPregenEnabled) return false;
        MinecraftServer srv = this.server;
        if (srv == null) return false;

        ServerLevel level = srv.overworld();
        if (level == null) return false;

        DimensionState ds = getOrSetupState(level);

        // setupLevel is the sole caller of ChunkPersistence.load and the only place state.loaded
        // is set, and shutdown() persists ONLY loaded states -- so without this the anchor would
        // re-generate everything on every restart and then silently discard its own progress.
        // It touches level storage, so it is scheduled onto the server thread and this pass yields.
        if (!ds.loaded) {
            srv.execute(() -> setupLevel(level));
            return false;
        }

        ChunkPos centre = spawnChunk(level);
        int radius = Math.max(1, Config.DATA.spawnPregenRadius);

        int dispatched = 0;
        while (dispatched < budget) {
            List<ChunkPos> batch = ds.distanceGraph.findWork(centre, radius, ds.trackedBatches);
            if (batch == null) break; // radius is full
            int sent = dispatchBatch(ds, batch, budget - dispatched);
            if (sent == 0) break;
            dispatched += sent;
        }
        return dispatched > 0;
    }

    /**
     * The world spawn as a chunk position, read without a player. Inlines what unified's
     * platform-abstraction {@code ChunkPosCompat.spawnChunk} does on Fabric, since that shim is a
     * unified-only multiloader concern this single-module branch has no use for.
     */
    private static ChunkPos spawnChunk(ServerLevel level) {
        return new ChunkPos(level.getSharedSpawnPos());
    }

    /**
     * Returns a copy of {@code list} rotated left by {@code offset}; used to round-robin players so
     * one with a big unfilled frontier can't starve the others. Package-private for direct testing.
     */
    static <T> List<T> rotated(List<T> list, int offset) {
        int n = list.size();
        if (n <= 1) return list;
        offset = ((offset % n) + n) % n;
        if (offset == 0) return list;
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(list.get((i + offset) % n));
        }
        return out;
    }

    public void tick() {
        if (!running.get() || server == null) return;
        
        processPendingTickets();

        // Once a second, reconcile the player tracker against the server's own list. The
        // disconnect event cannot be relied on to fire (see PlayerTracker.reconcile), and a stale
        // entry means the worker never idles.
        if (++playerReconcileTicks >= 20) {
            playerReconcileTicks = 0;
            com.ethan.voxyworldgenv2.command.TabHud.tick(server);
            reapStuckTasks();
            int dropped = PlayerTracker.getInstance().reconcile(server);
            if (dropped > 0) {
                VoxyWorldGenV2.LOGGER.debug("pruned {} stale tracked player(s)", dropped);
            }
        }

        if (configReloadScheduled.compareAndSet(true, false)) {
            Config.load();
            updateThrottleCapacity();
            restartScan();
        }
        
        tpsMonitor.tick();
        stats.tick();
        logProgress();
        checkPlayerMovement();
        
        // broadcast changes for all active dimensions
        Set<ServerLevel> activeLevels = new HashSet<>();
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            activeLevels.add((ServerLevel) player.level());
        }
        for (ServerLevel level : activeLevels) {
            ChunkUpdateTracker.getInstance().processDirty(level);
        }
    }
    
    private void logProgress() {
        int interval = Config.DATA.logProgressIntervalSeconds;
        if (interval <= 0) return;

        long now = System.currentTimeMillis();
        if (now - lastProgressLogMs < interval * 1000L) return;
        lastProgressLogMs = now;

        long completed = stats.getCompleted();
        int active = activeTaskCount.get();
        int remaining = getRemainingInRadius();
        boolean generating = active > 0 || completed != lastLoggedCompleted;

        if (!generating) {
            // one final line when a run finishes so the console shows it caught up
            if (wasGenerating && remaining == 0) {
                VoxyWorldGenV2.LOGGER.info(
                    "generation caught up: {} chunks this session ({} skipped, {} failed)",
                    completed, stats.getSkipped(), stats.getFailed());
                wasGenerating = false;
            }
            return;
        }

        double cps = stats.getChunksPerSecond();
        String eta = "";
        if (cps > 0.5 && remaining > 0) {
            long secs = (long) (remaining / cps);
            eta = secs >= 60
                ? String.format(" (~%dm %02ds)", secs / 60, secs % 60)
                : String.format(" (~%ds)", secs);
        }

        String dim = currentDimensionKey != null ? currentDimensionKey.location().toString() : "?";
        VoxyWorldGenV2.LOGGER.info(
            "generating [{}]: {} done @ {}/s, {} remaining in radius{}, {} active, {} skipped, {} failed{}",
            dim, completed, String.format("%.1f", cps), remaining, eta,
            active, stats.getSkipped(), stats.getFailed(),
            tpsMonitor.isThrottled() ? " [TPS-THROTTLED]" : (isTransitionPaused() ? " [PAUSED: player loading]" : ""));

        lastLoggedCompleted = completed;
        wasGenerating = true;
    }

    private void checkPlayerMovement() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) {
            if (!lastPlayerPositions.isEmpty()) {
                lastPlayerPositions.clear();
                lastPlayerLevels.clear();
            }
            return;
        }

        boolean shouldRescan = false;
        Map<ServerLevel, Integer> levelCounts = new HashMap<>();
        
        for (ServerPlayer player : players) {
            levelCounts.merge((ServerLevel) player.level(), 1, Integer::sum);
            ChunkPos currentPos = player.chunkPosition();
            ChunkPos lastPos = lastPlayerPositions.get(player.getUUID());

            if (lastPos == null || distSq(lastPos, currentPos) >= 4) {
                lastPlayerPositions.put(player.getUUID(), currentPos);
                shouldRescan = true;
            }

            // A join or dimension change means the server is about to synchronously load that
            // player's surroundings. Get out of the chunk system's way for a grace period.
            ResourceKey<Level> currentLevelKey = player.level().dimension();
            ResourceKey<Level> lastLevelKey = lastPlayerLevels.put(player.getUUID(), currentLevelKey);
            if (!currentLevelKey.equals(lastLevelKey)) {
                PlayerTracker.getInstance().armGate(player.getUUID(),
                    PlayerTracker.dimensionId(currentLevelKey));
                pauseForTransition(player.getName().getString(), currentLevelKey, lastLevelKey == null);
            }
        }
        
        // majority check for currentLevel - only switch when a candidate strictly exceeds the current level's count...
        ServerLevel majorLevel = currentLevel;
        int maxCount = levelCounts.getOrDefault(currentLevel, 0);
        
        for (var entry : levelCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                majorLevel = entry.getKey();
            }
        }
        
        if (majorLevel != currentLevel && majorLevel != null) {
            setupLevel(majorLevel);
            return;
        }
        
        // clean up players who left
        Set<java.util.UUID> currentPlayerIds = new java.util.HashSet<>();
        for (ServerPlayer p : players) currentPlayerIds.add(p.getUUID());
        if (lastPlayerPositions.size() > currentPlayerIds.size()) {
            lastPlayerPositions.keySet().removeIf(uuid -> !currentPlayerIds.contains(uuid));
            lastPlayerLevels.keySet().removeIf(uuid -> !currentPlayerIds.contains(uuid));
            shouldRescan = true;
        }

        if (shouldRescan) {
            restartScan();
        }
    }

    private void pauseForTransition(String playerName, ResourceKey<Level> dim, boolean isJoin) {
        int seconds = Config.getDimensionChangePauseSeconds(isSingleplayer());
        if (seconds <= 0) return;
        generationPausedUntilMs = System.currentTimeMillis() + seconds * 1000L;
        VoxyWorldGenV2.LOGGER.info("pausing generation {}s while {} loads into {} ({})",
            seconds, playerName, dim.location(), isJoin ? "join" : "dimension change");
    }

    public boolean isTransitionPaused() {
        return System.currentTimeMillis() < generationPausedUntilMs;
    }

    private double distSq(ChunkPos a, ChunkPos b) {
        int dx = a.x - b.x;
        int dz = a.z - b.z;
        return (double) dx * dx + dz * dz;
    }

    private void setupLevel(ServerLevel newLevel) {
        if (currentLevel != null && currentDimensionKey != null) {
            DimensionState oldState = dimensionStates.get(currentDimensionKey);
            if (oldState != null) {
                ChunkPersistence.save(currentLevel, currentDimensionKey, oldState.completedChunks);
            }
        }
        
        currentLevel = newLevel;
        currentDimensionKey = newLevel.dimension();
        DimensionState state = getOrSetupState(newLevel);
        
        if (!state.loaded) {
            if (state.tellusActive) {
                VoxyWorldGenV2.LOGGER.info("tellus world detected for {}, enabling fast generation", currentDimensionKey);
            }
            ChunkPersistence.load(newLevel, currentDimensionKey, state.completedChunks);
            synchronized(state.completedChunks) {
                for (long pos : state.completedChunks) {
                    state.distanceGraph.markChunkCompleted(ChunkPos.getX(pos), ChunkPos.getZ(pos));
                }
            }
            state.loaded = true;
        }
        
        restartScan();
    }
    
    private void restartScan() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) return;
        
        java.util.Map<DimensionState, Integer> maxCounts = new java.util.HashMap<>();
        for (ServerPlayer player : players) {
            DimensionState state = getOrSetupState((ServerLevel) player.level());
            int genRadius = Config.getGenerationRadius(isSingleplayer());
            int radius = state.tellusActive ? Math.max(genRadius, 128) : genRadius;
            int missing = state.distanceGraph.countMissingInRange(player.chunkPosition(), radius);
            maxCounts.merge(state, missing, Math::max);
        }
        
        maxCounts.forEach((state, count) -> state.remainingInRadius.set(count));
    }

    // Token bucket for maxChunksPerSecond. Only the worker thread touches these, so no
    // synchronization; the config value is re-read every iteration so /voxygen genrate and
    // config reloads take effect mid-wait.
    private double rateTokens;
    private long rateLastRefillNanos;

    /** Paces chunk dispatch to the configured chunks/second by sleeping on the worker thread. */
    private void awaitGenerationRate() throws InterruptedException {
        while (workerRunning.get()) {
            int cps = Config.getMaxChunksPerSecond(isSingleplayer());
            if (cps <= 0) return; // unlimited
            long now = System.nanoTime();
            if (rateLastRefillNanos != 0) {
                double refill = (now - rateLastRefillNanos) / 1_000_000_000.0 * cps;
                // Burst budget of one second's worth: enough to smooth scheduling jitter,
                // small enough that an idle period cannot bank a frame-killing spike.
                rateTokens = Math.min(cps, rateTokens + refill);
            }
            rateLastRefillNanos = now;
            if (rateTokens >= 1.0) {
                rateTokens -= 1.0;
                return;
            }
            long sleepMs = (long) Math.ceil((1.0 - rateTokens) * 1000.0 / cps);
            Thread.sleep(Math.max(1, Math.min(sleepMs, 250)));
        }
    }

    private void updateThrottleCapacity() {
        int target = Config.getMaxActiveTasks(isSingleplayer());
        int available = throttle.availablePermits();
        int maxPossible = available + activeTaskCount.get();
        if (target > maxPossible) {
            throttle.release(target - maxPossible);
        }
    }
    
    private void processPendingTickets() {
        TicketOp op;
        java.util.Set<ServerLevel> modifiedLevels = new java.util.HashSet<>();
        while ((op = pendingTicketOps.poll()) != null) {
            ServerChunkCache cache = op.level().getChunkSource();
            // 1.21.1 ticket API: radius maps 1:1 onto the newer addTicketWithRadius (both derive the
            // level as ChunkLevel.byStatus(FULL) - radius). The trailing value is part of Ticket
            // equality, so add and remove must pass the same ChunkPos or the removal won't match.
            if (op.add()) {
                cache.addRegionTicket(TicketType.FORCED, op.pos(), 0, op.pos());
                appliedTickets.computeIfAbsent(op.level(), k -> LongSets.synchronize(new LongOpenHashSet()))
                    .add(op.pos().toLong());
            } else {
                cache.removeRegionTicket(TicketType.FORCED, op.pos(), 0, op.pos());
                LongSet set = appliedTickets.get(op.level());
                if (set != null) set.remove(op.pos().toLong());
            }
            modifiedLevels.add(op.level());
        }
        for (ServerLevel level : modifiedLevels) {
            ((ServerChunkCacheMixin) level.getChunkSource()).invokeRunDistanceManagerUpdates();
        }
    }

    /**
     * Removes every FORCED ticket this manager has applied, so no chunk stays pinned after a
     * graceful shutdown. Queued ops are dropped first so a pending add cannot re-apply a ticket
     * this is in the middle of removing.
     */
    private void releaseAllTickets() {
        pendingTicketOps.clear();

        for (var entry : appliedTickets.entrySet()) {
            ServerLevel level = entry.getKey();
            LongSet positions = entry.getValue();
            ServerChunkCache cache = level.getChunkSource();
            synchronized (positions) {
                for (long key : positions) {
                    ChunkPos pos = new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key));
                    cache.removeRegionTicket(TicketType.FORCED, pos, 0, pos);
                }
            }
            ((ServerChunkCacheMixin) cache).invokeRunDistanceManagerUpdates();
        }
        appliedTickets.clear();
    }

    private void queueTicketAdd(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(level, pos, true));
    }
    
    private void queueTicketRemove(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(level, pos, false));
    }
    
    private void cleanupTask(ServerLevel level, ChunkPos pos) {
        queueTicketRemove(level, pos);
        // Upstream resets MinecraftServer.emptyTicks here to keep an empty server from idling while
        // background generation runs. That field does not exist in 1.21.1 (no empty-tick auto-pause
        // counter exists on MinecraftServer, IntegratedServer or DedicatedServer), so there is
        // nothing to reset and the omission is behaviourally inert on this version.
        DimensionState state = dimensionStates.get(level.dimension());
        if (state != null) completeTask(state, pos);
    }

    private void onSuccess(DimensionState state, ChunkPos pos) {
        long key = pos.toLong();
        state.failCounts.remove(key);
        if (state.completedChunks.add(key)) {
            stats.incrementCompleted();
            state.distanceGraph.markChunkCompleted(pos.x, pos.z);
            state.remainingInRadius.decrementAndGet();
        } else {
            stats.incrementSkipped();
            // Re-assert into the distance graph even though completedChunks already had this key.
            // completedChunks is the source of truth and the graph is a derived index; an index
            // that lost a bit (a race in DistanceGraph.recursiveMark, or any other cause) leaves its
            // batch permanently below the 0xFFFF completion mask, so findWork offers it on every
            // call and generation for that batch never terminates. markChunkCompleted early-returns
            // once a bit is already set, so this is idempotent and heals such a batch on its first
            // re-offer instead of forever.
            state.distanceGraph.markChunkCompleted(pos.x, pos.z);
        }
        decrementBatch(state, pos);
    }
    
    private void onFailure(DimensionState state, ChunkPos pos) {
        stats.incrementFailed();
        long key = pos.toLong();
        int fails = state.failCounts.merge(key, 1, Integer::sum);
        // A chunk whose generation future resolves but consistently fails (not one that hangs --
        // reapStuckTasks covers that) would otherwise never set its completion bit, so findWork
        // would re-offer its batch forever -- structurally the same non-termination family as the
        // batch-boundary bug, different trigger. After a few tries, give up: force the bit so the
        // batch can still reach the 0xFFFF completion mask.
        if (fails >= MAX_CHUNK_RETRIES) {
            state.failCounts.remove(key);
            state.distanceGraph.markChunkCompleted(pos.x, pos.z);
            state.remainingInRadius.updateAndGet(v -> Math.max(0, v - 1));
        }
        decrementBatch(state, pos);
    }

    private void decrementBatch(DimensionState state, ChunkPos pos) {
        long batchKey = DistanceGraph.getBatchKey(pos.x, pos.z);
        AtomicInteger counter = state.batchCounters.get(batchKey);
        if (counter != null && counter.decrementAndGet() <= 0) {
            state.trackedBatches.remove(batchKey);
            state.batchCounters.remove(batchKey);
        }
    }
    
    private void completeTask(DimensionState state, ChunkPos pos) {
        state.taskStartMs.remove(pos.toLong());
        if (state.trackedChunks.remove(pos.toLong())) {
            activeTaskCount.decrementAndGet();
            throttle.release();
        }
    }

    /**
     * Whether a dimension's in-flight generation tasks get the full {@code stuckTaskTimeoutSeconds}
     * grace rather than the short one meant for a dimension the chunk system has actually parked.
     *
     * <p>True when someone is standing in the dimension ({@code dimensionIsOccupied}), OR when
     * nobody is online anywhere ({@code !anyoneOnline}). The second half is what protects spawn
     * pre-generation's own in-flight tasks (see {@link #dispatchSpawnPregen}): that path dispatches
     * precisely when the player list is empty, so without it every one of its own tasks would be
     * judged against the short grace meant for an idle, nobody-here dimension -- and chunk
     * generation routinely takes longer than that short grace under load, so the anchor's work
     * would be reaped and restarted in a loop instead of ever finishing. Package-private so it can
     * be unit tested directly without a live {@code MinecraftServer}/{@code PlayerTracker}.
     */
    static boolean getsFullStuckTaskTimeout(boolean anyoneOnline, boolean dimensionIsOccupied) {
        return !anyoneOnline || dimensionIsOccupied;
    }

    /**
     * Abandons generation tasks whose future never completed (chunk system parked the
     * dimension, broken chunk load, etc.). Releasing the permit un-wedges the worker; the
     * chunk stays un-completed so a later scan retries it. If the original future does
     * finish afterwards, completeTask's trackedChunks guard makes the second release a no-op.
     */
    private void reapStuckTasks() {
        int timeout = Config.DATA.stuckTaskTimeoutSeconds;
        if (timeout <= 0) return;
        long now = System.currentTimeMillis();

        // Dimensions someone is standing in get the full timeout (their tasks are probably just
        // slow). A dimension with NO players is parked by the chunk system, so its in-flight
        // tasks are dead weight holding permits the occupied dimension needs — reclaim those
        // after a short grace. The permit pool is global; this is what keeps a teleport from
        // stalling generation where the player actually is.
        //
        // Unlike before spawn pre-generation existed, this branch now dispatches player-anchored
        // work when the player list is empty too (see dispatchSpawnPregen). If nobody is online
        // anywhere, there is no occupied dimension whose permits need protecting from an idle one,
        // so the short grace would only punish the pregen anchor's own legitimately in-flight
        // tasks — chunk generation routinely takes longer than 5 seconds under load, so this would
        // reap and restart the anchor's work in a loop instead of letting it finish. Fall back to
        // the full timeout everywhere in that case.
        Set<ResourceKey<Level>> occupied = new HashSet<>();
        for (ServerPlayer p : PlayerTracker.getInstance().getPlayers()) {
            occupied.add(p.level().dimension());
        }
        boolean anyoneOnline = !occupied.isEmpty();
        long occupiedCutoff = now - timeout * 1000L;
        long emptyCutoff = now - Math.min(5, timeout) * 1000L;

        for (var entry : dimensionStates.entrySet()) {
            DimensionState state = entry.getValue();
            if (state.taskStartMs.isEmpty()) continue;
            boolean hasPlayers = getsFullStuckTaskTimeout(anyoneOnline, occupied.contains(entry.getKey()));
            long cutoff = hasPlayers ? occupiedCutoff : emptyCutoff;
            java.util.List<Long> stuck = new ArrayList<>();
            state.taskStartMs.forEach((posKey, started) -> {
                if (started < cutoff) stuck.add(posKey);
            });
            if (stuck.isEmpty()) continue;
            VoxyWorldGenV2.LOGGER.warn(
                "abandoning {} generation task(s) in {} ({}) - releasing permits, chunks will retry",
                stuck.size(), entry.getKey().location(),
                hasPlayers ? "stuck >" + timeout + "s" : "dimension has no players");
            for (long posKey : stuck) {
                ChunkPos pos = new ChunkPos(ChunkPos.getX(posKey), ChunkPos.getZ(posKey));
                queueTicketRemove(state.level, pos);
                onFailure(state, pos);
                completeTask(state, pos);
            }
        }
    }
    
    public void scheduleConfigReload() {
        configReloadScheduled.set(true);
    }
    
    public boolean isChunkCompleted(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        DimensionState state = dimensionStates.get(level.dimension());
        return state != null && state.completedChunks.contains(pos.toLong());
    }

    public GenerationStats getStats() { return stats; }
    public int getActiveTaskCount() { return activeTaskCount.get(); }
    public int getRemainingInRadius() {
        if (currentDimensionKey == null) return 0;
        DimensionState state = dimensionStates.get(currentDimensionKey);
        return state != null ? state.remainingInRadius.get() : 0; 
    }
    public boolean isThrottled() { return tpsMonitor.isThrottled(); }
    public int getQueueSize() { return 0; }
    
    public void setPauseCheck(java.util.function.BooleanSupplier check) {
        this.pauseCheck = check;
    }
}
