package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.core.Config;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire round-trip coverage for the six payloads that bring this branch from protocol 2 to
 * protocol 5: {@code HandshakeAckPayload}, {@code ServerConfigPayload}, {@code
 * ServerConfigPushPayload}, {@code StorageReportPayload}, {@code SettingsSnapshotPayload} and
 * {@code SettingsUpdatePayload}. Each needs no game harness -- no registries, no bootstrap -- the
 * same reason {@code HandshakePayloadTest} can test the handshake codec directly.
 */
class Protocol5PayloadsTest {

    private static <T> T roundTrip(T value, java.util.function.BiConsumer<T, FriendlyByteBuf> write,
                                    java.util.function.Function<FriendlyByteBuf, T> read) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            write.accept(value, buf);
            T result = read.apply(buf);
            assertFalse(buf.isReadable(), "the whole payload must be consumed");
            return result;
        } finally {
            buf.release();
        }
    }

    @Test
    void handshakeAckRoundTrips() {
        var read = roundTrip(new NetworkHandler.HandshakeAckPayload(5),
            NetworkHandler.HandshakeAckPayload::write, NetworkHandler.HandshakeAckPayload::new);
        assertEquals(5, read.clientProtocol());
    }

    @Test
    void serverConfigRoundTripsIncludingCanEditFlag() {
        Config.ServerConfig cfg = new Config.ServerConfig(true, 64, 20, 20000, 20);
        var read = roundTrip(new NetworkHandler.ServerConfigPayload(cfg, true),
            NetworkHandler.ServerConfigPayload::write, NetworkHandler.ServerConfigPayload::new);
        assertEquals(cfg, read.config());
        assertTrue(read.canEdit());

        var readNoEdit = roundTrip(new NetworkHandler.ServerConfigPayload(cfg, false),
            NetworkHandler.ServerConfigPayload::write, NetworkHandler.ServerConfigPayload::new);
        assertFalse(readNoEdit.canEdit());
    }

    @Test
    void serverConfigPushRoundTrips() {
        Config.ServerConfig cfg = new Config.ServerConfig(false, 128, 40, 5000, 10);
        var read = roundTrip(new NetworkHandler.ServerConfigPushPayload(cfg),
            NetworkHandler.ServerConfigPushPayload::write, NetworkHandler.ServerConfigPushPayload::new);
        assertEquals(cfg, read.config());
    }

    @Test
    void storageReportRoundTrips() {
        var read = roundTrip(new NetworkHandler.StorageReportPayload(3_221_225_472L),
            NetworkHandler.StorageReportPayload::write, NetworkHandler.StorageReportPayload::new);
        assertEquals(3_221_225_472L, read.bytesOnDisk());
    }

    @Test
    void settingsSnapshotRoundTrips() {
        var sent = new NetworkHandler.SettingsSnapshotPayload(
            true, false,
            true, 64, 20, 0,
            2.0,
            123456L, 789L,
            5, 512, 42L);
        var read = roundTrip(sent, NetworkHandler.SettingsSnapshotPayload::write, NetworkHandler.SettingsSnapshotPayload::new);
        assertEquals(sent, read);
    }

    @Test
    void settingsUpdateRoundTripsOps() {
        var sent = new NetworkHandler.SettingsUpdatePayload(List.of(
            new NetworkHandler.SettingsUpdatePayload.Op("radius", "128"),
            new NetworkHandler.SettingsUpdatePayload.Op("enabled", "true")));
        var read = roundTrip(sent, NetworkHandler.SettingsUpdatePayload::write, NetworkHandler.SettingsUpdatePayload::new);
        assertEquals(sent.ops(), read.ops());
    }

    /**
     * The op count is bounded hard at read time: the permission check happens later in the
     * receiver, so the codec itself must not let an unauthenticated packet allocate an unbounded
     * list.
     */
    @Test
    void settingsUpdateRejectsMoreThanMaxOps() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(NetworkHandler.SettingsUpdatePayload.MAX_OPS + 1);
            assertThrows(DecoderException.class, () -> new NetworkHandler.SettingsUpdatePayload(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void applyServerConfigClampsValuesFromUntrustedPackets() {
        Config.DATA = new Config.ConfigData();
        Config.applyServerConfig(new Config.ServerConfig(true, 99999, -5, -1, 99999));

        assertEquals(512, Config.DATA.generationRadius, "radius clamped to the max a client packet may set");
        assertEquals(1, Config.DATA.update_interval, "update interval clamped to its floor");
        assertEquals(0, Config.DATA.maxQueueSize, "queue size floored at zero rather than negative");
        assertEquals(128, Config.DATA.maxActiveTasks, "active tasks clamped to the max a client packet may set");
    }
}
