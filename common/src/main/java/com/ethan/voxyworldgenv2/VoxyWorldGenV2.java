package com.ethan.voxyworldgenv2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// shared mod constants, the real entrypoints live in each loader
public final class VoxyWorldGenV2 {
    private VoxyWorldGenV2() {}

    public static final String MOD_ID = "voxyworldgenv2";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * The loaded mod version, e.g. {@code "2.5.2+mc26.2"} -- the {@code +mc} suffix carries the
     * Minecraft version, so callers never need to hardcode one. Read from loader metadata, which
     * {@code processResources} populates from {@code mod_version}, so the jar filename and every
     * string the mod prints derive from a single value and cannot disagree.
     *
     * <p>Catches {@link Throwable} rather than {@link Exception} on purpose: without the
     * ServiceLoader binding (a plain JUnit JVM) the first failure surfaces as
     * {@code ExceptionInInitializerError} and later ones as {@code NoClassDefFoundError}, both
     * {@link Error}s. Same reasoning as {@code Config.getConfigPath()}.
     */
    public static String modVersion() {
        try {
            return com.ethan.voxyworldgenv2.platform.Services.PLATFORM.getModVersion();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
