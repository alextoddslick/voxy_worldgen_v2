package com.ethan.voxyworldgenv2.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import it.unimi.dsi.fastutil.ints.IntSet;

// networking calls the shared code makes, each loader implements it
public interface INetworkBridge {

    void sendHandshake(ServerPlayer player);

    void sendServerConfig(ServerPlayer player);

    void sendLODData(ServerPlayer player, LevelChunk chunk);

    void broadcastLODData(LevelChunk chunk);

    void broadcastLODData(LevelChunk chunk, IntSet onlySectionYs);

    double syncRadiusSq();

    /**
     * Starts the loader's LOD sender. Called once from onServerStarted. Without it the Fabric send
     * queue stays stopped and every enqueue is refused, which looks exactly like "no terrain ever
     * streams" -- silent and total. NeoForge keeps its own pool and no-ops here.
     */
    void startSendQueue();

    /** True when the sender is at capacity, so callers can back off rather than queue and drop. */
    boolean isSendQueueSaturated();

    /** Post-deflate bytes sent to each player, for the settings screen's per-player table. */
    java.util.Map<java.util.UUID, Long> perPlayerWireBytes();

    void shutdown();
}
