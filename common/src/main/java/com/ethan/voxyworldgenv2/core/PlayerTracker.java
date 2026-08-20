package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import net.minecraft.resources.ResourceKey;
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
    private final Set<ServerPlayer> players;

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

    private static final int MAX_KNOWN_CHUNKS_PACKETS = 32;
    private static final int UPLOAD_FINISHED = -1;

    private PlayerTracker() {
        this.players = ConcurrentHashMap.newKeySet();
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
        players.add(player);
        syncedChunks.put(id, new SyncedChunkStore());
        // nothing synced yet so flag for backfill
        needsBackfill.add(id);
        lastDimension.put(id, player.level().dimension());
        armGate(id, dimensionId(player.level().dimension()));
    }

    public void removePlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        players.remove(player);
        syncedChunks.remove(id);
        moddedPlayers.remove(id);
        clientProtocols.remove(id);
        needsBackfill.remove(id);
        lastDimension.remove(id);
        awaitingKnownSet.remove(id);
        knownChunksBudget.remove(id);
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
        return Collections.unmodifiableCollection(players);
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
