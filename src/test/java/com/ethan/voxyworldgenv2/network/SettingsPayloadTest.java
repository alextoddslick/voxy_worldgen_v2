package com.ethan.voxyworldgenv2.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Protocol 4: the settings screen's three payloads. The ack tells the server this client can show
 * the screen; the snapshot carries settings + live stats + (for ops) the player table; the update
 * carries string-keyed ops back, which SettingsApplier gives command semantics.
 */
class SettingsPayloadTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void handshakeAckRoundTrips() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new NetworkHandler.HandshakeAckPayload(4).write(buf);
            NetworkHandler.HandshakeAckPayload read = new NetworkHandler.HandshakeAckPayload(buf);
            assertEquals(4, read.clientProtocol());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }

    @Test
    void snapshotRoundTripsWithPlayerRows() {
        var row = new NetworkHandler.SettingsSnapshotPayload.PlayerRow(
            ALICE, "VoxyTester", true, 1000L, 9_650_000L, 165_000L, true, 10.0, false, 0);
        var sent = new NetworkHandler.SettingsSnapshotPayload(
            true, false,
            true, 64, 20, 0, 2.0, 256,
            true, true, false,
            24_300_000L, 140_000L, 123,
            0, 512, 26_957L,
            List.of(row));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            sent.write(buf);
            NetworkHandler.SettingsSnapshotPayload read = new NetworkHandler.SettingsSnapshotPayload(buf);
            assertFalse(buf.isReadable(), "the whole payload must be consumed");

            assertTrue(read.isOp());
            assertFalse(read.singleplayerActive());
            assertEquals(64, read.generationRadius());
            assertEquals(2.0, read.maxMbpsPerPlayer(), 0.001);
            assertEquals(256, read.lodSendDistanceChunks());
            assertFalse(read.hudClientDisk());
            assertEquals(123, read.zipRatioX10());
            assertEquals(26_957L, read.chunksDone());

            assertEquals(1, read.players().size());
            var r = read.players().get(0);
            assertEquals(ALICE, r.uuid());
            assertEquals("VoxyTester", r.name());
            assertTrue(r.online());
            assertEquals(165_000L, r.diskBytes());
            assertTrue(r.hasCap());
            assertEquals(10.0, r.capMbps(), 0.001);
            assertFalse(r.hasDist());
        } finally {
            buf.release();
        }
    }

    @Test
    void nonOpSnapshotCarriesNoPlayerRows() {
        var sent = new NetworkHandler.SettingsSnapshotPayload(
            false, true,
            true, 128, 40, 50, 0.0, 0,
            true, true, true,
            0L, 0L, 0,
            0, 512, 0L,
            List.of());

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            sent.write(buf);
            NetworkHandler.SettingsSnapshotPayload read = new NetworkHandler.SettingsSnapshotPayload(buf);
            assertFalse(read.isOp());
            assertTrue(read.players().isEmpty());
        } finally {
            buf.release();
        }
    }

    @Test
    void updateRoundTripsItsOps() {
        var sent = new NetworkHandler.SettingsUpdatePayload(List.of(
            new NetworkHandler.SettingsUpdatePayload.Op("radius", "128"),
            new NetworkHandler.SettingsUpdatePayload.Op("player." + ALICE + ".reset", "")));

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            sent.write(buf);
            NetworkHandler.SettingsUpdatePayload read = new NetworkHandler.SettingsUpdatePayload(buf);
            assertFalse(buf.isReadable());
            assertEquals(2, read.ops().size());
            assertEquals("radius", read.ops().get(0).key());
            assertEquals("128", read.ops().get(0).value());
            assertEquals("", read.ops().get(1).value());
        } finally {
            buf.release();
        }
    }

    @Test
    void protocolIsNowFour() {
        assertTrue(NetworkHandler.PROTOCOL_VERSION >= 4, "the settings screen ships with protocol 4");
    }
}
