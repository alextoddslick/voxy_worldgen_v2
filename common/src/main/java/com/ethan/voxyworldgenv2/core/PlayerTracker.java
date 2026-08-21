package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import it.unimi.dsi.fastutil.longs.LongSet;

public class PlayerTracker {
    private static final PlayerTracker INSTANCE = new PlayerTracker();

    // Keyed by UUID, not by the ServerPlayer object.
    //
    // Entity.hashCode() is the entity id, which is not stable across a player's session: respawning
    // after death (and a dimension change) yields a ServerPlayer carrying a different id. A hash set
    // keyed on the player object therefore probes the wrong bucket when removing on disconnect, the
    // entry is never removed, and the generation worker never sees an empty player list -- so it
    // keeps generating chunks forever for nobody, anchored to a phantom. Measured on a 1.21.1
    // dedicated server (port/26.2, commit 2bc4971): this tracker reported 1 player while the
    // server's own player list reported 0, with generation still running.
    private final Map<UUID, ServerPlayer> players;

    /**
     * Per-player synced sets, keyed by dimension inside the store. The flat set this replaced had
     * no dimension in its key, so it had to be wiped on every dimension change or a stale entry
     * would block a send at the same coordinates in the new world -- and wiping it threw away the
     * overworld's progress every time a player stepped through a portal.
     */
    private final Map<UUID, SyncedChunkStore> syncedChunks;

    // players that acked the handshake; retained because the NeoForge module still gates on it
    private final Set<UUID> moddedPlayers;
    private final Map<UUID, Integer> clientProtocols = new ConcurrentHashMap<>();
    // recently joined players that still need a backfill
    private final Set<UUID> needsBackfill;
    // last dimension per player, to spot a dim change
    private final Map<UUID, ResourceKey<Level>> lastDimension;

    /** UUID -> dimension id -> millis when we started waiting for that client's known-chunk set. */
    private final Map<UUID, Map<String, Long>> awaitingKnownSet = new ConcurrentHashMap<>();

    /**
     * How many known-chunks packets a player has spent for a dimension since entering it, or
     * {@link #UPLOAD_FINISHED} once their final packet arrived. UUID -> dimension id -> count.
     * Bounded so a hostile client cannot stream unbounded region data at the server.
     */
    private final Map<UUID, Map<String, Integer>> knownChunksBudget = new ConcurrentHashMap<>();

    /** Per-player, per-dimension radius for /voxygen refresh near. */
    private final Map<UUID, Map<String, Integer>> refreshRadius = new ConcurrentHashMap<>();

    private static final int MAX_KNOWN_CHUNKS_PACKETS = 32;
    private static final int UPLOAD_FINISHED = -1;

    private PlayerTracker() {
        this.players = new ConcurrentHashMap<>();
        this.syncedChunks = new ConcurrentHashMap<>();
        this.moddedPlayers = ConcurrentHashMap.newKeySet();
        this.needsBackfill = ConcurrentHashMap.newKeySet();
        this.lastDimension = new ConcurrentHashMap<>();
    }

    public static PlayerTracker getInstance() {
        return INSTANCE;
    }

