package com.ethan.voxyworldgenv2;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

public class VoxyWorldGenV2Client implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        VoxyWorldGenV2.LOGGER.info("initializing voxy world gen v2 client");

        // pause the worker when the game is paused
        ChunkGenerationManager.getInstance().setPauseCheck(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.isPaused();
        });

        com.ethan.voxyworldgenv2.network.NetworkClientHandler.init();

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // Clears NetworkState AND ServerConfigState. Previously only the former ran, so a stale
            // server config -- and a stale canEdit=true -- survived into the next session: a client
            // that was an operator on one server showed editable controls on the next until restart.
            com.ethan.voxyworldgenv2.network.ServerConfigGate.onDisconnect();
        });

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            com.ethan.voxyworldgenv2.network.NetworkClientHandler.drainIngestQueue();
            com.ethan.voxyworldgenv2.network.NetworkState.tick();
        });
    }
}
