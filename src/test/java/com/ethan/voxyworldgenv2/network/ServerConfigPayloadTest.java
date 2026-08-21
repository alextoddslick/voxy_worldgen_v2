package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.core.Config;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Protocol 5's two new payloads: the server's live values plus edit permission (S2C), and an op's
 * pushed edit (C2S). Both carry {@link Config.ServerConfig}, the wire-serialisable subset an
 * operator can push from the client ModMenu/Cloth screen.
 *
 * <p>Also covers the protocol-floor discipline the whole handshake depends on: gates are always
 * {@code >=}, never {@code ==}, so a future protocol 6+ server must keep offering everything
 * protocol 5 offered.
 */
class ServerConfigPayloadTest {

    @Test
    void serverConfigPayloadRoundTrips() {
        var cfg = new Config.ServerConfig(true, 96, 40, 15_000, 32);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new NetworkHandler.ServerConfigPayload(cfg, true).write(buf);

            NetworkHandler.ServerConfigPayload read = new NetworkHandler.ServerConfigPayload(buf);
            assertFalse(buf.isReadable(), "the whole payload must be consumed");
            assertTrue(read.canEdit());
            assertEquals(cfg, read.config());
            assertTrue(read.config().enabled());
            assertEquals(96, read.config().generationRadius());
            assertEquals(40, read.config().updateInterval());
            assertEquals(15_000, read.config().maxQueueSize());
            assertEquals(32, read.config().maxActiveTasks());
        } finally {
            buf.release();
        }
    }

    @Test
    void serverConfigPushPayloadRoundTrips() {
        var cfg = new Config.ServerConfig(false, 200, 10, 0, 5);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new NetworkHandler.ServerConfigPushPayload(cfg).write(buf);

            NetworkHandler.ServerConfigPushPayload read = new NetworkHandler.ServerConfigPushPayload(buf);
            assertFalse(buf.isReadable(), "the whole payload must be consumed");
            assertEquals(cfg, read.config());
        } finally {
            buf.release();
        }
    }

    /**
     * Clamped, not trusted: applyServerConfig treats the sender as untrusted input regardless of
     * whether the packet came from an authorised push, since a hand-crafted packet is
     * indistinguishable from a UI mistake once it's on the wire.
     */
    @Test
    void applyServerConfigClampsOutOfRangeValues() {
        Config.DATA = new Config.ConfigData();
        Config.applyServerConfig(new Config.ServerConfig(true, 99999, -5, -100, 0));

        assertEquals(512, Config.DATA.generationRadius, "radius clamps to the UI's own max of 512");
        assertEquals(1, Config.DATA.update_interval, "update interval clamps to a minimum of 1");
        assertEquals(0, Config.DATA.maxQueueSize, "queue size floors at 0, never negative");
        assertEquals(1, Config.DATA.maxActiveTasks, "active tasks clamps to a minimum of 1");
    }

    @Test
    void protocolFloorsAreGreaterOrEqualNeverEquality() {
        assertTrue(NetworkHandler.PROTOCOL_VERSION >= 5,
            "server-config push ships with protocol 5");
        try {
            NetworkState.setServerConnected(true);

            NetworkState.setServerProtocol(4);
            assertFalse(NetworkState.supportsServerConfig(),
                "a protocol-4 server has not registered server_config_push; sending would drop the connection");

            NetworkState.setServerProtocol(5);
            assertTrue(NetworkState.supportsServerConfig(), "exactly protocol 5 must be supported");

            // The floor is >=, not ==: a hypothetical future protocol 6+ server must still be
            // treated as supporting everything protocol 5 introduced.
            NetworkState.setServerProtocol(6);
            assertTrue(NetworkState.supportsServerConfig(), "protocol 6 must still satisfy the >= 5 floor");

            NetworkState.setServerProtocol(42);
            assertTrue(NetworkState.supportsServerConfig(), "an arbitrarily higher protocol must still satisfy the floor");
        } finally {
            NetworkState.setServerConnected(false);
        }
    }

    @Test
    void aProtocolFiveServerStillSupportsEveryOlderFeature() {
        // Bumping the floor must never turn off a feature an older protocol already granted.
        try {
            NetworkState.setServerConnected(true);
            NetworkState.setServerProtocol(NetworkHandler.PROTOCOL_VERSION);

            assertTrue(NetworkState.supportsKnownChunks(), "protocol 2 feature");
            assertTrue(NetworkState.supportsStorageReport(), "protocol 3 feature");
            assertTrue(NetworkState.supportsServerConfig(), "protocol 5 feature");
        } finally {
            NetworkState.setServerConnected(false);
        }
    }
}
