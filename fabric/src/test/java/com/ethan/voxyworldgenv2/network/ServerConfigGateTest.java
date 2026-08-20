package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.core.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The decision of whether an edit may be applied at all, shared by the ModMenu screen and the
 * Video Settings rows. This is the half with real logic -- the widgets are just widgets -- and a
 * bug here does nothing visible, it silently drops the operator's change.
 */
class ServerConfigGateTest {

    @BeforeEach
    void setUp() {
        Config.DATA = new Config.ConfigData();
        ServerConfigState.clear();
    }

    @AfterEach
    void tearDown() {
        ServerConfigState.clear();
        NetworkState.setServerConnected(false);
    }

    /**
     * Singleplayer has no server to push to, so edits always apply locally. Gating this on canEdit
     * would lock a solo player out of their own settings.
     */
    @Test
    void singleplayerMayAlwaysEdit() {
        assertTrue(ServerConfigGate.mayEdit(false), "not on a remote server, so always editable");
    }

    /** On a remote server the operator flag the server pushed is the only authority. */
    @Test
    void remoteServerRequiresTheServersOwnCanEditFlag() {
        ServerConfigState.set(new Config.ServerConfig(true, 64, 20, 20000, 20), false);
        assertFalse(ServerConfigGate.mayEdit(true), "server said this client is not an operator");

        ServerConfigState.set(new Config.ServerConfig(true, 64, 20, 20000, 20), true);
        assertTrue(ServerConfigGate.mayEdit(true));
    }

    /**
     * A stale canEdit from a previous session must not carry over. ServerConfigState.clear() is the
     * only thing that resets it, and before this it was never called on disconnect -- so an op on
     * one server stayed "op" in the UI on the next one until the game was restarted.
     */
    @Test
    void disconnectingClearsAStaleOperatorFlag() {
        ServerConfigState.set(new Config.ServerConfig(true, 64, 20, 20000, 20), true);
        assertTrue(ServerConfigState.canEdit());

        ServerConfigGate.onDisconnect();

        assertFalse(ServerConfigState.canEdit(), "a stale op flag must not survive into the next session");
        assertFalse(ServerConfigState.hasServerConfig());
    }

    /** Values read back come from the server when it sent them, and from local config otherwise. */
    @Test
    void readsServerValuesWhenPresentAndLocalOtherwise() {
        Config.DATA.generationRadius = 64;
        assertEquals(64, ServerConfigGate.current().generationRadius(), "no server config -> local snapshot");

        ServerConfigState.set(new Config.ServerConfig(true, 300, 20, 20000, 20), true);
        assertEquals(300, ServerConfigGate.current().generationRadius(), "server config wins once received");
    }
}
