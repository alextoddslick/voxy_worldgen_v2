package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Config {
    
    private static Path getConfigPath() {
        try {
            var loader = FabricLoader.getInstance();
            if (loader != null && loader.getConfigDir() != null) {
                return loader.getConfigDir().resolve("voxyworldgenv2.json");
            }
        } catch (Throwable ignored) {}
        return Path.of("config", "voxyworldgenv2.json");
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    public static ConfigData DATA = new ConfigData();
    
    public static void load() {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            // auto-configure for first run
            int cores = Runtime.getRuntime().availableProcessors();
            long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024); // mb
            
            // scale based on hardware
            DATA.maxActiveTasks = 20;
            DATA.generationRadius = 64; // conservative default; raise via /voxygen or the book UI
            
            save();
            return;
        }
        
        try (var reader = Files.newBufferedReader(configPath)) {
            DATA = GSON.fromJson(reader, ConfigData.class);
        } catch (IOException e) {
            VoxyWorldGenV2.LOGGER.error("failed to load config", e);
        }
        if (DATA == null) DATA = new ConfigData(); // empty file parses to null
        // A hand-edited "singleplayer": null must not NPE the toggles in the book/commands.
        if (DATA.singleplayer == null) DATA.singleplayer = new SingleplayerConfig();
    }
    
    public static void save() {
        Path configPath = getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            try (var writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(DATA, writer);
            }
        } catch (IOException e) {
            VoxyWorldGenV2.LOGGER.error("failed to save config", e);
        }
    }
    
    /** Floor the auto values use: generous enough to feel unthrottled, still bounded. */
    private static final int SINGLEPLAYER_AUTO_FLOOR = 128;

    private static boolean singleplayerActive(boolean isSingleplayer) {
        return isSingleplayer && DATA.singleplayer != null && DATA.singleplayer.enableSingleplayerDefaults;
    }

    public static int getGenerationRadius(boolean isSingleplayer) {
        if (singleplayerActive(isSingleplayer)) {
            int v = DATA.singleplayer.generationRadius;
            return v > 0 ? v : Math.max(SINGLEPLAYER_AUTO_FLOOR, DATA.generationRadius);
        }
        return DATA.generationRadius;
    }

    public static int getMaxActiveTasks(boolean isSingleplayer) {
        if (singleplayerActive(isSingleplayer)) {
            int v = DATA.singleplayer.maxActiveTasks;
            return v > 0 ? v : Math.max(SINGLEPLAYER_AUTO_FLOOR, DATA.maxActiveTasks);
        }
        return DATA.maxActiveTasks;
    }

    public static double getMaxMbpsPerPlayer(boolean isSingleplayer) {
        if (singleplayerActive(isSingleplayer)) {
            return DATA.singleplayer.maxMbpsPerPlayer;
        }
        return DATA.maxMbpsPerPlayer;
    }

    /**
     * The bandwidth cap for one specific player: their override if an operator set one, otherwise
     * the global/singleplayer resolution. An override of 0 means "unlimited for this player" —
     * present-with-zero and absent are different states, which is why this is a map and not a
     * default in disguise.
     */
    public static double getMaxMbpsForPlayer(java.util.UUID player, boolean isSingleplayer) {
        var overrides = DATA.playerRateLimits;
        if (overrides != null) {
            Double v = overrides.get(player.toString());
            if (v != null) return Math.max(0.0, v);
        }
        return getMaxMbpsPerPlayer(isSingleplayer);
    }

    /** Global LOD send distance in chunks; 0 = unlimited. */
    public static int getSendDistanceChunks(boolean isSingleplayer) {
        if (singleplayerActive(isSingleplayer)) {
            return Math.max(0, DATA.singleplayer.lodSendDistanceChunks);
        }
        return Math.max(0, DATA.lodSendDistanceChunks);
    }

    /** Send distance for one player in chunks, override first; 0 = unlimited. */
    public static int getSendDistanceForPlayer(java.util.UUID player, boolean isSingleplayer) {
        var overrides = DATA.playerSendDistances;
        if (overrides != null) {
            Integer v = overrides.get(player.toString());
            if (v != null) return Math.max(0, v);
        }
        return getSendDistanceChunks(isSingleplayer);
    }

    public static int getMaxChunksPerSecond(boolean isSingleplayer) {
        if (singleplayerActive(isSingleplayer)) {
            return DATA.singleplayer.maxChunksPerSecond;
        }
        return DATA.maxChunksPerSecond;
    }

    public static int getDimensionChangePauseSeconds(boolean isSingleplayer) {
        if (singleplayerActive(isSingleplayer)) {
            return DATA.singleplayer.dimensionChangePauseSeconds;
        }
        return DATA.dimensionChangePauseSeconds;
    }

    /**
     * Settings profile applied only while the integrated (singleplayer/LAN) server is running.
     * The multiplayer limits exist to protect a shared server's CPU and egress; none of that
     * applies to a world running on the player's own machine.
     *
     * <p>The auto sentinels (0) resolve to {@code max(128, base value)} so enabling this profile
     * can only ever RAISE the base radius/task limits, never silently shrink them — a profile
     * that limited below the user's own configured values is exactly the bug this replaces.
     * Explicit non-zero values are used verbatim, above or below the base: a setting, not a force.
     */
    public static class SingleplayerConfig {
        // Master switch, exposed in Mod Menu, the settings book, and /voxygen singleplayer.
        public boolean enableSingleplayerDefaults = true;
        public int generationRadius = 0;  // 0 = auto: max(128, generationRadius)
        public int maxActiveTasks = 0;    // 0 = auto: max(128, maxActiveTasks)
        public double maxMbpsPerPlayer = 0.0; // 0 = unlimited bandwidth
        public int dimensionChangePauseSeconds = 0; // 0 = no transition pause
        // Generation dispatch cap in chunks/second, 0 = unlimited. The knob for FPS drain:
        // the integrated server shares the machine with rendering, so pacing generation frees
        // CPU for frames without shrinking the radius.
        public int maxChunksPerSecond = 0;
        // LOD send distance in chunks, 0 = unlimited. Unlike radius/tasks this defaults to the
        // unlimited sentinel, not an auto floor: the data already exists on the local machine, so
        // there is no egress to protect.
        public int lodSendDistanceChunks = 0;
    }

    public static class ConfigData {
        public boolean enabled = true;
        public boolean showF3MenuStats = true;
        public int generationRadius = 64;
        public int update_interval = 20; // legacy field for Compat
        public int maxQueueSize = 20000;
        public int maxActiveTasks = 20;
        // Per-player LOD bandwidth cap in megabits/sec, applied to the WIRE (post-deflate) bytes
        // actually sent. 0 = unlimited. Default 2 = 250 KB/s of real traffic per player — safe
        // for hosted servers; LAN testing can `/voxygen ratelimit off`. Per-player overrides in
        // playerRateLimits beat this.
        public double maxMbpsPerPlayer = 2.0;
        // Worker dispatch cap in chunks/second across all players, 0 = unlimited. Paces how fast
        // the generation worker hands chunks to the chunk system; the semaphore (maxActiveTasks)
        // bounds how many are in flight at once, this bounds how many start per second.
        public int maxChunksPerSecond = 0;
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
        // How far LOD data is streamed to each player, in chunks. 256 matches the 4096-block cap
        // that used to be hardcoded in broadcastLODData, so upgrading changes nothing by itself.
        // 0 = unlimited.
        public int lodSendDistanceChunks = 256;
        // Per-player overrides, keyed by UUID string (what survives a Gson round-trip, same as
        // headlessPlayers). An entry always beats the global/singleplayer resolution; 0 means
        // unlimited for that player. Managed via /voxygen ratelimit|senddistance <player> ...
        public java.util.Map<String, Double> playerRateLimits = new java.util.HashMap<>();
        public java.util.Map<String, Integer> playerSendDistances = new java.util.HashMap<>();
        // Which traffic stats the tab HUD and /voxygen traffic render. All on by default; these
        // exist to let an operator declutter, not to hide the accounting.
        public boolean hudShowCompressed = true;
        public boolean hudShowSavings = true;
        public boolean hudShowClientDisk = true;
        // Singleplayer specific configuration profile (maxed out defaults for integrated server)
        public SingleplayerConfig singleplayer = new SingleplayerConfig();
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

