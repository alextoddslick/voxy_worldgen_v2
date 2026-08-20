package com.ethan.voxyworldgenv2.mixin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the one mixin mistake that compiles cleanly and only fails at runtime.
 *
 * <p>Mixin resolves {@code @Shadow} against the TARGET class itself, not its superclasses. Shadowing
 * an inherited member throws {@code InvalidMixinException: @Shadow field ... was not located in the
 * target class}, and because this mod sets {@code injectors.defaultRequire = 1} that is a hard
 * MixinApplyError. Mixins also apply lazily, so nothing is logged until the target class first
 * loads — for a settings screen, that is the moment the player opens the menu. The observed symptom
 * is an Options button that silently does nothing, with a clean startup log.
 *
 * <p>These assertions are cheap and catch a Minecraft update moving the members out from under us.
 */
class MixinTargetsTest {

    /** OptionsSubScreenMixin shadows `list` and injects into `addContents`; both must be declared here. */
    @Test
    void optionsSubScreenDeclaresTheMembersWeShadowAndInject() throws Exception {
        Class<?> target = Class.forName("net.minecraft.client.gui.screens.options.OptionsSubScreen");

        Field list = target.getDeclaredField("list");
        assertEquals("net.minecraft.client.gui.components.OptionsList", list.getType().getName());

        Method addContents = target.getDeclaredMethod("addContents");
        assertNotNull(addContents, "injection target must be declared on the mixin target");
    }

    /**
     * The reason the mixin targets the superclass. If a future version moves `list` down onto
     * VideoSettingsScreen this fails, and the mixin should be retargeted rather than left guessing.
     */
    @Test
    void videoSettingsScreenDoesNotDeclareListItself() throws Exception {
        Class<?> video = Class.forName("net.minecraft.client.gui.screens.options.VideoSettingsScreen");
        assertThrows(NoSuchFieldException.class, () -> video.getDeclaredField("list"),
            "if this ever passes, `list` moved and OptionsSubScreenMixin can target the video screen directly");
    }
}
