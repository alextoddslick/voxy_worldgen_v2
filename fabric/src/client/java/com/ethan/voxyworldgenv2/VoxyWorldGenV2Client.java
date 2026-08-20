package com.ethan.voxyworldgenv2;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

public class VoxyWorldGenV2Client implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Build stamp. A stale jar on the test machine has cost several debugging rounds; this
        // line makes "which build is actually running" answerable from the log alone.
        com.ethan.voxyworldgenv2.VoxyWorldGenV2.LOGGER.info(
            "[voxy-settings] client init - build 2026-08-20-sodium, sodium present: {}",
            net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium"));
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
            com.ethan.voxyworldgenv2.client.LodMemory.onDisconnect();
        });

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            com.ethan.voxyworldgenv2.network.NetworkClientHandler.drainIngestQueue();
            com.ethan.voxyworldgenv2.network.NetworkState.tick();
            // Drives the known-chunks upload. Without it the upload never happens, so the server's
            // join gate never lifts early and only times out -- and every chunk sendAsync touches
            // during that window used to be marked delivered and dropped.
            com.ethan.voxyworldgenv2.client.LodMemory.tick(client);
        });
    }
}
