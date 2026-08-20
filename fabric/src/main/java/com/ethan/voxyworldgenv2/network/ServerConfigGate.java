package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.core.Config;

/**
 * Whether server-side settings may be edited from a client UI, and where an edit goes when it is.
 *
 * <p>Extracted so the ModMenu screen and the Video Settings rows cannot drift apart: they are two
 * front-ends onto one decision, and the failure mode of divergence is silent — the operator moves a
 * slider, nothing happens, and nothing is logged.
 */
public final class ServerConfigGate {

    private ServerConfigGate() {}

    /**
     * @param onRemoteServer whether a remote server owns these values
     * @return true when an edit will actually be honoured. In singleplayer the values are local, so
     *         edits always apply; on a remote server the flag the server pushed is the only
     *         authority, because the server re-checks permissions on receipt anyway.
     */
    public static boolean mayEdit(boolean onRemoteServer) {
        return !onRemoteServer || ServerConfigState.canEdit();
    }

    /** The values a UI should display: the server's if it sent any, otherwise the local config. */
    public static Config.ServerConfig current() {
        return ServerConfigState.get();
    }

    /**
     * Applies an edited snapshot. Locally in singleplayer, or pushed to the server when this client
     * is allowed to. Deliberately silent when not allowed: the UI is expected to have disabled the
     * control, so reaching here without permission is a programming error, not a user error.
     */
    public static void apply(Config.ServerConfig edited, boolean onRemoteServer) {
        if (!onRemoteServer) {
            Config.applyServerConfig(edited);
            Config.save();
            ChunkGenerationManager.getInstance().scheduleConfigReload();
        } else if (ServerConfigState.canEdit()) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new NetworkHandler.ServerConfigPushPayload(edited));
        }
    }

    /**
     * Resets cached server state on disconnect. Without this a stale config — and worse, a stale
     * canEdit=true — survived into the next session, so a client that was an operator on one server
     * showed editable controls on the next one until the game was restarted.
     */
    public static void onDisconnect() {
        ServerConfigState.clear();
        NetworkState.setServerConnected(false);
    }
}
