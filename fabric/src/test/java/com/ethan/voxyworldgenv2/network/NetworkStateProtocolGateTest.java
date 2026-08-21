package com.ethan.voxyworldgenv2.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the exact boundary of every protocol feature gate. Both existing gates are documented as
 * floors, never equality (HANDOFF.md: {@code supportsKnownChunks()} is {@code >= 2},
 * {@code supportsStorageReport()} is {@code >= 3}) -- {@link HandshakePayloadTest} and
 * {@link StorageReportPayloadTest} already exercise these gates as part of larger scenarios, but
 * neither pins the exact floor value nor guards against the gate silently narrowing to a range or
 * an equality check. A gate that regressed to {@code ==} would still pass "protocol 5 supports
 * it" (5 happens to be the current value everywhere) but would wrongly refuse a future protocol 6
 * client, which is exactly the class of bug this file exists to catch.
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

    /**
     * The floor property itself: every version at or above the introduction point must keep
     * supporting the feature. A regression to a narrow equality or range check would fail this at
     * some version above the floor even though it might still pass at the floor itself.
     */
    @Test
    void bothGatesRemainSatisfiedAtEveryProtocolUpToAndPastCurrent() {
        NetworkState.setServerConnected(true);

        // Current wire protocol is 5 (NetworkHandler.PROTOCOL_VERSION); walk from each gate's
        // floor through current and one past it, asserting the floor never closes again.
        for (int protocol = 2; protocol <= NetworkHandler.PROTOCOL_VERSION + 1; protocol++) {
            NetworkState.setServerProtocol(protocol);
            assertTrue(NetworkState.supportsKnownChunks(),
                "known-chunks must stay supported at protocol " + protocol);
        }
        for (int protocol = 3; protocol <= NetworkHandler.PROTOCOL_VERSION + 1; protocol++) {
            NetworkState.setServerProtocol(protocol);
            assertTrue(NetworkState.supportsStorageReport(),
                "storage report must stay supported at protocol " + protocol);
        }
    }

    /** Both gates are floors on the CURRENT wire protocol, so any future protocol-5+ feature gate must follow the same shape. */
    @Test
    void currentProtocolIsAtLeastFiveAndSatisfiesBothExistingFloors() {
        assertTrue(NetworkHandler.PROTOCOL_VERSION >= 5, "this suite assumes protocol 5 or later");

        NetworkState.setServerConnected(true);
        NetworkState.setServerProtocol(NetworkHandler.PROTOCOL_VERSION);

        assertTrue(NetworkState.supportsKnownChunks());
        assertTrue(NetworkState.supportsStorageReport());
    }

    @Test
    void disconnectingClosesBothGatesRegardlessOfTheLastKnownProtocol() {
        NetworkState.setServerConnected(true);
        NetworkState.setServerProtocol(NetworkHandler.PROTOCOL_VERSION);
        assertTrue(NetworkState.supportsKnownChunks());

        NetworkState.setServerConnected(false);

        assertFalse(NetworkState.supportsKnownChunks(), "no live connection -> nothing is supported, regardless of the cached protocol");
        assertFalse(NetworkState.supportsStorageReport());
        assertEquals(0, NetworkState.getServerProtocol(), "disconnect must clear the cached protocol, not just the connected flag");
    }
}
