package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Config {
    
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("voxyworldgenv2.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    public static ConfigData DATA = new ConfigData();
    
    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            // auto-configure for first run
            int cores = Runtime.getRuntime().availableProcessors();
            long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024); // mb
            
            // scale based on hardware
            DATA.maxActiveTasks = 20;
            DATA.generationRadius = 64; // conservative default; raise via /voxygen or the book UI
            
            save();
            return;
        }
        
        try (var reader = Files.newBufferedReader(CONFIG_PATH)) {
            DATA = GSON.fromJson(reader, ConfigData.class);
        } catch (IOException e) {
            VoxyWorldGenV2.LOGGER.error("failed to load config", e);
        }
    }
    
    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (var writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(DATA, writer);
            }
        } catch (IOException e) {
            VoxyWorldGenV2.LOGGER.error("failed to save config", e);
        }
    }
    
    public static class ConfigData {
        public boolean enabled = true;
        public int generationRadius = 64;
        public int update_interval = 20; // legacy field for Compat
        public int maxQueueSize = 20000;
        public int maxActiveTasks = 20;
        // Per-player LOD bandwidth cap in megabits/sec, applied to the RAW (pre-compression)
        // payload size. 0 = unlimited. Default 2: with ~10x wire compression that is a gentle
        // ~25 KB/s on the wire per player — safe for hosted servers; LAN testing can
        // `/voxygen ratelimit off`.
        public double maxMbpsPerPlayer = 2.0;
        // How often (seconds) to log generation progress to the server console while chunks are
        // being generated. 0 disables the log line entirely.
        public int logProgressIntervalSeconds = 10;
        // A generation task that hasn't completed after this many seconds is abandoned: its
        // permit is released, its ticket removed, and the chunk left for a later retry. This is
        // the safety net for chunk systems (C2ME) that park work for dimensions with no players —
        // without it, 20 in-flight End tasks after a teleport pin the worker forever. 0 disables.
        public int stuckTaskTimeoutSeconds = 60;
        // Seconds to pause ALL background generation when a player joins or changes dimension,
        // so the destination's own chunk loading gets the chunk system to itself. On big packs
        // (C2ME etc.) grinding overworld generation while the End spawn area loads can stall the
        // main thread long enough for the watchdog to kill the server. 0 disables.
        public int dimensionChangePauseSeconds = 15;
        // Player UUIDs who opted out of the auto-opening settings book (/voxygen headless on).
        // They keep the chat replies; /voxygen settings still opens the book on request.
        public java.util.List<String> headlessPlayers = new java.util.ArrayList<>();
        // Master switch for client LOD memory. When false the server ignores uploaded known-chunk
        // sets and re-streams everything, exactly as it did before that feature existed. This is
        // the rollback: the failure mode of remembering wrongly is invisible terrain.
        public boolean rememberSentChunks = true;
        // How long to withhold LOD data from a joining player while waiting for their client to
        // upload what it already has. A vanilla or older client never uploads, so this timeout is
        // what makes it behave exactly as it did before the feature existed. 0 disables the gate.
        public int knownChunksTimeoutSeconds = 10;
        // Permission level required for /voxygen refresh. 0 lets any player refresh themselves.
        // Targeting another player always requires level 2 regardless of this value, so lowering
        // it cannot let one player force egress onto another. Clamped to the valid vanilla range
        // 0-4 wherever it's read: anything outside that range is either always-true (negative,
        // which would defeat the point) or unsatisfiable even by a level-4 admin (above 4, which
        // would make refresh silently unreachable by anyone).
        public int refreshPermissionLevel = 2;
        // What "/voxygen refresh near" means, in chunks.
        public int refreshDefaultRadius = 16;
    }
}
