package com.ethan.voxyworldgenv2.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla hides the hold-Tab player list on a local server with one player and no scoreboard
 * objective — WITHOUT consulting the tab header/footer. That gate predates footers being used as
 * a HUD: our /voxygen log HUD lives in the footer, so in singleplayer it could never show.
 *
 * <p>On 26.2 the gate moved from {@code Gui.renderTabList} to {@code Hud.extractTabList}, but the
 * {@code isLocalServer()} call inside it is unchanged, so the redirect retargets cleanly.
 *
 * <p>This redirects the gate's {@code isLocalServer()} check to report "not local" whenever a
 * footer is present, which makes singleplayer behave exactly like multiplayer: hold Tab, see the
 * HUD. With no footer set, vanilla behavior is untouched.
 */
@Mixin(Hud.class)
public class GuiTabListMixin {

    @Redirect(method = "extractTabList",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isLocalServer()Z"))
    private boolean voxyworldgenv2$showTabListWhenFooterSet(Minecraft minecraft) {
        var footer = ((PlayerTabOverlayAccessor) ((Hud) (Object) this).getTabList())
            .voxyworldgenv2$getFooter();
        return minecraft.isLocalServer() && footer == null;
    }
}
