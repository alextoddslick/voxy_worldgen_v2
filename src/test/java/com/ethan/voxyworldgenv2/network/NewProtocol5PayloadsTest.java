package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.core.Config;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip codec coverage for the six payloads this branch was missing to reach protocol 5:
 * HandshakeAck, ServerConfig, ServerConfigPush, SettingsSnapshot, SettingsUpdate, StorageReport.
 * None of these touch ChunkPos/ResourceKey, so unlike DistanceGraphWorkTerminationTest they need
 * no Bootstrap.bootStrap() -- a plain FriendlyByteBuf over an unpooled buffer is enough.
 */
class NewProtocol5PayloadsTest {

    @Test
    void handshakeAckRoundTrips() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new NetworkHandler.HandshakeAckPayload(NetworkHandler.PROTOCOL_VERSION).write(buf);
            NetworkHandler.HandshakeAckPayload read = new NetworkHandler.HandshakeAckPayload(buf);
            assertEquals(NetworkHandler.PROTOCOL_VERSION, read.clientProtocol());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }

    @Test
    void serverConfigRoundTrips() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            Config.ServerConfig cfg = new Config.ServerConfig(true, 96, 20, 12345, 40);
            new NetworkHandler.ServerConfigPayload(cfg, true).write(buf);

            NetworkHandler.ServerConfigPayload read = new NetworkHandler.ServerConfigPayload(buf);
            assertEquals(cfg, read.config());
            assertTrue(read.canEdit());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }

    @Test
    void serverConfigPushRoundTrips() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            Config.ServerConfig cfg = new Config.ServerConfig(false, 64, 20, 20000, 20);
            new NetworkHandler.ServerConfigPushPayload(cfg).write(buf);

            NetworkHandler.ServerConfigPushPayload read = new NetworkHandler.ServerConfigPushPayload(buf);
            assertEquals(cfg, read.config());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }

    @Test
    void storageReportRoundTrips() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new NetworkHandler.StorageReportPayload(123_456_789L).write(buf);
            NetworkHandler.StorageReportPayload read = new NetworkHandler.StorageReportPayload(buf);
            assertEquals(123_456_789L, read.bytesOnDisk());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }

    @Test
    void settingsSnapshotRoundTrips() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkHandler.SettingsSnapshotPayload snap = new NetworkHandler.SettingsSnapshotPayload(
                true, false,
                true, 64, 20, 0,
                2.0,
                1_000_000L, 5_000L, 34,
                3, 20000, 987654L);
            snap.write(buf);

            NetworkHandler.SettingsSnapshotPayload read = new NetworkHandler.SettingsSnapshotPayload(buf);
            assertEquals(snap, read);
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }

    @Test
    void settingsUpdateRoundTrips() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            NetworkHandler.SettingsUpdatePayload payload = new NetworkHandler.SettingsUpdatePayload(List.of(
                new NetworkHandler.SettingsUpdatePayload.Op("generationRadius", "96"),
                new NetworkHandler.SettingsUpdatePayload.Op("enabled", "true")));
            payload.write(buf);

            NetworkHandler.SettingsUpdatePayload read = new NetworkHandler.SettingsUpdatePayload(buf);
            assertEquals(payload.ops(), read.ops());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }

    /**
     * The codec must refuse to allocate an unbounded list for an unauthenticated packet -- the op
     * permission check happens later, in NetworkHandler.receiveSettingsUpdate, so the read itself
     * is the only thing standing between a hostile length-prefixed count and an OOM.
     */
    @Test
    void settingsUpdateRejectsAnOversizedOpCount() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(NetworkHandler.SettingsUpdatePayload.MAX_OPS + 1);
            assertThrows(io.netty.handler.codec.DecoderException.class,
                () -> new NetworkHandler.SettingsUpdatePayload(buf));
        } finally {
            buf.release();
        }
    }
}
