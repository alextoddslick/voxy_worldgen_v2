package com.ethan.voxyworldgenv2.event;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import com.ethan.voxyworldgenv2.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ServerEventHandler {
    private ServerEventHandler() {}

    public static void onServerStarted(MinecraftServer server) {
        Services.NETWORK.startSendQueue();
        VoxyWorldGenV2.LOGGER.info("server started, initializing manager");
        ChunkGenerationManager.getInstance().initialize(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        VoxyWorldGenV2.LOGGER.info("server stopping, shutting down manager");
        ChunkGenerationManager.getInstance().shutdown();
        Services.NETWORK.shutdown();
        PlayerTracker.getInstance().clear();
    }

    public static void onPlayerJoin(ServerPlayer player) {
        PlayerTracker.getInstance().addPlayer(player);
        Services.NETWORK.sendHandshake(player);
    }

    public static void onPlayerDisconnect(ServerPlayer player) {
        PlayerTracker.getInstance().removePlayer(player);
    }

    // op changed so re-send config to update the client edit lock
    public static void onPermissionsChanged(ServerPlayer player) {
        Services.NETWORK.sendServerConfig(player);
    }

    public static void onServerTick(MinecraftServer server) {
        ChunkGenerationManager.getInstance().tick();
    }

    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        // fires for every vanilla chunk load, so skip the loop when nobody is online
        if (PlayerTracker.getInstance().getPlayerCount() == 0) return;

        // Gated on rememberSentChunks: this is the feature's rollback switch, and a rollback that
        // only partly rolls back is worse than none. With it off, skip the store consultation
        // entirely and send to every nearby player on every load.
        boolean skipKnown = com.ethan.voxyworldgenv2.core.Config.DATA.rememberSentChunks;
        String dim = skipKnown ? PlayerTracker.dimensionId(level.dimension()) : null;
        long packed = Services.CHUNK_POS.packPos(chunk.getPos());

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            if (player.level() != level) continue;
            if (skipKnown) {
                var store = PlayerTracker.getInstance().getStore(player.getUUID());
                if (store != null && store.isSynced(dim, packed)) {
                    // "Claimed" is not "delivered". markDeferred records a chunk that was marked
                    // synced but whose send never happened; without consulting it here that write
                    // is dead and the chunk is lost for the session.
                    if (!store.isDeferred(dim, packed)) continue;
                    // Send it now and drop the deferral, so this recovery happens once rather than
                    // on every subsequent load.
                    store.clearDeferred(dim, packed);
                }
            }
            Services.NETWORK.sendLODData(player, chunk);
        }
    }
}
