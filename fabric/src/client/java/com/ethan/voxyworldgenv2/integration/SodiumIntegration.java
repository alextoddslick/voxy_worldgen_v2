package com.ethan.voxyworldgenv2.integration;

import com.ethan.voxyworldgenv2.core.Config;
import com.ethan.voxyworldgenv2.network.ServerConfigGate;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Adds the mod's settings to Sodium's video settings screen.
 *
 * <p>Sodium ships its own {@code VideoSettingsScreen} and replaces vanilla's outright, so in any
 * pack containing Sodium the vanilla screen — and therefore {@link
 * com.ethan.voxyworldgenv2.mixin.OptionsSubScreenMixin} — is simply never reached. This is the path
 * that actually runs for players; the vanilla mixin remains as the fallback for Sodium-less setups.
 *
 * <p>Registered through the {@code sodium:config_api_user} entrypoint, so the class is only loaded
 * when Sodium is present. Nothing here is referenced from common code.
 */
public class SodiumIntegration implements ConfigEntryPoint {

    private static Identifier id(String path) {
        return Identifier.parse("voxyworldgenv2:" + path);
    }

    /** True when a remote server owns the server-side values. */
    private static boolean onRemoteServer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getCurrentServer() != null && !mc.isLocalServer();
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        OptionGroupBuilder display = builder.createOptionGroup()
            .setName(Component.translatable("config.voxyworldgenv2.category.client"))
            .addOption(builder.createBooleanOption(id("f3_stats"))
                .setName(Component.translatable("voxyworldgenv2.option.f3_stats"))
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.f3_stats.tooltip"))
                .setBinding(v -> { Config.DATA.showF3MenuStats = v; Config.save(); },
                            () -> Config.DATA.showF3MenuStats)
                .setDefaultValue(true))
            .addOption(builder.createBooleanOption(id("hud_compressed"))
                .setName(Component.translatable("voxyworldgenv2.option.hud_compressed"))
                .setBinding(v -> { Config.DATA.hudShowCompressed = v; Config.save(); },
                            () -> Config.DATA.hudShowCompressed)
                .setDefaultValue(true))
            .addOption(builder.createBooleanOption(id("hud_savings"))
                .setName(Component.translatable("voxyworldgenv2.option.hud_savings"))
                .setBinding(v -> { Config.DATA.hudShowSavings = v; Config.save(); },
                            () -> Config.DATA.hudShowSavings)
                .setDefaultValue(true))
            .addOption(builder.createBooleanOption(id("hud_client_disk"))
                .setName(Component.translatable("voxyworldgenv2.option.hud_client_disk"))
                .setBinding(v -> { Config.DATA.hudShowClientDisk = v; Config.save(); },
                            () -> Config.DATA.hudShowClientDisk)
                .setDefaultValue(true));

        // Server values. Disabled rather than hidden for a non-operator: a greyed row says "not
        // yours to change", where a missing row reads as the setting having disappeared.
        boolean editable = ServerConfigGate.mayEdit(onRemoteServer());

        OptionGroupBuilder server = builder.createOptionGroup()
            .setName(Component.translatable("config.voxyworldgenv2.category.server"))
            .addOption(builder.createBooleanOption(id("gen_enabled"))
                .setName(Component.translatable("voxyworldgenv2.option.gen_enabled"))
                .setBinding(v -> push(s -> new Config.ServerConfig(
                        v, s.generationRadius(), s.updateInterval(), s.maxQueueSize(), s.maxActiveTasks())),
                    () -> ServerConfigGate.current().enabled())
                .setDefaultValue(true)
                .setEnabled(editable))
            // Ranges match Config.applyServerConfig's clamps exactly; a slider offering a value the
            // server then clamps looks like the setting silently reverted.
            .addOption(builder.createIntegerOption(id("gen_radius"))
                .setRange(1, 512, 1)
                .setName(Component.translatable("voxyworldgenv2.option.gen_radius"))
                .setBinding(v -> push(s -> new Config.ServerConfig(
                        s.enabled(), v, s.updateInterval(), s.maxQueueSize(), s.maxActiveTasks())),
                    () -> ServerConfigGate.current().generationRadius())
                .setDefaultValue(64)
                .setEnabled(editable))
            .addOption(builder.createIntegerOption(id("max_tasks"))
                .setRange(1, 128, 1)
                .setName(Component.translatable("voxyworldgenv2.option.max_tasks"))
                .setBinding(v -> push(s -> new Config.ServerConfig(
                        s.enabled(), s.generationRadius(), s.updateInterval(), s.maxQueueSize(), v)),
                    () -> ServerConfigGate.current().maxActiveTasks())
                .setDefaultValue(20)
                .setEnabled(editable));

        OptionPageBuilder page = builder.createOptionPage()
            .setName(Component.translatable("config.voxyworldgenv2.title"))
            .addOptionGroup(display)
            .addOptionGroup(server);

        builder.registerOwnModOptions()
            .setName("Voxy World Gen V2")
            .addPage(page);
    }

    private static void push(java.util.function.UnaryOperator<Config.ServerConfig> edit) {
        boolean remote = onRemoteServer();
        if (!ServerConfigGate.mayEdit(remote)) return;
        ServerConfigGate.apply(edit.apply(ServerConfigGate.current()), remote);
    }
}
