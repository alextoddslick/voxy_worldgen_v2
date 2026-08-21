package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerTracker {
    private static final PlayerTracker INSTANCE = new PlayerTracker();

    // Keyed by UUID, not by the ServerPlayer object.
    //
    // Entity.hashCode() is the entity id, which is not stable across a player's session: respawning
    // after death yields a ServerPlayer carrying a different id. A hash set keyed on the player
    // object therefore probes the wrong bucket when removing on disconnect, the entry is never
    // removed, and the generation worker never sees an empty player list -- so it keeps generating
    // chunks forever for nobody. Measured on a 1.21.1 dedicated server: this tracker reported 1
    // player while the server's own player list reported 0, with generation still running.
    private final Map<UUID, ServerPlayer> players;
    private final Map<UUID, SyncedChunkStore> syncedChunks;

    /**
     * The peer's announced protocol, recorded from the {@code HandshakeAckPayload}. Gating
     * clientbound protocol-5 payloads on a floor here is what lets an older client keep receiving
     * terrain while simply not being offered features its build cannot parse.
     */
    private final Map<UUID, Integer> clientProtocols = new ConcurrentHashMap<>();

    /** How many bytes of on-disk Voxy store the client last reported, or -1 if never reported. */
    private final Map<UUID, Long> diskBytesReported = new ConcurrentHashMap<>();

    /**
     * When a player entered a dimension and has not yet uploaded what they already have.
     * Keyed UUID -> dimension id -> millis at which the wait started.
     */
    private final Map<UUID, Map<String, Long>> awaitingKnownSet = new ConcurrentHashMap<>();

    /** Catch-up radius override while a /voxygen refresh drains. UUID -> dimension id -> chunks. */
    private final Map<UUID, Map<String, Integer>> refreshRadius = new ConcurrentHashMap<>();

    /**
     * How many known-chunk packets this player has spent for a dimension since entering it, or
     * {@link #UPLOAD_FINISHED} once their final packet arrived. UUID -> dimension id -> count.
     *
     * <p>Applying a batch is up to 184,320 set insertions on the main server thread, so the count
     * of batches a client can spend has to be bounded by something other than the client's good
     * manners. Reset on join and on dimension entry, which is when an honest client uploads.
     */
    private final Map<UUID, Map<String, Integer>> knownChunksBudget = new ConcurrentHashMap<>();

    /**
     * A radius-512 known set is ~1,089 regions, about 6 packets. Well above any legitimate upload
     * and still only a few hundred milliseconds of work if a client spends the whole allowance.
     */
    private static final int MAX_KNOWN_CHUNKS_PACKETS = 32;
    private static final int UPLOAD_FINISHED = -1;

    private PlayerTracker() {
        this.players = new ConcurrentHashMap<>();
        this.syncedChunks = new ConcurrentHashMap<>();
    }

    public static PlayerTracker getInstance() {
        return INSTANCE;
    }

    /**
     * A JOIN is always a new connection, which will upload its own known set, so all per-session
     * state is replaced rather than reused.
     *
     * <p>Keeping the previous store would be a false positive that outlives the session: the
     * disconnect event is not guaranteed to fire (see {@link #reconcile}), and a player who
     * rejoins inside the one-second reconcile window would otherwise land on the old store. Since
     * {@code applyRegions} is additive, the seeded set would become the union of stale server
     * belief and client truth — and the difference between those two is precisely the chunks the
     * server marked synced but the client never ingested, i.e. permanently blank terrain.
     */
    public void addPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        players.put(id, player);
        syncedChunks.put(id, new SyncedChunkStore());
        awaitingKnownSet.remove(id);
        refreshRadius.remove(id);
        knownChunksBudget.remove(id);
        clientProtocols.remove(id);
        diskBytesReported.remove(id);
    }

    /**
     * Drops a player, but only if the instance disconnecting is the one being tracked.
     *
     * <p>Vanilla's {@code PlayerList.remove} guards the same way. A duplicate login disconnects the
     * old connection after the new one's JOIN has already fired, and without the guard that late
     * disconnect wipes the live session's tracking — which {@link #reconcile} never repairs,
     * because it only prunes and refreshes, it never re-adds. A skipped removal is harmless by
     * comparison: reconcile drops entries whose player is gone within a second.
     */
    public void removePlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        ServerPlayer tracked = players.get(id);
        if (tracked != null && tracked != player) return;
        players.remove(id);
        syncedChunks.remove(id);
        awaitingKnownSet.remove(id);
        refreshRadius.remove(id);
        knownChunksBudget.remove(id);
        clientProtocols.remove(id);
        diskBytesReported.remove(id);
    }

    public void clear() {
        players.clear();
        syncedChunks.clear();
        awaitingKnownSet.clear();
        refreshRadius.clear();
        knownChunksBudget.clear();
        clientProtocols.clear();
        diskBytesReported.clear();
    }

    /**
     * Reconciles this tracker against the server's authoritative player list.
     *
     * <p>Necessary because {@code ServerPlayConnectionEvents.DISCONNECT} is not guaranteed to fire.
     * Measured on 1.21.1: a Carpet fake player that died left the game — {@code PlayerList.remove}
     * ran and "left the game" was logged — yet the event never fired, so the entry was never removed
     * and the generation worker never saw an empty player list. It then generated chunks forever for
     * nobody. Any disconnect path that bypasses the connection's onDisconnect hook has the same
     * effect, so the tracker reconciles rather than trusting the event alone.
     *
     * <p>Also refreshes stale references: respawning replaces a player's {@code ServerPlayer}
     * instance, and the old one is removed from the world. Holding it would mean reading a dead
     * entity's position and sending packets to a defunct connection.
     *
     * @return number of entries dropped
     */
    public int reconcile(net.minecraft.server.MinecraftServer server) {
        if (server == null) return 0;
        int removed = 0;
        for (var it = players.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerPlayer live = server.getPlayerList().getPlayer(entry.getKey());
            if (live == null || live.hasDisconnected()) {
                it.remove();
                syncedChunks.remove(entry.getKey());
                awaitingKnownSet.remove(entry.getKey());
                refreshRadius.remove(entry.getKey());
                knownChunksBudget.remove(entry.getKey());
                clientProtocols.remove(entry.getKey());
                diskBytesReported.remove(entry.getKey());
                removed++;
            } else if (live != entry.getValue()) {
                entry.setValue(live);
            }
        }
        return removed;
    }

    public Collection<ServerPlayer> getPlayers() {
        return Collections.unmodifiableCollection(players.values());
    }

    public SyncedChunkStore getStore(java.util.UUID uuid) {
        return syncedChunks.get(uuid);
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
     * vanilla or older client — which never uploads — behave as it did before.
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

    public it.unimi.dsi.fastutil.longs.LongSet getSyncedChunks(
            java.util.UUID uuid, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        SyncedChunkStore store = syncedChunks.get(uuid);
        return store == null ? null : store.setFor(dimensionId(dimension));
    }

    /** Stable string key for a dimension, e.g. "minecraft:overworld". */
    public static String dimensionId(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        return dimension.identifier().toString();
    }

    public int getPlayerCount() {
        return players.size();
    }

    public void setClientProtocol(UUID uuid, int protocol) {
        clientProtocols.put(uuid, protocol);
    }

    public int getClientProtocol(UUID uuid) {
        return clientProtocols.getOrDefault(uuid, 0);
    }

    /** -1 if this player has never reported their on-disk Voxy store size this session. */
    public long getDiskBytes(UUID uuid) {
        return diskBytesReported.getOrDefault(uuid, -1L);
    }

    public void setDiskBytes(UUID uuid, long bytes) {
        diskBytesReported.put(uuid, bytes);
    }
}
