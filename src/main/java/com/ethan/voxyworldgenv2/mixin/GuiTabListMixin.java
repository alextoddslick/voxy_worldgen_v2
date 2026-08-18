package com.ethan.voxyworldgenv2.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla hides the hold-Tab player list on a local server with one player and no scoreboard
 * objective — WITHOUT consulting the tab header/footer. That gate predates footers being used as
 * a HUD: our /voxygen log HUD lives in the footer, so in singleplayer it could never show.
 *
 * <p>This redirects the gate's {@code isLocalServer()} check to report "not local" whenever a
 * footer is present, which makes singleplayer behave exactly like multiplayer: hold Tab, see the
 * HUD. With no footer set, vanilla behavior is untouched.
 */
@Mixin(Gui.class)
public class GuiTabListMixin {

    @Redirect(method = "renderTabList",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isLocalServer()Z"))
    private boolean voxyworldgenv2$showTabListWhenFooterSet(Minecraft minecraft) {
        var footer = ((PlayerTabOverlayAccessor) ((Gui) (Object) this).getTabList())
            .voxyworldgenv2$getFooter();
        return minecraft.isLocalServer() && footer == null;
    }
}
