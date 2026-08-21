package com.ethan.voxyworldgenv2.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public String getModVersion() {
        return ModList.get()
            .getModContainerById(com.ethan.voxyworldgenv2.VoxyWorldGenV2.MOD_ID)
            .map(c -> c.getModInfo().getVersion().toString())
            .orElse("unknown");
    }
}
