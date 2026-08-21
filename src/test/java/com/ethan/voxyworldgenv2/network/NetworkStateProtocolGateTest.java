package com.ethan.voxyworldgenv2.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the exact boundary of every protocol feature gate. All three are documented as floors,
 * never equality ({@code supportsKnownChunks()} is {@code >= 2}, {@code supportsStorageReport()}
 * is {@code >= 3}, {@code supportsHandshakeAck()} is {@code >= 5}) -- a gate that regressed to
 * {@code ==} would still pass "protocol 5 supports it" (5 happens to be the current value
 * everywhere) but would wrongly refuse a future protocol 6 client, which is exactly the class of
 * bug this file exists to catch.
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
    void storageReportIsGatedAtExactlyProtocolThree() {
        NetworkState.setServerConnected(true);

        NetworkState.setServerProtocol(2);
        assertFalse(NetworkState.supportsStorageReport(), "protocol 2 predates the storage report payload");

        NetworkState.setServerProtocol(3);
        assertTrue(NetworkState.supportsStorageReport(), "protocol 3 is the floor where it was introduced");
    }

    @Test
    void handshakeAckIsGatedAtExactlyProtocolFive() {
        NetworkState.setServerConnected(true);

        NetworkState.setServerProtocol(4);
        assertFalse(NetworkState.supportsHandshakeAck(), "protocol 4 predates the handshake-ack payload");

        NetworkState.setServerProtocol(5);
        assertTrue(NetworkState.supportsHandshakeAck(), "protocol 5 is the floor where it was introduced");
    }

    /**
     * The floor property itself: every version at or above the introduction point must keep
     * supporting the feature. A regression to a narrow equality or range check would fail this at
     * some version above the floor even though it might still pass at the floor itself.
     */
    @Test
    void allGatesRemainSatisfiedAtEveryProtocolUpToAndPastCurrent() {
        NetworkState.setServerConnected(true);

        for (int protocol = 2; protocol <= NetworkHandler.PROTOCOL_VERSION + 3; protocol++) {
            NetworkState.setServerProtocol(protocol);
            assertTrue(NetworkState.supportsKnownChunks(),
                "known-chunks must stay supported at protocol " + protocol);
        }
        for (int protocol = 3; protocol <= NetworkHandler.PROTOCOL_VERSION + 3; protocol++) {
            NetworkState.setServerProtocol(protocol);
            assertTrue(NetworkState.supportsStorageReport(),
                "storage report must stay supported at protocol " + protocol);
        }
        for (int protocol = 5; protocol <= NetworkHandler.PROTOCOL_VERSION + 3; protocol++) {
            NetworkState.setServerProtocol(protocol);
            assertTrue(NetworkState.supportsHandshakeAck(),
                "handshake-ack must stay supported at protocol " + protocol);
        }
    }

    /** All three gates are floors on the CURRENT wire protocol, so any future protocol-6+ feature gate must follow the same shape. */
    @Test
    void currentProtocolIsAtLeastFiveAndSatisfiesEveryExistingFloor() {
        assertTrue(NetworkHandler.PROTOCOL_VERSION >= 5, "this suite assumes protocol 5 or later");

        NetworkState.setServerConnected(true);
        NetworkState.setServerProtocol(NetworkHandler.PROTOCOL_VERSION);

        assertTrue(NetworkState.supportsKnownChunks());
        assertTrue(NetworkState.supportsStorageReport());
        assertTrue(NetworkState.supportsHandshakeAck());
    }
}
