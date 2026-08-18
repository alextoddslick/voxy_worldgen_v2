package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent per-player record backing the admin Players tab: name, last-seen, lifetime wire
 * traffic and last known client disk usage for every player who has ever joined. Overrides
 * already persist in the config; this sidecar carries the stats that used to die with the
 * session. Written once per disconnect (and on server stop), so accumulation is the caller's
 * job: pass the new absolute totals.
 */
public class PlayerHistory {

    public static final class Entry {
        public String name = "";
        public long wireBytes;
        public long diskBytes = -1;
        public long lastSeenMs;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static PlayerHistory instance;

    /** The server-wide history, stored next to the config as voxyworldgenv2_players.json. */
    public static synchronized PlayerHistory getInstance() {
        if (instance == null) {
            Path p;
            try {
                p = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("voxyworldgenv2_players.json");
            } catch (Throwable t) {
                p = Path.of("config", "voxyworldgenv2_players.json");
            }
            instance = new PlayerHistory(p);
        }
        return instance;
    }

    private final Path file;
    private final Map<String, Entry> byUuid = new ConcurrentHashMap<>();

    public PlayerHistory(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file)) {
            Map<String, Entry> loaded = GSON.fromJson(reader,
                new TypeToken<Map<String, Entry>>() {}.getType());
            if (loaded != null) byUuid.putAll(loaded);
        } catch (Exception e) {
            // A corrupt history costs the offline rows, never the server.
            VoxyWorldGenV2.LOGGER.warn("could not read player history {}: {}", file, e.toString());
        }
    }

    public synchronized void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (var writer = Files.newBufferedWriter(file)) {
                GSON.toJson(byUuid, writer);
            }
        } catch (IOException e) {
            VoxyWorldGenV2.LOGGER.error("failed to save player history", e);
        }
    }

    /**
     * Absolute set of a player's totals. A negative {@code diskBytes} means "no report this
     * session" and never erases a known figure — disk reports arrive on their own cadence.
     */
    public void record(UUID uuid, String name, long wireBytes, long diskBytes, long nowMs) {
        Entry e = byUuid.computeIfAbsent(uuid.toString(), k -> new Entry());
        e.name = name;
        e.wireBytes = wireBytes;
        if (diskBytes >= 0) e.diskBytes = diskBytes;
        e.lastSeenMs = nowMs;
    }

    /** Wire bytes already folded into the stored totals this server run; never serialized. */
    private final Map<String, Long> flushedThisRun = new ConcurrentHashMap<>();

    /**
     * Folds a player's LIVE running total (bytes since server start, as the send queue counts
     * them) into the stored lifetime figure. Safe to call repeatedly — only the delta since the
     * previous call is added, and a new server run starts a fresh baseline because this map is
     * in-memory only.
     */
    public void recordSession(UUID uuid, String name, long liveWireBytes, long diskBytes, long nowMs) {
        Entry e = byUuid.computeIfAbsent(uuid.toString(), k -> new Entry());
        long flushed = flushedThisRun.getOrDefault(uuid.toString(), 0L);
        long delta = Math.max(0, liveWireBytes - flushed);
        flushedThisRun.put(uuid.toString(), liveWireBytes);
        e.name = name;
        e.wireBytes += delta;
        if (diskBytes >= 0) e.diskBytes = diskBytes;
        e.lastSeenMs = nowMs;
    }

    public Entry get(UUID uuid) {
        return byUuid.get(uuid.toString());
    }

    /** Live view keyed by UUID string; treat as read-only. */
    public Map<String, Entry> entries() {
        return byUuid;
    }
}
