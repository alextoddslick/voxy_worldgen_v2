package com.ethan.voxyworldgenv2.mixin;

import com.ethan.voxyworldgenv2.client.VoxyOptions;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.OptionsList;
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
 * <p>Injected at the TAIL of {@code addOptions()} — an abstract method on {@code OptionsSubScreen},
 * so the target is stable across point releases — and the rows go under their own header rather
 * than being mixed in with vanilla's, so it is obvious which settings belong to the mod.
 *
 * <p>Server rows are disabled rather than hidden when this client is not an operator. Hiding them
 * would leave a player wondering where the setting went; a greyed row says "not yours to change".
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {

    @Shadow
    protected OptionsList list;

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void voxyworldgenv2$addVoxyOptions(CallbackInfo ci) {
        if (this.list == null) return;

        this.list.addHeader(Component.translatable("config.voxyworldgenv2.title"));

        List<OptionInstance<?>> client = VoxyOptions.clientRows();
        this.list.addSmall(client.toArray(new OptionInstance<?>[0]));

        this.list.addHeader(Component.translatable("config.voxyworldgenv2.category.server"));

        List<OptionInstance<?>> server = VoxyOptions.serverRows();
        this.list.addSmall(server.toArray(new OptionInstance<?>[0]));
    }
}
