package com.ethan.voxyworldgenv2.platform;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public String getModVersion() {
        return FabricLoader.getInstance()
            .getModContainer(com.ethan.voxyworldgenv2.VoxyWorldGenV2.MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }
}
