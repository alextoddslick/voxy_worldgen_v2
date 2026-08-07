package com.ethan.voxyworldgenv2.client;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.MinecraftServerExtension;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import com.ethan.voxyworldgenv2.network.NetworkState;
import com.ethan.voxyworldgenv2.network.RegionBitmask;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers which chunks this client has successfully ingested into Voxy, so a reconnect does not
 * re-stream terrain Voxy already holds on disk.
 *
 * <p>A chunk is recorded only after ingest succeeds — the record must mean "Voxy has this", not
 * "a packet arrived", or the server will skip chunks that never made it in.
 *
 * <p>Keyed by a hash of the server address rather than by Voxy's own {@code WorldIdentifier}: this
 * key only has to be stable per server, and the codebase already carries enough version-fragile
 * reflection against Voxy internals. Known limitation — reaching one server by LAN IP and by
 * hostname yields two memory files, costing one redundant re-stream.
 */
@Environment(EnvType.CLIENT)
public final class LodMemory {
    private static final long FLUSH_INTERVAL_MS = 30_000L;

    private static final Map<Long, byte[]> regions = new HashMap<>();
    private static String worldKey;
    private static String dimensionId;
    private static ResourceKey<Level> dimension;
    private static boolean dirty;
    private static long lastFlushMs;

    // The handshake that tells us the server's protocol version arrives asynchronously, and the
    // client level can appear before it does. Uploading straight from switchTo() would therefore
    // silently no-op on a fast join, the server's gate would time out, and the whole radius would
    // re-stream — the exact bug this class exists to prevent. So the upload is deferred until the
    // handshake has landed. If it is simply late (server lag, slow channel registration) a single
    // fixed wait would abandon the upload permanently and re-stream the whole radius anyway, so
    // this retries on a backoff and only gives up after a few attempts (server without the mod,
    // or too old).
    private static boolean pendingUpload;
    private static long pendingSinceMs;
    private static int uploadAttempts;
    /** So a connection with no stable world identity is reported once, not once per dimension. */
    private static boolean unidentifiedWorldLogged;
    private static final long UPLOAD_INITIAL_WAIT_MS = 15_000L;
    private static final int MAX_UPLOAD_ATTEMPTS = 4;

    private LodMemory() {}

    /**
     * Records a chunk as known, but only for the dimension the caller says it belongs to.
     *
     * <p>{@code dimensionId}/{@code dimension} are only refreshed when {@link #tick} notices a
     * dimension change, up to one client tick after {@code Minecraft.getInstance().level} has
     * actually switched. A LOD payload for the new dimension can arrive and be ingested in that
     * window, so the caller must state which dimension it ingested for rather than this class
     * trusting whichever dimension it last cached — otherwise the record lands in the stale
     * (old) dimension's map and gets persisted there on the next flush.
     */
    public static synchronized void record(ResourceKey<Level> payloadDimension, int chunkX, int chunkZ) {
        if (worldKey == null || dimensionId == null || !payloadDimension.equals(dimension)) return;
        byte[] mask = regions.computeIfAbsent(
            RegionBitmask.regionKey(chunkX, chunkZ), k -> new byte[RegionBitmask.MASK_BYTES]);
        if (!RegionBitmask.get(mask, chunkX, chunkZ)) {
            RegionBitmask.set(mask, chunkX, chunkZ);
            dirty = true;
        }
    }

