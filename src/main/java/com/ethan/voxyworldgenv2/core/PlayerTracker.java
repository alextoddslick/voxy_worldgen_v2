package com.ethan.voxyworldgenv2.core;

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
     * When a player entered a dimension and has not yet uploaded what they already have.
     * Keyed UUID -> dimension id -> millis at which the wait started.
     */
    private final Map<UUID, Map<String, Long>> awaitingKnownSet = new ConcurrentHashMap<>();

    private PlayerTracker() {
        this.players = new ConcurrentHashMap<>();
        this.syncedChunks = new ConcurrentHashMap<>();
    }

    public static PlayerTracker getInstance() {
        return INSTANCE;
    }

    public void addPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        players.put(id, player);
        syncedChunks.computeIfAbsent(id, k -> new SyncedChunkStore());
    }

    public void removePlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        players.remove(id);
        syncedChunks.remove(id);
        awaitingKnownSet.remove(id);
    }

    public void clear() {
        players.clear();
        syncedChunks.clear();
        awaitingKnownSet.clear();
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

    public void armGate(UUID uuid, String dimensionId) {
        if (!Config.DATA.rememberSentChunks) return;
        awaitingKnownSet.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
            .put(dimensionId, System.currentTimeMillis());
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
        return dimension.location().toString();
    }

    public int getPlayerCount() {
        return players.size();
    }
}
