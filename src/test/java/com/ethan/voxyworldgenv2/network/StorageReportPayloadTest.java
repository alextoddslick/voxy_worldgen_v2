package com.ethan.voxyworldgenv2.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client's "this is how much disk Voxy's store for this world uses" report. Serverbound,
 * protocol 3: a client must never send it to an older server, because an unregistered payload
 * drops the connection — the same rule the known-chunks upload already follows.
 */
class StorageReportPayloadTest {

    @Test
    void roundTripsTheByteCount() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new NetworkHandler.StorageReportPayload(3_221_225_472L).write(buf);

            NetworkHandler.StorageReportPayload read = new NetworkHandler.StorageReportPayload(buf);
            assertEquals(3_221_225_472L, read.bytesOnDisk());
            assertFalse(buf.isReadable(), "the whole payload must be consumed");
        } finally {
            buf.release();
        }
    }

    @Test
    void onlyAProtocolThreeServerAcceptsTheReport() {
        assertTrue(NetworkHandler.PROTOCOL_VERSION >= 3,
            "the storage report ships with protocol 3");
        try {
            NetworkState.setServerConnected(true);

            NetworkState.setServerProtocol(2);
            assertFalse(NetworkState.supportsStorageReport(),
                "a protocol-2 server has not registered the payload; sending would drop the connection");

            NetworkState.setServerProtocol(3);
            assertTrue(NetworkState.supportsStorageReport());
        } finally {
            NetworkState.setServerConnected(false);
        }
    }

    @Test
    void aProtocolThreeServerStillAcceptsKnownChunksUploads() {
        // The gates are independent floors, not an exact match: bumping the version must never
        // turn off a feature an older protocol already supported.
        try {
            NetworkState.setServerConnected(true);
            NetworkState.setServerProtocol(NetworkHandler.PROTOCOL_VERSION);
            assertTrue(NetworkState.supportsKnownChunks());
        } finally {
            NetworkState.setServerConnected(false);
        }
    }
}
