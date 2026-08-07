package com.ethan.voxyworldgenv2.client;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
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
    // handshake has landed, and abandoned if it never does (server without the mod).
    private static boolean pendingUpload;
    private static long pendingSinceMs;
    private static final long UPLOAD_WAIT_MS = 15_000L;

    private LodMemory() {}

    public static synchronized void record(int chunkX, int chunkZ) {
        if (dimensionId == null) return;
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
            } else if (now - pendingSinceMs > UPLOAD_WAIT_MS) {
                // No handshake in 15s: this server does not have the mod, or is too old.
                pendingUpload = false;
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

    private static String worldKeyFor(Minecraft client) {
        String raw;
        var server = client.getCurrentServer();
        if (server != null) {
            raw = "server:" + server.ip;
        } else if (client.getSingleplayerServer() != null) {
            raw = "single:" + client.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            raw = "unknown";
        }
        return sha256Hex(raw).substring(0, 16);
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
