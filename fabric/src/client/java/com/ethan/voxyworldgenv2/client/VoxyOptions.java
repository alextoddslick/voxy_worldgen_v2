package com.ethan.voxyworldgenv2.client;

import com.ethan.voxyworldgenv2.core.Config;
import com.ethan.voxyworldgenv2.network.ServerConfigGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod's rows for the vanilla Video Settings screen.
 *
 * <p>Two kinds of row live here and they behave differently on purpose. Client rows are personal
 * display preferences and write straight to the local config. Server rows are shared state: they
 * read whatever the server pushed, they are disabled outright for a non-operator, and applying one
 * goes through {@link ServerConfigGate} so this screen and the ModMenu screen cannot drift.
 */
public final class VoxyOptions {

    private VoxyOptions() {}

    /** True when a remote server owns the server-side values. */
    private static boolean onRemoteServer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getCurrentServer() != null && !mc.isLocalServer();
    }

    public static List<OptionInstance<?>> clientRows() {
        List<OptionInstance<?>> out = new ArrayList<>();
        out.add(bool("voxyworldgenv2.option.f3_stats", Config.DATA.showF3MenuStats,
            v -> Config.DATA.showF3MenuStats = v));
        out.add(bool("voxyworldgenv2.option.hud_compressed", Config.DATA.hudShowCompressed,
            v -> Config.DATA.hudShowCompressed = v));
        out.add(bool("voxyworldgenv2.option.hud_savings", Config.DATA.hudShowSavings,
            v -> Config.DATA.hudShowSavings = v));
        out.add(bool("voxyworldgenv2.option.hud_client_disk", Config.DATA.hudShowClientDisk,
            v -> Config.DATA.hudShowClientDisk = v));
        return out;
    }

    public static List<OptionInstance<?>> serverRows() {
        List<OptionInstance<?>> out = new ArrayList<>();
        Config.ServerConfig c = ServerConfigGate.current();

        out.add(new OptionInstance<>(
            "voxyworldgenv2.option.gen_enabled",
            OptionInstance.noTooltip(),
            (caption, v) -> v ? Component.translatable("options.on") : Component.translatable("options.off"),
            OptionInstance.BOOLEAN_VALUES,
            c.enabled(),
            v -> pushWith(s -> new Config.ServerConfig(v, s.generationRadius(), s.updateInterval(), s.maxQueueSize(), s.maxActiveTasks()))));

        // Bounds match Config.applyServerConfig's clamps exactly. A slider that offers a value the
        // server then clamps looks like the setting silently reverted.
        out.add(intSlider("voxyworldgenv2.option.gen_radius", c.generationRadius(), 1, 512,
            v -> pushWith(s -> new Config.ServerConfig(s.enabled(), v, s.updateInterval(), s.maxQueueSize(), s.maxActiveTasks()))));

        out.add(intSlider("voxyworldgenv2.option.max_tasks", c.maxActiveTasks(), 1, 128,
            v -> pushWith(s -> new Config.ServerConfig(s.enabled(), s.generationRadius(), s.updateInterval(), s.maxQueueSize(), v))));

        return out;
    }

    /** True when the server rows should be interactive at all. */
    public static boolean serverRowsEditable() {
        return ServerConfigGate.mayEdit(onRemoteServer());
    }

    private static void pushWith(java.util.function.UnaryOperator<Config.ServerConfig> edit) {
        boolean remote = onRemoteServer();
        if (!ServerConfigGate.mayEdit(remote)) return;
        ServerConfigGate.apply(edit.apply(ServerConfigGate.current()), remote);
    }

    private static OptionInstance<Boolean> bool(String key, boolean initial,
                                                java.util.function.Consumer<Boolean> onSet) {
        return OptionInstance.createBoolean(key, initial, v -> {
            onSet.accept(v);
            Config.save();
        });
    }

    private static OptionInstance<Integer> intSlider(String key, int initial, int min, int max,
                                                     java.util.function.Consumer<Integer> onSet) {
        return new OptionInstance<>(
            key,
            OptionInstance.noTooltip(),
            (caption, v) -> Component.translatable(key).append(": " + v),
            new OptionInstance.IntRange(min, max),
            initial,
            onSet::accept);
    }
}
