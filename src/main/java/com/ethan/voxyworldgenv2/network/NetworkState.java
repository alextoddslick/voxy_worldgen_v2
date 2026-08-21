package com.ethan.voxyworldgenv2.network;

import java.util.concurrent.atomic.AtomicLong;

public class NetworkState {
    private static boolean serverConnected = false;
    private static volatile int serverProtocol = 0;
    private static final AtomicLong chunksReceived = new AtomicLong(0);
    private static final AtomicLong bytesReceived = new AtomicLong(0);

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

    /** The server registered the known-chunks payload, so sending it will not drop the connection. */
    public static boolean supportsKnownChunks() {
        return serverConnected && serverProtocol >= 2;
    }

    /** The server registered the storage-report payload, so sending it will not drop the connection. */
    public static boolean supportsStorageReport() {
        return serverConnected && serverProtocol >= 3;
    }

    /**
     * The server registered handshake-ack, server-config and settings payloads, so replying to the
     * handshake and requesting the settings snapshot will not drop the connection. All three
     * arrived together in protocol 5, so one gate covers the set.
     */
    public static boolean supportsHandshakeAck() {
        return serverConnected && serverProtocol >= 5;
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
