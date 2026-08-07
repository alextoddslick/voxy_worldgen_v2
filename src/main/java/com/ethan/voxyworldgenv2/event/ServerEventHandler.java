package com.ethan.voxyworldgenv2.event;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.core.Config;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ServerEventHandler {
    private ServerEventHandler() {}
    
    public static void onServerStarted(MinecraftServer server) {
        VoxyWorldGenV2.LOGGER.info("server started, initializing manager");
        ChunkGenerationManager.getInstance().initialize(server);
    }
    
    public static void onServerStopping(MinecraftServer server) {
        VoxyWorldGenV2.LOGGER.info("server stopping, shutting down manager");
        ChunkGenerationManager.getInstance().shutdown();
        PlayerTracker.getInstance().clear();
    }
    
    public static void onPlayerJoin(ServerGamePacketListenerImpl handler, PacketSender sender, MinecraftServer server) {
        ServerPlayer player = handler.getPlayer();
        PlayerTracker.getInstance().addPlayer(player);
        PlayerTracker.getInstance().armGate(player.getUUID(),
            PlayerTracker.dimensionId(player.level().dimension()));
        NetworkHandler.sendHandshake(player);
    }
    
    public static void onPlayerDisconnect(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        PlayerTracker.getInstance().removePlayer(handler.getPlayer());
    }
    
    public static void onServerTick(MinecraftServer server) {
        ChunkGenerationManager.getInstance().tick();
    }

    public static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean newlyGenerated) {
        // re-ingest the freshly-loaded chunk so Voxy receives biome data with correct
        // neighbor context (fixes hard snow/biome blend edges on new worlds, issue #40).
        // also handles syncing pre-generated chunks that couldn't be sent at generation
        // time because the player wasn't loaded yet (issue #50).
        //
        // Skipped for a player who already holds the chunk: the issue-#40 re-send still happens
        // the first time a chunk is delivered, and a client that already has that version does not
        // need it again. Without this check every chunk load re-streams to every nearby player.
        //
        // Gated on rememberSentChunks: this is the feature's rollback switch, and a rollback that
        // only partly rolls back is worse than none. With it off, skip the store consultation
        // entirely and send to every nearby player on every load, exactly as before this task --
        // otherwise a still-connected player who unloads and reloads a chunk silently loses the
        // issue-#40 re-send even with the feature supposedly disabled.
        boolean skipKnown = Config.DATA.rememberSentChunks;
        String dim = skipKnown ? PlayerTracker.dimensionId(level.dimension()) : null;
        long key = chunk.getPos().toLong();
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            if (player.level() != level) continue;
            if (skipKnown) {
                var store = PlayerTracker.getInstance().getStore(player.getUUID());
                if (store != null && store.isSynced(dim, key)) continue;
            }
            NetworkHandler.sendLODData(player, chunk);
        }
    }
}
