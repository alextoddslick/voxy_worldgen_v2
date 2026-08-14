package com.ethan.voxyworldgenv2.integration;

import com.ethan.voxyworldgenv2.core.Config;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.network.chat.Component;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.voxyworldgenv2.title"));
            
            ConfigEntryBuilder entryBuilder = builder.entryBuilder();
            ConfigCategory general = builder.getOrCreateCategory(Component.translatable("config.voxyworldgenv2.category.general"));
            
            general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.voxyworldgenv2.option.enabled"), Config.DATA.enabled)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.enabled.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.enabled = newValue)
                .build());

            general.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.voxyworldgenv2.option.f3_stats"), Config.DATA.showF3MenuStats)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.f3_stats.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.showF3MenuStats = newValue)
                .build());

            general.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.radius"), Config.DATA.generationRadius, 1, 512)
                .setDefaultValue(64)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.radius.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.generationRadius = newValue)
                .build());
            
            general.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.update_interval"), Config.DATA.update_interval, 1, 200)
                .setDefaultValue(20)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.update_interval.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.update_interval = newValue)
                .build());
                
            general.addEntry(entryBuilder.startIntField(Component.translatable("config.voxyworldgenv2.option.max_queue"), Config.DATA.maxQueueSize)
                .setDefaultValue(20000)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.max_queue.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.maxQueueSize = newValue)
                .build());
                
            general.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.max_active"), Config.DATA.maxActiveTasks, 1, 128)
                .setDefaultValue(20)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.max_active.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.maxActiveTasks = newValue)
                .build());

            general.addEntry(entryBuilder.startIntField(Component.translatable("config.voxyworldgenv2.option.gen_rate"), Config.DATA.maxChunksPerSecond)
                .setDefaultValue(0)
                .setMin(0)
                .setMax(100_000)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.gen_rate.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.maxChunksPerSecond = newValue)
                .build());

            // Voxy itself exposes no API for third-party settings (its page is a hardcoded Sodium
            // OptionPage), so the singleplayer profile lives here, next to everything else.
            if (Config.DATA.singleplayer == null) {
                Config.DATA.singleplayer = new Config.SingleplayerConfig();
            }
            var sp = Config.DATA.singleplayer;
            ConfigCategory singleplayer = builder.getOrCreateCategory(Component.translatable("config.voxyworldgenv2.category.singleplayer"));

            singleplayer.addEntry(entryBuilder.startBooleanToggle(Component.translatable("config.voxyworldgenv2.option.sp_enabled"), sp.enableSingleplayerDefaults)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.sp_enabled.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.singleplayer.enableSingleplayerDefaults = newValue)
                .build());

            singleplayer.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.sp_radius"), sp.generationRadius, 0, 512)
                .setDefaultValue(0)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.sp_radius.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.singleplayer.generationRadius = newValue)
                .build());

            singleplayer.addEntry(entryBuilder.startIntSlider(Component.translatable("config.voxyworldgenv2.option.sp_tasks"), sp.maxActiveTasks, 0, 128)
                .setDefaultValue(0)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.sp_tasks.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.singleplayer.maxActiveTasks = newValue)
                .build());

            singleplayer.addEntry(entryBuilder.startDoubleField(Component.translatable("config.voxyworldgenv2.option.sp_rate"), sp.maxMbpsPerPlayer)
                .setDefaultValue(0.0)
                .setMin(0.0)
                .setMax(1000.0)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.sp_rate.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.singleplayer.maxMbpsPerPlayer = newValue)
                .build());

            singleplayer.addEntry(entryBuilder.startIntField(Component.translatable("config.voxyworldgenv2.option.sp_gen_rate"), sp.maxChunksPerSecond)
                .setDefaultValue(0)
                .setMin(0)
                .setMax(100_000)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.sp_gen_rate.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.singleplayer.maxChunksPerSecond = newValue)
                .build());

            singleplayer.addEntry(entryBuilder.startIntField(Component.translatable("config.voxyworldgenv2.option.sp_pause"), sp.dimensionChangePauseSeconds)
                .setDefaultValue(0)
                .setMin(0)
                .setMax(3600)
                .setTooltip(Component.translatable("config.voxyworldgenv2.option.sp_pause.tooltip"))
                .setSaveConsumer(newValue -> Config.DATA.singleplayer.dimensionChangePauseSeconds = newValue)
                .build());

            builder.setSavingRunnable(() -> {
                Config.save();
                com.ethan.voxyworldgenv2.core.ChunkGenerationManager.getInstance().scheduleConfigReload();
            });
            
            return builder.build();
        };
    }
}