    /** Called every client tick: detects a dimension change and flushes on a debounce. */
    public static synchronized void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }

        ResourceKey<Level> current = level.dimension();
        if (!current.equals(dimension)) {
            switchTo(client, current);
            return;
        }

        long now = System.currentTimeMillis();

        if (pendingUpload) {
            if (NetworkState.supportsKnownChunks()) {
                upload();
                pendingUpload = false;
                uploadAttempts = 0;
            } else {
                // Exponential backoff: 15s, 30s, 60s, 120s between checks, capped at
                // MAX_UPLOAD_ATTEMPTS retries so a legitimately slow handshake (server lag, slow
                // channel registration) still gets uploaded instead of the whole radius
                // re-streaming just because the first check was too early.
                long waitMs = UPLOAD_INITIAL_WAIT_MS << uploadAttempts;
                if (now - pendingSinceMs > waitMs) {
                    uploadAttempts++;
                    if (uploadAttempts >= MAX_UPLOAD_ATTEMPTS) {
                        pendingUpload = false;
                        VoxyWorldGenV2.LOGGER.warn(
                            "giving up waiting for the server handshake after {} attempts; known LOD chunks for {} were not uploaded this session (still cached on disk for next join)",
                            uploadAttempts, dimensionId);
                    } else {
                        pendingSinceMs = now;
                    }
                }
            }
        }

        if (dirty && now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            flush();
            lastFlushMs = now;
        }
    }

    public static synchronized void onDisconnect() {
        flush();
        regions.clear();
        worldKey = null;
        dimensionId = null;
        dimension = null;
        dirty = false;
        pendingUpload = false;
        uploadAttempts = 0;
        unidentifiedWorldLogged = false;
    }

    /** Flush the previous dimension, load the new one, upload it. */
    private static void switchTo(Minecraft client, ResourceKey<Level> newDimension) {
        flush();
        regions.clear();

        worldKey = worldKeyFor(client);
        dimension = newDimension;
        dimensionId = newDimension.location().toString();
        dirty = false;
        lastFlushMs = System.currentTimeMillis();

        if (worldKey == null) {
            // No stable identity for this world: remember nothing, persist nothing, upload nothing.
            // The server then re-streams as it always did, which is the safe direction.
            if (!unidentifiedWorldLogged) {
                unidentifiedWorldLogged = true;
                VoxyWorldGenV2.LOGGER.warn(
                    "no stable world identity for this connection; LOD memory is disabled for this session");
            }
            return;
        }

        Path file = fileFor(worldKey, dimensionId);
        try {
            if (Files.exists(file)) {
                regions.putAll(RegionBitmask.readFile(Files.readAllBytes(file)));
            }
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.warn("discarding unreadable LOD memory {}: {}", file, e.toString());
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {}
            regions.clear();
        }

        pendingUpload = true;
        pendingSinceMs = System.currentTimeMillis();
        uploadAttempts = 0;
    }

    /**
     * Uploads the known set for the current dimension. Always sends at least one packet — even
     * when this client knows nothing — because the final packet is what lifts the server's join
     * gate. Only called once {@link NetworkState#supportsKnownChunks()} is true.
     */
    private static void upload() {
        List<byte[]> packets = RegionBitmask.encode(regions);
        for (int i = 0; i < packets.size(); i++) {
            ClientPlayNetworking.send(new NetworkHandler.KnownChunksPayload(
                dimension, i == packets.size() - 1, packets.get(i)));
        }
        VoxyWorldGenV2.LOGGER.info("uploaded {} known LOD regions for {} in {} packet(s)",
            regions.size(), dimensionId, packets.size());
    }

    private static void flush() {
        if (!dirty || worldKey == null || dimensionId == null) return;
        Path file = fileFor(worldKey, dimensionId);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, RegionBitmask.writeFile(regions));
            dirty = false;
        } catch (Exception e) {
            // Keep the in-memory set; the session still benefits, only persistence is lost.
            VoxyWorldGenV2.LOGGER.warn("could not persist LOD memory to {}: {}", file, e.toString());
        }
    }

    static Path fileFor(String worldKey, String dimensionId) {
        return FabricLoader.getInstance().getGameDir()
            .resolve("voxyworldgenv2").resolve("lodmemory").resolve(worldKey)
            .resolve(dimensionId.replace(':', '_').replace('/', '_') + ".bin");
    }

    /**
     * A key that identifies this world, or {@code null} when no stable identity is available.
     *
     * <p>Null is not a failure to be papered over with a shared fallback key: every unidentified
     * connection would then share one memory file, and one world's known set would be uploaded for
     * another — the same cross-world false positive as two saves sharing a key, with the same
     * permanently blank result. The caller disables persistence and upload instead.
     */
    private static String worldKeyFor(Minecraft client) {
        var server = client.getCurrentServer();
        if (server != null) {
            return sha256Hex("server:" + server.ip).substring(0, 16);
        }
        String levelId = singleplayerLevelId(client);
        if (levelId != null && !levelId.isEmpty()) {
            return sha256Hex("singleplayer:" + levelId).substring(0, 16);
        }
        return null;
    }

    /**
     * The save's storage level id — the directory identifier, not the display name.
     *
     * <p>Minecraft uniquifies the save folder ("New World (1)") but not the display name, so two
     * different saves can both be called "New World". Keying on the display name would give them
     * one shared memory file and make world B upload world A's known set.
     *
     * <p>This runs on the client thread while the integrated server thread runs independently, so
     * it must not call {@code MinecraftServer.getWorldPath(LevelResource)}. Verified against the
     * Mojang-mapped 1.21.1 jar: {@code getWorldPath(r)} is {@code storageSource.getLevelPath(r)},
     * which is {@code resources.computeIfAbsent(r, levelDirectory::resourcePath)} over a
     * {@code Maps.newHashMap()} — a plain, unsynchronised {@link java.util.HashMap}. The server
     * thread populates that same map lazily (PLAYER_STATS_DIR and PLAYER_ADVANCEMENTS_DIR are
     * resolved on player join, which is exactly when this first runs), and two threads inside one
     * {@code HashMap.computeIfAbsent} can corrupt a bucket chain and leave a thread spinning. That
     * is not a lost-key race that costs at most a cache entry — it is the failure class that has
     * already taken this project down once (see HANDOFF.md on BetterEnd's
     * {@code MountainPiece.heightmap}: a spin inside {@code HashMap.resize}, then the 60-second
     * watchdog).
     *
     * <p>So the id is read straight off {@code MinecraftServer.storageSource} (reached with an
     * {@code @Accessor} because the field is {@code protected}) via
     * {@code LevelStorageAccess.getLevelId()}, whose bytecode is a single {@code getfield} of the
     * {@code private final String levelId} — no map, no allocation, no mutation. Both that field
     * and {@code storageSource} are final and assigned in their constructors, so the cross-thread
     * read is safely published.
     *
     * <p>The value is unchanged from the previous {@code getWorldPath(ROOT).normalize()} result:
     * {@code LevelStorageAccess} is built as {@code new LevelStorageAccess(src, levelId,
     * baseDir.resolve(levelId))}, so {@code getLevelId()} is exactly the save directory's name.
     */
    private static String singleplayerLevelId(Minecraft client) {
        var integrated = client.getSingleplayerServer();
        if (integrated == null) return null;
        try {
            return ((MinecraftServerExtension) integrated).voxyworldgen$storageSource().getLevelId();
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.warn("could not read the singleplayer level id: {}", e.toString());
            return null;
        }
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the JRE spec", e);
        }
    }
}
