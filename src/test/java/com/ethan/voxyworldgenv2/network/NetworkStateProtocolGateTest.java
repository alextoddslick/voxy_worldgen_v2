package com.ethan.voxyworldgenv2.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the exact boundary of every protocol feature gate. Every gate is documented as a floor,
 * never equality: {@code supportsKnownChunks()} is {@code >= 2}, and the three protocol-5 gates
 * added alongside {@code HandshakeAckPayload}/{@code ServerConfigPayload}/
 * {@code SettingsSnapshotPayload}/{@code StorageReportPayload} are all {@code >= 5}. A gate that
 * regressed to {@code ==} would still pass "protocol 5 supports it" (5 happens to be the current
 * value everywhere) but would wrongly refuse a future protocol 6 client, which is exactly the
 * class of bug this file exists to catch.
 */
class NetworkStateProtocolGateTest {

    @AfterEach
    void tearDown() {
        NetworkState.setServerConnected(false);
    }

    @Test
    void knownChunksIsGatedAtExactlyProtocolTwo() {
        NetworkState.setServerConnected(true);

        NetworkState.setServerProtocol(1);
        assertFalse(NetworkState.supportsKnownChunks(), "protocol 1 predates the known-chunks payload");

        NetworkState.setServerProtocol(2);
        assertTrue(NetworkState.supportsKnownChunks(), "protocol 2 is the floor where it was introduced");
    }

    @Test
    void protocolFiveGatesAreGatedAtExactlyProtocolFive() {
        NetworkState.setServerConnected(true);

        NetworkState.setServerProtocol(4);
        assertFalse(NetworkState.supportsStorageReport(), "protocol 4 predates the storage report payload");
        assertFalse(NetworkState.supportsServerConfig(), "protocol 4 predates server config sync");
        assertFalse(NetworkState.supportsSettingsSync(), "protocol 4 predates settings sync");

        NetworkState.setServerProtocol(5);
        assertTrue(NetworkState.supportsStorageReport(), "protocol 5 is the floor where it was introduced");
        assertTrue(NetworkState.supportsServerConfig(), "protocol 5 is the floor where it was introduced");
        assertTrue(NetworkState.supportsSettingsSync(), "protocol 5 is the floor where it was introduced");
    }

    /**
     * The floor property itself: every version at or above the introduction point must keep
     * supporting the feature. A regression to a narrow equality or range check would fail this at
     * some version above the floor even though it might still pass at the floor itself. Walks well
     * past the current wire protocol (5) to guard against a future protocol 6, 7... peer being
     * wrongly refused.
     */
    @Test
    void everyGateRemainsSatisfiedAtEveryProtocolUpToAndWellPastCurrent() {
        NetworkState.setServerConnected(true);

        for (int protocol = 2; protocol <= NetworkHandler.PROTOCOL_VERSION + 5; protocol++) {
            NetworkState.setServerProtocol(protocol);
            assertTrue(NetworkState.supportsKnownChunks(),
                "known-chunks must stay supported at protocol " + protocol);
        }
        for (int protocol = 5; protocol <= NetworkHandler.PROTOCOL_VERSION + 5; protocol++) {
            NetworkState.setServerProtocol(protocol);
            assertTrue(NetworkState.supportsStorageReport(),
                "storage report must stay supported at protocol " + protocol);
            assertTrue(NetworkState.supportsServerConfig(),
                "server config sync must stay supported at protocol " + protocol);
            assertTrue(NetworkState.supportsSettingsSync(),
                "settings sync must stay supported at protocol " + protocol);
        }
    }

    /** Every gate is a floor on the CURRENT wire protocol, so any future protocol-6+ feature gate must follow the same shape. */
    @Test
    void currentProtocolIsAtLeastFiveAndSatisfiesEveryExistingFloor() {
        assertTrue(NetworkHandler.PROTOCOL_VERSION >= 5, "this suite assumes protocol 5 or later");

        NetworkState.setServerConnected(true);
        NetworkState.setServerProtocol(NetworkHandler.PROTOCOL_VERSION);

        assertTrue(NetworkState.supportsKnownChunks());
        assertTrue(NetworkState.supportsStorageReport());
        assertTrue(NetworkState.supportsServerConfig());
        assertTrue(NetworkState.supportsSettingsSync());
    }

    @Test
    void disconnectingClosesEveryGateRegardlessOfTheLastKnownProtocol() {
        NetworkState.setServerConnected(true);
        NetworkState.setServerProtocol(NetworkHandler.PROTOCOL_VERSION);
        assertTrue(NetworkState.supportsKnownChunks());
        assertTrue(NetworkState.supportsStorageReport());

        NetworkState.setServerConnected(false);

        assertFalse(NetworkState.supportsKnownChunks(), "no live connection -> nothing is supported, regardless of the cached protocol");
        assertFalse(NetworkState.supportsStorageReport());
        assertFalse(NetworkState.supportsServerConfig());
        assertFalse(NetworkState.supportsSettingsSync());
        assertEquals(0, NetworkState.getServerProtocol(), "disconnect must clear the cached protocol, not just the connected flag");
    }
}
