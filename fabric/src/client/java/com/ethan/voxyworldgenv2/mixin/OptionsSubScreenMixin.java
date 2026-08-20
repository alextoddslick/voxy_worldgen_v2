package com.ethan.voxyworldgenv2.mixin;

import com.ethan.voxyworldgenv2.client.VoxyOptions;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Appends the mod's settings to vanilla's Video Settings screen.
 *
 * <p>Targets {@link OptionsSubScreen} rather than {@code VideoSettingsScreen}, and filters to the
 * video screen at runtime. That is not a stylistic choice: {@code list} and {@code addContents} are
 * both declared on the superclass, and Mixin resolves {@code @Shadow} against the target class
 * itself, so shadowing them from a {@code VideoSettingsScreen} mixin throws
 * {@code InvalidMixinException: @Shadow field list was not located in the target class}. With
 * {@code injectors.defaultRequire = 1} that is a hard MixinApplyError, and because mixins apply
 * lazily the game stays silent until the moment the screen is opened — presenting as an Options
 * button that simply does nothing.
 *
 * <p>{@code addOptions} is abstract here, so the injection goes into {@code addContents}, which is
 * concrete and runs after vanilla has populated the list.
 */
@Mixin(OptionsSubScreen.class)
public abstract class OptionsSubScreenMixin {

    @Shadow
    protected OptionsList list;

    @Inject(method = "addContents", at = @At("TAIL"))
    private void voxyworldgenv2$addVoxyOptions(CallbackInfo ci) {
        // Only the video screen; every other options subscreen shares this superclass.
        if (!((Object) this instanceof VideoSettingsScreen)) return;
        if (this.list == null) return;
        com.ethan.voxyworldgenv2.VoxyWorldGenV2.LOGGER.info(
            "[voxy-settings] vanilla VideoSettingsScreen reached: adding rows");

        this.list.addHeader(Component.translatable("config.voxyworldgenv2.title"));
        List<OptionInstance<?>> client = VoxyOptions.clientRows();
        this.list.addSmall(client.toArray(new OptionInstance<?>[0]));

        this.list.addHeader(Component.translatable("config.voxyworldgenv2.category.server"));
        List<OptionInstance<?>> server = VoxyOptions.serverRows();
        this.list.addSmall(server.toArray(new OptionInstance<?>[0]));
    }
}
