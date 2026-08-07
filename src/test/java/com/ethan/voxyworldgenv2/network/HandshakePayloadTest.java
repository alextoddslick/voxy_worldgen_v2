package com.ethan.voxyworldgenv2.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The handshake codec, which is the one place a version mismatch is survivable or fatal.
 *
 * <p>{@code FriendlyByteBuf} over an unpooled buffer needs no game harness — no registries, no
 * bootstrap — so the compatibility case that otherwise only shows up as a dropped connection
 * against a real old server is testable here.
 */
class HandshakePayloadTest {

    @Test
    void roundTripsBothFields() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new NetworkHandler.HandshakePayload(true, NetworkHandler.PROTOCOL_VERSION).write(buf);

            NetworkHandler.HandshakePayload read = new NetworkHandler.HandshakePayload(buf);
            assertTrue(read.serverHasMod());
            assertEquals(NetworkHandler.PROTOCOL_VERSION, read.protocolVersion());
            assertFalse(buf.isReadable(), "the whole payload must be consumed");
        } finally {
            buf.release();
        }
    }

    /**
     * A protocol-1 server sends the boolean and nothing else. Reading a varint anyway throws
     * inside the netty decoder and drops the connection with an opaque "Internal Exception"
     * before the player reaches the world, so the missing field must read as version 1.
     */
    @Test
    void legacyOneBytePayloadReadsAsProtocolOne() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeBoolean(true); // exactly what protocol 1 wrote

            NetworkHandler.HandshakePayload read = new NetworkHandler.HandshakePayload(buf);
            assertTrue(read.serverHasMod());
            assertEquals(1, read.protocolVersion());
        } finally {
            buf.release();
        }
    }

    /**
     * The consequence of reading a legacy handshake as version 1: the upload gate stays shut, so
     * the client never sends a payload the old server has not registered.
     */
    @Test
    void aLegacyHandshakeLeavesTheUploadGateShut() {
        FriendlyByteBuf legacy = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf current = new FriendlyByteBuf(Unpooled.buffer());
        try {
            legacy.writeBoolean(true);
            new NetworkHandler.HandshakePayload(true, NetworkHandler.PROTOCOL_VERSION).write(current);

            NetworkState.setServerConnected(true);
            NetworkState.setServerProtocol(new NetworkHandler.HandshakePayload(legacy).protocolVersion());
            assertFalse(NetworkState.supportsKnownChunks(), "an old server must never be uploaded to");

            NetworkState.setServerProtocol(new NetworkHandler.HandshakePayload(current).protocolVersion());
            assertTrue(NetworkState.supportsKnownChunks(), "a current server must be");
        } finally {
            NetworkState.setServerConnected(false);
            legacy.release();
            current.release();
        }
    }
}
