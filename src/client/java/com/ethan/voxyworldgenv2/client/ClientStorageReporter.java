package com.ethan.voxyworldgenv2.client;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import com.ethan.voxyworldgenv2.network.NetworkState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically tells the server how much disk Voxy's store for the CURRENT world occupies (ported
 * from unified as part of the protocol-5 convergence, {@code StorageReportPayload}).
 *
 * <p>The store location mirrors Voxy's own {@code VoxyClientInstance.getBasePath()}: the
 * {@code <world>/voxy} folder on an integrated server, {@code .voxy/saves/<ip with : as _>} for a
 * multiplayer connection, {@code .voxy/saves/realms} on a realm. Replicated rather than reflected:
 * it is three lines of stable convention, and reflecting into a Voxy internal would break on the
 * exact class this mod otherwise never touches.
 *
 * <p>The walk runs on the shared background executor, never the render thread — a mature store is
 * gigabytes across thousands of region files. One walk per minute, one in flight at a time, and a
 * report is only sent when the size actually changed (the server keeps the last value).
 */
public final class ClientStorageReporter {
    private static final long REPORT_INTERVAL_MS = 60_000;

    private static volatile long lastAttemptMs = 0;
    private static volatile long lastReportedBytes = -1;
    private static final AtomicBoolean walkInFlight = new AtomicBoolean(false);

    private ClientStorageReporter() {}

    /** Called once per client tick; cheap unless a report is due. */
    public static void tick(Minecraft client) {
        if (client.level == null) return;
        if (!NetworkState.supportsStorageReport()) return;

        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < REPORT_INTERVAL_MS) return;
        if (!walkInFlight.compareAndSet(false, true)) return;
        lastAttemptMs = now;

        Path store = resolveStorePath(client);
        Util.backgroundExecutor().execute(() -> {
            try {
                long bytes = store == null ? -1 : directorySize(store);
                if (bytes < 0 || bytes == lastReportedBytes) return;
                lastReportedBytes = bytes;
                client.execute(() -> {
                    if (ClientPlayNetworking.canSend(NetworkHandler.StorageReportPayload.TYPE)) {
                        ClientPlayNetworking.send(new NetworkHandler.StorageReportPayload(bytes));
                    }
                });
            } catch (Throwable t) {
                VoxyWorldGenV2.LOGGER.debug("voxy store size walk failed", t);
            } finally {
                walkInFlight.set(false);
            }
        });
    }

    /** Reset so the next session re-reports immediately instead of inheriting a stale baseline. */
    public static void onDisconnect() {
        lastAttemptMs = 0;
        lastReportedBytes = -1;
    }

    private static Path resolveStorePath(Minecraft client) {
        var integrated = client.getSingleplayerServer();
        if (integrated != null) {
            return integrated.getWorldPath(LevelResource.ROOT).resolve("voxy");
        }
        Path saves = client.gameDirectory.toPath().resolve(".voxy").resolve("saves");
        ServerData server = client.getCurrentServer();
        if (server == null) return null;
        if (server.isRealm()) return saves.resolve("realms");
        return saves.resolve(server.ip.replace(":", "_"));
    }

    /**
     * Total size of the regular files under {@code dir}; -1 if it does not exist. "No store" and
     * "empty store" must stay distinguishable — the caller sends nothing for -1, while a 0 would
     * travel to the server and render as truth. Files vanishing mid-walk (live DB compaction) are
     * skipped, not fatal.
     */
    static long directorySize(Path dir) {
        if (!Files.isDirectory(dir)) return -1;
        long[] total = new long[1];
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return -1;
        }
        return total[0];
    }
}
