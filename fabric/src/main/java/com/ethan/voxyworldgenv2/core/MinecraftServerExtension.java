package com.ethan.voxyworldgenv2.core;

import net.minecraft.world.level.storage.LevelStorageSource;

import java.util.function.BooleanSupplier;

public interface MinecraftServerExtension {
    void voxyworldgen$runHousekeeping(BooleanSupplier haveTime);
    void voxyworldgen$markHousekeeping();

    /**
     * The server's own {@link LevelStorageSource.LevelStorageAccess}, exposed because
     * {@code MinecraftServer.storageSource} is {@code protected}.
     *
     * <p>Callers on a thread other than the server thread must only touch members of the returned
     * object that are final-field reads -- {@code getLevelId()} is one. In particular
     * {@code getLevelPath(LevelResource)} is <em>not</em> safe off-thread: it is
     * {@code resources.computeIfAbsent(...)} over a plain {@link java.util.HashMap}, which the
     * server thread mutates as it resolves resources.
     */
    LevelStorageSource.LevelStorageAccess voxyworldgen$storageSource();
}
