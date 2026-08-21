package com.ethan.voxyworldgenv2.network;

import java.util.concurrent.atomic.AtomicLong;

public class NetworkState {
    private static boolean serverConnected = false;
    private static volatile int serverProtocol = 0;
    private static final AtomicLong chunksReceived = new AtomicLong(0);
    private static final AtomicLong bytesReceived = new AtomicLong(0);

    // Last values pushed by the server over the protocol-5 config/settings payloads. Held here
    // (client-agnostic, main source set) rather than a dedicated holder class so a future settings
    // screen has somewhere to read from without adding another cross-thread singleton.
    private static volatile com.ethan.voxyworldgenv2.core.Config.ServerConfig lastServerConfig = null;
    private static volatile boolean canEditServerConfig = false;
    private static volatile NetworkHandler.SettingsSnapshotPayload lastSettingsSnapshot = null;

    private static double receiveRate = 0; // chunks/s
    private static double bandwidthRate = 0; // bytes/s

    private static long lastUpdateTime = 0;
    private static long lastChunkCount = 0;
    private static long lastByteCount = 0;

    public static void setServerConnected(boolean connected) {
        serverConnected = connected;
        if (!connected) {
            chunksReceived.set(0);
            bytesReceived.set(0);
            receiveRate = 0;
            bandwidthRate = 0;
            lastUpdateTime = 0;
            lastChunkCount = 0;
            lastByteCount = 0;
            serverProtocol = 0;
            lastServerConfig = null;
            canEditServerConfig = false;
            lastSettingsSnapshot = null;
        }
    }

    public static boolean isServerConnected() {
        return serverConnected;
    }

    public static void setServerProtocol(int version) {
        serverProtocol = version;
    }

    public static int getServerProtocol() {
        return serverProtocol;
    }

    /**
     * Feature gates are floors, never equality: a newer server must keep accepting everything an
     * older one did. An unregistered serverbound payload drops the connection, which is why the
     * client checks before sending rather than after failing.
     */
    public static boolean supportsKnownChunks() {
        return serverConnected && serverProtocol >= 2;
    }

    /** The server registered the storage-report payload (protocol 5+). */
    public static boolean supportsStorageReport() {
        return serverConnected && serverProtocol >= 5;
    }

    /** The server registered the server-config sync/push payloads (protocol 5+). */
    public static boolean supportsServerConfig() {
        return serverConnected && serverProtocol >= 5;
    }

    /** The server registered the settings snapshot/update payloads (protocol 5+). */
    public static boolean supportsSettingsSync() {
        return serverConnected && serverProtocol >= 5;
    }

    public static void setServerConfig(com.ethan.voxyworldgenv2.core.Config.ServerConfig config, boolean canEdit) {
        lastServerConfig = config;
        canEditServerConfig = canEdit;
    }

    public static com.ethan.voxyworldgenv2.core.Config.ServerConfig getServerConfig() {
        return lastServerConfig;
    }

    public static boolean canEditServerConfig() {
        return canEditServerConfig;
    }

    public static void setSettingsSnapshot(NetworkHandler.SettingsSnapshotPayload snapshot) {
        lastSettingsSnapshot = snapshot;
    }

    public static NetworkHandler.SettingsSnapshotPayload getSettingsSnapshot() {
        return lastSettingsSnapshot;
    }

    public static void incrementReceived(long bytes) {
        chunksReceived.incrementAndGet();
        bytesReceived.addAndGet(bytes);
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (lastUpdateTime == 0) {
            lastUpdateTime = now;
            lastChunkCount = chunksReceived.get();
            lastByteCount = bytesReceived.get();
            return;
        }

        long delta = now - lastUpdateTime;
        if (delta >= 1000) {
            long currentChunkCount = chunksReceived.get();
            long currentByteCount = bytesReceived.get();
            
            double seconds = delta / 1000.0;
            receiveRate = (currentChunkCount - lastChunkCount) / seconds;
            bandwidthRate = (currentByteCount - lastByteCount) / seconds;
            
            lastChunkCount = currentChunkCount;
            lastByteCount = currentByteCount;
            lastUpdateTime = now;
        }
    }

    public static double getReceiveRate() {
        return receiveRate;
    }

    public static double getBandwidthRate() {
        return bandwidthRate;
    }

    public static long getChunksReceived() {
        return chunksReceived.get();
    }

    public static long getBytesReceived() {
        return bytesReceived.get();
    }
}
