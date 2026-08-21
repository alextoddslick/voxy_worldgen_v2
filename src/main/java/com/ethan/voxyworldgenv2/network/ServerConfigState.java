package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.core.Config;

/**
 * The client's cached copy of what {@link NetworkHandler.ServerConfigPayload} last delivered:
 * the remote server's live values, and whether this client is currently allowed to edit them.
 *
 * <p>Separate from {@link NetworkState} because that class is about connection/protocol
 * bookkeeping; this is about one specific payload's payload. Cleared on disconnect so a client that
 * was an operator on one server does not show editable controls on the next one before a fresh
 * payload arrives.
 */
public final class ServerConfigState {
    private static volatile Config.ServerConfig current = null;
    private static volatile boolean canEdit = false;

    private ServerConfigState() {}

    public static void set(Config.ServerConfig config, boolean editable) {
        current = config;
        canEdit = editable;
    }

    public static void clear() {
        current = null;
        canEdit = false;
    }

    /** True once a modded server (protocol >= 5) has sent its config. */
    public static boolean hasServerConfig() {
        return current != null;
    }

    public static boolean canEdit() {
        return canEdit;
    }

    /** The server's last-delivered values, or a local snapshot if none has arrived yet. */
    public static Config.ServerConfig get() {
        Config.ServerConfig c = current;
        return c != null ? c : Config.ServerConfig.snapshot();
    }
}