    public void addPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        players.put(id, player);
        syncedChunks.put(id, new SyncedChunkStore());
        // nothing synced yet so flag for backfill
        needsBackfill.add(id);
        lastDimension.put(id, player.level().dimension());
        armGate(id, dimensionId(player.level().dimension()));
    }

    /**
     * Drops a player, but only if the instance disconnecting is the one currently tracked.
     *
     * <p>Vanilla's {@code PlayerList.remove} guards the same way. A duplicate login disconnects the
     * old connection after the new one's JOIN has already fired, and without the guard that late
     * disconnect would wipe the live session's tracking -- which {@link #reconcile} never repairs,
     * because it only prunes and refreshes, it never re-adds. A skipped removal here is harmless by
     * comparison: reconcile drops entries whose player is actually gone within a second.
     */
    public void removePlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        ServerPlayer tracked = players.get(id);
        if (tracked != null && tracked != player) return;
        players.remove(id);
        syncedChunks.remove(id);
        moddedPlayers.remove(id);
        clientProtocols.remove(id);
        needsBackfill.remove(id);
        lastDimension.remove(id);
        awaitingKnownSet.remove(id);
        knownChunksBudget.remove(id);
        refreshRadius.remove(id);
    }

    /**
     * Reconciles this tracker against the server's authoritative player list.
     *
     * <p>Necessary because {@code ServerPlayConnectionEvents.DISCONNECT} is not guaranteed to fire.
     * Measured on 1.21.1 (port/26.2, commit 2bc4971): a Carpet fake player that died left the game --
     * {@code PlayerList.remove} ran and "left the game" was logged -- yet the event never fired, so
     * the entry was never removed and the generation worker never saw an empty player list. It then
     * generated chunks forever for nobody. No keying scheme fixes that case, because the removal
     * code never executed at all; the tracker has to reconcile rather than trust the event alone.
     *
     * <p>Also refreshes stale references: respawning replaces a player's {@code ServerPlayer}
     * instance, and the old one is removed from the world. Holding it would mean reading a dead
     * entity's position and sending packets to a defunct connection.
     *
     * @return number of entries dropped
     */
    public int reconcile(MinecraftServer server) {
        if (server == null) return 0;
        int removed = 0;
        for (var it = players.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            UUID id = entry.getKey();
            ServerPlayer live = server.getPlayerList().getPlayer(id);
            if (live == null || live.hasDisconnected()) {
                it.remove();
                syncedChunks.remove(id);
                moddedPlayers.remove(id);
                clientProtocols.remove(id);
                needsBackfill.remove(id);
                lastDimension.remove(id);
                awaitingKnownSet.remove(id);
                knownChunksBudget.remove(id);
                refreshRadius.remove(id);
                removed++;
            } else if (live != entry.getValue()) {
                entry.setValue(live);
            }
        }
        return removed;
    }

    public void clear() {
        players.clear();
        syncedChunks.clear();
        moddedPlayers.clear();
        clientProtocols.clear();
        needsBackfill.clear();
        lastDimension.clear();
        awaitingKnownSet.clear();
        knownChunksBudget.clear();
        refreshRadius.clear();
    }

    /**
     * True if the player changed dimension. The synced set is no longer wiped -- it is keyed by
     * dimension, so the old world's progress survives the trip and is still valid on return. The
     * gate is re-armed because entering a dimension is when an honest client uploads what it holds.
     */
    public boolean handleDimensionChange(ServerPlayer player) {
        UUID uuid = player.getUUID();
        ResourceKey<Level> current = player.level().dimension();
        ResourceKey<Level> previous = lastDimension.put(uuid, current);
        if (previous != null && !previous.equals(current)) {
            needsBackfill.add(uuid);
            armGate(uuid, dimensionId(current));
            return true;
        }
        return false;
    }

    public Collection<ServerPlayer> getPlayers() {
        return Collections.unmodifiableCollection(players.values());
    }

    public SyncedChunkStore getStore(UUID uuid) {
        return syncedChunks.get(uuid);
    }

    public LongSet getSyncedChunks(UUID uuid, ResourceKey<Level> dimension) {
        SyncedChunkStore store = syncedChunks.get(uuid);
        return store == null ? null : store.setFor(dimensionId(dimension));
    }

    public int getPlayerCount() {
        return players.size();
    }

    /**
     * The peer's announced protocol, recorded from the handshake ack. Gating clientbound payloads
     * on a floor here is what lets an older client keep receiving terrain while simply not being
     * offered features its build cannot parse.
     */
    public void setClientProtocol(UUID uuid, int protocol) {
        clientProtocols.put(uuid, protocol);
    }

    public int getClientProtocol(UUID uuid) {
        return clientProtocols.getOrDefault(uuid, 0);
    }

    // Retained because the NeoForge module still gates on it; the Fabric module no longer does.
    public void setModded(UUID uuid, boolean modded) {
        if (modded) {
            moddedPlayers.add(uuid);
        } else {
            moddedPlayers.remove(uuid);
        }
    }

    public boolean isModded(UUID uuid) {
        return moddedPlayers.contains(uuid);
    }

    // any online player acked the handshake
    public boolean anyModded() {
        return !moddedPlayers.isEmpty();
    }

    // cheap dedup before we serialize sections
    public boolean isSynced(UUID uuid, ResourceKey<Level> dimension, long packedChunkPos) {
        SyncedChunkStore store = syncedChunks.get(uuid);
        return store != null && store.isSynced(dimensionId(dimension), packedChunkPos);
    }

    public boolean needsBackfill(UUID uuid) {
        return needsBackfill.contains(uuid);
    }

    public void clearBackfill(UUID uuid) {
        needsBackfill.remove(uuid);
    }

    public void setRefreshRadius(UUID uuid, String dimensionId, int radius) {
        refreshRadius.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(dimensionId, radius);
    }

    public int getRefreshRadius(UUID uuid, String dimensionId) {
        Map<String, Integer> byDim = refreshRadius.get(uuid);
        if (byDim == null) return 0;
        Integer r = byDim.get(dimensionId);
        return r == null ? 0 : r;
    }

    public void clearRefreshRadius(UUID uuid, String dimensionId) {
        Map<String, Integer> byDim = refreshRadius.get(uuid);
        if (byDim != null) byDim.remove(dimensionId);
    }

    public void armGate(UUID uuid, String dimensionId) {
        if (!Config.DATA.rememberSentChunks) return;
        awaitingKnownSet.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
            .put(dimensionId, System.currentTimeMillis());
        // Entering a dimension is when an honest client uploads, so this is where its allowance
        // is renewed. Nothing else renews it.
        knownChunksBudget.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
            .put(dimensionId, 0);
    }

    /**
     * Spends one packet of this player's known-chunks allowance for a dimension.
     *
     * @return false when the allowance is exhausted or the client already sent its final packet,
     *         in which case the caller must ignore the payload
     */
    public boolean acceptKnownChunksPacket(UUID uuid, String dimensionId) {
        Map<String, Integer> byDim = knownChunksBudget.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        int spent = byDim.getOrDefault(dimensionId, 0);
        if (spent == UPLOAD_FINISHED) return false; // already sent `last`; nothing more is expected
        if (spent >= MAX_KNOWN_CHUNKS_PACKETS) {
            // Close the budget so this logs once rather than once per packet.
            byDim.put(dimensionId, UPLOAD_FINISHED);
            VoxyWorldGenV2.LOGGER.warn(
                "ignoring further known-chunks batches for {} from player {}: more than {} in one dimension entry",
                dimensionId, uuid, MAX_KNOWN_CHUNKS_PACKETS);
            return false;
        }
        byDim.put(dimensionId, spent + 1);
        return true;
    }

    /** The client says it is done for this dimension; accept nothing further until it re-enters. */
    public void finishKnownChunksUpload(UUID uuid, String dimensionId) {
        knownChunksBudget.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
            .put(dimensionId, UPLOAD_FINISHED);
    }

    public void clearGate(UUID uuid, String dimensionId) {
        Map<String, Long> byDim = awaitingKnownSet.get(uuid);
        if (byDim != null) byDim.remove(dimensionId);
    }

    /**
     * True while we are still waiting for this player's known-chunk upload for this dimension.
     *
     * <p>Without this the worker starts re-streaming during the second the upload is in flight,
     * which is exactly the traffic the feature exists to avoid. The timeout is what makes a
     * vanilla or older client -- which never uploads -- behave as it did before.
     */
    public boolean isGated(UUID uuid, String dimensionId) {
        if (!Config.DATA.rememberSentChunks) return false;
        if (Config.DATA.knownChunksTimeoutSeconds <= 0) return false;
        Map<String, Long> byDim = awaitingKnownSet.get(uuid);
        if (byDim == null) return false;
        Long since = byDim.get(dimensionId);
        if (since == null) return false;
        if (System.currentTimeMillis() - since > Config.DATA.knownChunksTimeoutSeconds * 1000L) {
            byDim.remove(dimensionId);
            return false;
        }
        return true;
    }

    /** Stable string key for a dimension, e.g. "minecraft:overworld". */
    public static String dimensionId(ResourceKey<Level> dimension) {
        return com.ethan.voxyworldgenv2.platform.Services.CHUNK_POS.dimensionId(dimension);
    }
}
