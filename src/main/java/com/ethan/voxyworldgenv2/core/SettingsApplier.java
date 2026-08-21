package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;

import java.util.List;
import java.util.UUID;

/**
 * Applies the settings screen's string-keyed ops with exactly the semantics and clamps of the
 * equivalent /voxygen commands. String keys keep the wire format version-tolerant: an unknown key
 * (a newer client talking to an older server) is skipped, never fatal, and a malformed value skips
 * only its own op. The caller persists and schedules the config reload after a successful batch —
 * this class only mutates {@link Config#DATA}.
 */
public final class SettingsApplier {

    public record Op(String key, String value) {}

    private SettingsApplier() {}

    /** @return how many ops actually applied. */
    public static int apply(List<Op> ops, boolean singleplayerActive) {
        int applied = 0;
        for (Op op : ops) {
            try {
                if (applyOne(op.key(), op.value(), singleplayerActive)) {
                    applied++;
                }
            } catch (RuntimeException e) {
                VoxyWorldGenV2.LOGGER.warn("skipping malformed settings op {}={}: {}", op.key(), op.value(), e.toString());
            }
        }
        return applied;
    }

    private static boolean applyOne(String key, String value, boolean sp) {
        if (key.startsWith("player.")) {
            return applyPlayerOp(key, value);
        }
        switch (key) {
            case "enabled" -> Config.DATA.enabled = Boolean.parseBoolean(value);
            case "radius" -> {
                int v = clampInt(value, 1, 512);
                if (sp) Config.DATA.singleplayer.generationRadius = v; else Config.DATA.generationRadius = v;
            }
            case "tasks" -> {
                int v = clampInt(value, 1, 128);
                if (sp) Config.DATA.singleplayer.maxActiveTasks = v; else Config.DATA.maxActiveTasks = v;
            }
            case "genrate" -> {
                int v = clampInt(value, 0, 100_000);
                if (sp) Config.DATA.singleplayer.maxChunksPerSecond = v; else Config.DATA.maxChunksPerSecond = v;
            }
            case "ratelimit" -> {
                double v = clampDouble(value, 0.0, 1000.0);
                if (sp) Config.DATA.singleplayer.maxMbpsPerPlayer = v; else Config.DATA.maxMbpsPerPlayer = v;
            }
            case "senddistance" -> {
                int v = clampInt(value, 0, 4096);
                if (sp) Config.DATA.singleplayer.lodSendDistanceChunks = v; else Config.DATA.lodSendDistanceChunks = v;
            }
            case "hud.compressed" -> Config.DATA.hudShowCompressed = Boolean.parseBoolean(value);
            case "hud.savings" -> Config.DATA.hudShowSavings = Boolean.parseBoolean(value);
            case "hud.clientdisk" -> Config.DATA.hudShowClientDisk = Boolean.parseBoolean(value);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** {@code player.<uuid>.<ratelimit|senddistance|reset>} — same stores the commands use. */
    private static boolean applyPlayerOp(String key, String value) {
        String[] parts = key.split("\\.");
        if (parts.length != 3) return false;
        UUID uuid = UUID.fromString(parts[1]); // malformed throws -> the op is skipped
        switch (parts[2]) {
            case "ratelimit" -> {
                rateLimitOverrides().put(uuid.toString(), clampDouble(value, 0.0, 1000.0));
            }
            case "senddistance" -> {
                sendDistanceOverrides().put(uuid.toString(), clampInt(value, 0, 4096));
            }
            case "reset" -> {
                rateLimitOverrides().remove(uuid.toString());
                sendDistanceOverrides().remove(uuid.toString());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static java.util.Map<String, Double> rateLimitOverrides() {
        if (Config.DATA.playerRateLimits == null) Config.DATA.playerRateLimits = new java.util.HashMap<>();
        return Config.DATA.playerRateLimits;
    }

    private static java.util.Map<String, Integer> sendDistanceOverrides() {
        if (Config.DATA.playerSendDistances == null) Config.DATA.playerSendDistances = new java.util.HashMap<>();
        return Config.DATA.playerSendDistances;
    }

    private static int clampInt(String value, int min, int max) {
        return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
    }

    private static double clampDouble(String value, double min, double max) {
        return Math.max(min, Math.min(max, Double.parseDouble(value.trim())));
    }
}
