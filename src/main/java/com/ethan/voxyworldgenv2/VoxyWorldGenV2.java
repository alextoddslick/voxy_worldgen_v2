package com.ethan.voxyworldgenv2;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.event.ServerEventHandler;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxyWorldGenV2 implements ModInitializer {
    public static final String MOD_ID = "voxyworldgenv2";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // Read the version back out of fabric.mod.json (templated from gradle.properties'
        // `version=` at build time) rather than hardcoding it, so this line can never drift from
        // the jar filename -- five codebases all silently reporting version=2.2.5 at three
        // different protocol levels is exactly the failure this line exists to make impossible.
        String modVersion = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        LOGGER.info("voxy world gen v2 initializing (version {})", modVersion);
        com.ethan.voxyworldgenv2.core.Config.load();
        NetworkHandler.init();
        
        // server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(ServerEventHandler::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerEventHandler::onServerStopping);
        
        // player connection events
        ServerPlayConnectionEvents.JOIN.register(ServerEventHandler::onPlayerJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(ServerEventHandler::onPlayerDisconnect);
        
        // server tick event
        ServerTickEvents.END_SERVER_TICK.register(ServerEventHandler::onServerTick);

        // sync LOD data when a completed chunk loads into memory (issue #50)
        ServerChunkEvents.CHUNK_LOAD.register(ServerEventHandler::onChunkLoad);

        // operator commands - the only way to tune a dedicated server without a restart,
        // since the ModMenu config screen is client-side only
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) ->
                com.ethan.voxyworldgenv2.command.VoxyGenCommand.register(dispatcher));
    }
}
