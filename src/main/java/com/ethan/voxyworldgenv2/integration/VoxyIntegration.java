package com.ethan.voxyworldgenv2.integration;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class VoxyIntegration {
    private static boolean initialized = false;
    private static boolean enabled = false;
    private static MethodHandle ingestMethod;
    private static MethodHandle rawIngestMethod;
    private static MethodHandle worldIdentifierOfMethod;

    // Voxy's rendering-enabled state. Either a no-arg accessor (static method or static field), or
    // an instance accessor plus a getter for the singleton that holds it. The singleton is read
    // fresh on every query rather than bound once at init: Voxy's VoxyConfig.CONFIG is a non-final
    // static that gets reassigned when the config is reloaded, so a bound handle would pin a stale
    // instance and silently report the old state forever.
    private static MethodHandle voxyEnabledStaticHandle;
    private static MethodHandle voxyEnabledInstanceHandle;
    private static MethodHandle voxyConfigSingletonGetter;

    private VoxyIntegration() {}

    private static void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            Class<?> ingestServiceClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            Class<?> worldIdentifierClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            
            Object serviceInstance = null;
            try {
                Field instanceField = ingestServiceClass.getDeclaredField("INSTANCE");
                serviceInstance = instanceField.get(null);
            } catch (Exception ignored) {
            }

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            // 1. find main ingest method
            String[] commonMethods = {"ingestChunk", "tryAutoIngestChunk", "enqueueIngest", "ingest"};
            Method targetMethod = null;
            for (String methodName : commonMethods) {
                try {
                    targetMethod = ingestServiceClass.getMethod(methodName, LevelChunk.class);
                    if (targetMethod != null) break;
                } catch (NoSuchMethodException ignored) {}
            }

            if (targetMethod != null) {
                ingestMethod = lookup.unreflect(targetMethod);
                if (serviceInstance != null && !Modifier.isStatic(targetMethod.getModifiers())) {
                    ingestMethod = ingestMethod.bindTo(serviceInstance);
                }
                enabled = true;
            }

            // 2. find rawIngest method
            try {
                Method rawIngest = ingestServiceClass.getMethod("rawIngest", 
                    worldIdentifierClass, 
                    net.minecraft.world.level.chunk.LevelChunkSection.class, 
                    int.class, int.class, int.class, 
                    net.minecraft.world.level.chunk.DataLayer.class, 
                    net.minecraft.world.level.chunk.DataLayer.class);
                rawIngestMethod = lookup.unreflect(rawIngest);
            } catch (NoSuchMethodException ignored) {}

            // 3. find WorldIdentifier.of method
            try {
                Method ofMethod = worldIdentifierClass.getMethod("of", net.minecraft.world.level.Level.class);
                worldIdentifierOfMethod = lookup.unreflect(ofMethod);
            } catch (NoSuchMethodException ignored) {}

            // find voxy enabled/active state accessor
            try {
                resolveEnabledAccessor(lookup, Class.forName("me.cortex.voxy.client.config.VoxyConfig"));
            } catch (ClassNotFoundException ignored) {
                // try alternate class names
                try {
                    resolveEnabledAccessor(lookup, Class.forName("me.cortex.voxy.client.VoxyClient"));
                } catch (ClassNotFoundException ignored3) {}
            }

            VoxyWorldGenV2.LOGGER.info("voxy integration initialized (enabled: {}, raw: {}, voxyEnabled: {})", enabled, rawIngestMethod != null, hasEnabledAccessor());

        } catch (ClassNotFoundException e) {
            VoxyWorldGenV2.LOGGER.info("voxy not present, integration disabled");
            enabled = false;
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("failed to initialize voxy integration", e);
            enabled = false;
        }
    }

    /**
     * Resolves Voxy's rendering-enabled state accessor on {@code owner}, preferring the most
     * complete signal available.
     *
     * <p>{@code isRenderingEnabled()} is checked first because it is the accessor that actually
     * answers this question — in Voxy it returns {@code isAvailable() && enabled && enableRendering},
     * whereas the raw {@code enabled} field is only one of those three terms. The others are
     * fallbacks for Voxy versions that expose a different shape.
     *
     * <p>Static and instance members are handled separately. An instance accessor is only usable if
     * the singleton holding it can also be located, so it is discarded otherwise rather than left
     * dangling — invoking an instance handle with no receiver throws.
     */
    private static void resolveEnabledAccessor(MethodHandles.Lookup lookup, Class<?> owner) {
        for (String name : new String[]{"isRenderingEnabled", "isEnabled"}) {
            try {
                Method m = owner.getMethod(name);
                if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
                if (Modifier.isStatic(m.getModifiers())) {
                    voxyEnabledStaticHandle = lookup.unreflect(m);
                    return;
                }
                voxyEnabledInstanceHandle = lookup.unreflect(m);
                break;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.debug("could not unreflect voxy accessor {}", name, e);
            }
        }

        if (voxyEnabledInstanceHandle == null) {
            try {
                Field enabledField = owner.getDeclaredField("enabled");
                enabledField.setAccessible(true);
                if (Modifier.isStatic(enabledField.getModifiers())) {
                    voxyEnabledStaticHandle = lookup.unreflectGetter(enabledField);
                    return;
                }
                voxyEnabledInstanceHandle = lookup.unreflectGetter(enabledField);
            } catch (Exception ignored) {
                return;
            }
        }

        voxyConfigSingletonGetter = resolveSingletonGetter(lookup, owner);
        if (voxyConfigSingletonGetter == null) {
            // no receiver available, so the instance handle is unusable
            voxyEnabledInstanceHandle = null;
        }
    }

    /** Finds a static field on {@code owner} holding an instance of {@code owner} itself. */
    private static MethodHandle resolveSingletonGetter(MethodHandles.Lookup lookup, Class<?> owner) {
        for (String name : new String[]{"CONFIG", "INSTANCE"}) {
            try {
                Field f = owner.getDeclaredField(name);
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!owner.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                return lookup.unreflectGetter(f);
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.debug("could not unreflect voxy singleton {}", name, e);
            }
        }
        return null;
    }

    private static boolean hasEnabledAccessor() {
        return voxyEnabledStaticHandle != null
            || (voxyEnabledInstanceHandle != null && voxyConfigSingletonGetter != null);
    }

    public static void ingestChunk(LevelChunk chunk) {
        if (!initialized) initialize();
        if (!enabled || ingestMethod == null) return;

        try {
            ingestMethod.invoke(chunk);
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("failed to ingest chunk", e);
        }
    }

    public static void rawIngest(LevelChunk chunk, net.minecraft.world.level.chunk.DataLayer skyLight) {
        if (!initialized) initialize();
        if (rawIngestMethod == null || worldIdentifierOfMethod == null) return;

        try {
            net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
            int cx = chunk.getPos().x();
            int cz = chunk.getPos().z();
            int minY = chunk.getMinSectionY();
            
            // get worldid once per chunk
            Object worldId = worldIdentifierOfMethod.invoke(chunk.getLevel());
            if (worldId == null) return;

            for (int i = 0; i < sections.length; i++) {
                net.minecraft.world.level.chunk.LevelChunkSection section = sections[i];
                if (section == null || section.hasOnlyAir()) continue;
                
                rawIngestMethod.invoke(worldId, section, cx, minY + i, cz, null, skyLight);
            }
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("failed to raw ingest chunk", e);
        }
    }
    
    public static void rawIngest(net.minecraft.world.level.Level level, net.minecraft.world.level.chunk.LevelChunkSection section, int cx, int cy, int cz, net.minecraft.world.level.chunk.DataLayer blockLight, net.minecraft.world.level.chunk.DataLayer skyLight) {
        if (!initialized) initialize();
        if (rawIngestMethod == null || worldIdentifierOfMethod == null) return;

        try {
            Object worldId = worldIdentifierOfMethod.invoke(level);
            if (worldId == null) return;
            
            rawIngestMethod.invoke(worldId, section, cx, cy, cz, blockLight, skyLight);
        } catch (Throwable e) {
            VoxyWorldGenV2.LOGGER.error("failed to raw ingest section", e);
        }
    }

    public static void rawIngest(net.minecraft.world.level.Level level, net.minecraft.world.level.chunk.LevelChunkSection section, int cx, int cy, int cz, net.minecraft.world.level.chunk.DataLayer skyLight) {
        rawIngest(level, section, cx, cy, cz, null, skyLight);
    }

    public static boolean isVoxyAvailable() {
        if (!initialized) initialize();
        return enabled;
    }

    public static boolean isVoxyRenderingEnabled() {
        if (!initialized) initialize();
        if (!enabled) return true; // voxy not present, don't suppress generation
        try {
            if (voxyEnabledStaticHandle != null) {
                Object result = voxyEnabledStaticHandle.invoke();
                if (result instanceof Boolean b) return b;
            } else if (voxyEnabledInstanceHandle != null && voxyConfigSingletonGetter != null) {
                Object config = voxyConfigSingletonGetter.invoke();
                if (config != null) {
                    Object result = voxyEnabledInstanceHandle.invoke(config);
                    if (result instanceof Boolean b) return b;
                }
            }
        } catch (Throwable t) {
            VoxyWorldGenV2.LOGGER.debug("failed to read voxy rendering state", t);
        }
        return true; // can't determine state, assume enabled
    }
}
