package com.ethan.voxyworldgenv2.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Mirrors fabric's MinecraftServerAccess: MinecraftServer.emptyTicks is private, and
// ChunkGenerationManager.cleanupTask needs to zero it so a dedicated server does not pause
// itself (and silence its own tick events) while generation is still running.
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccess {
    @Accessor("emptyTicks")
    void setEmptyTicks(int ticks);
}
