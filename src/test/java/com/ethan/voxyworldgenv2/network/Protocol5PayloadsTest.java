package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.core.Config;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire round-trips for the six payloads added to reach protocol 5 (HandshakeAck, ServerConfig,
 * ServerConfigPush, StorageReport, SettingsSnapshot, SettingsUpdate). Like
 * {@link HandshakePayloadTest}, a plain {@code FriendlyByteBuf} over an unpooled buffer needs no
 * game harness, so the codec that only otherwise proves itself against a real client/server pair
 * is testable here.
 */
class Protocol5PayloadsTest {

    private static <T> T roundTrip(java.util.function.Consumer<FriendlyByteBuf> write,
                                   java.util.function.Function<FriendlyByteBuf, T> read) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            write.accept(buf);
            T result = read.apply(buf);
            assertFalse(buf.isReadable(), "the whole payload must be consumed");
            return result;
        } finally {
            buf.release();
        }
    }

    @Test
    void handshakeAckRoundTripsTheClientProtocol() {
        var read = roundTrip(
            buf -> new NetworkHandler.HandshakeAckPayload(NetworkHandler.PROTOCOL_VERSION).write(buf),
            NetworkHandler.HandshakeAckPayload::new);
        assertEquals(NetworkHandler.PROTOCOL_VERSION, read.clientProtocol());
    }

    @Test
    void serverConfigRoundTripsEveryFieldAndTheEditFlag() {
        var config = new Config.ServerConfig(false, 200, 15, 5000, 40);
        var read = roundTrip(
            buf -> new NetworkHandler.ServerConfigPayload(config, true).write(buf),
            NetworkHandler.ServerConfigPayload::new);

        assertEquals(config, read.config());
        assertTrue(read.canEdit());
    }

    @Test
    void serverConfigPushRoundTrips() {
        var config = new Config.ServerConfig(true, 64, 20, 20000, 20);
        var read = roundTrip(
            buf -> new NetworkHandler.ServerConfigPushPayload(config).write(buf),
            NetworkHandler.ServerConfigPushPayload::new);

        assertEquals(config, read.config());
    }

    @Test
    void applyServerConfigClampsOutOfRangeValuesRatherThanTrustingTheSender() {
        // The sender is a client: a value outside the Cloth-slider-equivalent bounds means a
        // hand-crafted packet, not a UI mistake.
        Config.DATA = new Config.ConfigData();
        Config.applyServerConfig(new Config.ServerConfig(true, 100_000, -5, -10, 0));

        assertEquals(512, Config.DATA.generationRadius, "clamped to the upper bound");
        assertEquals(1, Config.DATA.update_interval, "clamped to the lower bound");
        assertEquals(0, Config.DATA.maxQueueSize, "floored at zero");
        assertEquals(1, Config.DATA.maxActiveTasks, "clamped to the lower bound");
    }

    @Test
    void storageReportRoundTripsALargeByteCount() {
        long bytes = 12_345_678_901L; // > Integer.MAX_VALUE, exercises the long codec path
        var read = roundTrip(
            buf -> new NetworkHandler.StorageReportPayload(bytes).write(buf),
            NetworkHandler.StorageReportPayload::new);
        assertEquals(bytes, read.bytesOnDisk());
    }

    @Test
    void settingsSnapshotRoundTripsEveryField() {
        var payload = new NetworkHandler.SettingsSnapshotPayload(
            true, false,
            true, 96, 24, 40,
            1.5, true, 128,
            123_456L, 789L, 3, 512, 999L);

        var read = roundTrip(payload::write, NetworkHandler.SettingsSnapshotPayload::new);
        assertEquals(payload, read);
    }

    @Test
    void settingsUpdateRoundTripsAnOpList() {
        var payload = new NetworkHandler.SettingsUpdatePayload(List.of(
            new NetworkHandler.SettingsUpdatePayload.Op("generationRadius", "96"),
            new NetworkHandler.SettingsUpdatePayload.Op("spawnPregenEnabled", "false")));

        var read = roundTrip(payload::write, NetworkHandler.SettingsUpdatePayload::new);
        assertEquals(payload.ops(), read.ops());
    }

    /**
     * The codec bounds the op count BEFORE the op check happens (the op check happens later, on
     * the server thread), so a hostile client cannot make an unauthenticated packet allocate an
     * unbounded list.
     */
    @Test
    void settingsUpdateRejectsMoreOpsThanTheDeclaredMaximum() {
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
