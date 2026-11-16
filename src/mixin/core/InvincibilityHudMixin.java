package com.engineerclient.mixin.core;

import com.engineerclient.misc.OdinMasksUsed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Odin's Invincibility Timer HUD (InvincibilityTimer$hud$2) reads Show Spirit / Bonzo / Phoenix
 * to pick what to draw; with Only Used on, an item not used this run reads as off ({@link OdinMasksUsed}).
 */
@Pseudo
@Mixin(targets = "com.adm.features.impl.dungeon.InvincibilityTimer$hud$2", remap = false)
public class InvincibilityHudMixin {
    private static final String OWNER = "Lcom/adm/features/impl/dungeon/InvincibilityTimer;";
    private static final String METHOD = "invoke(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Z)Lkotlin/Pair;";

    @Redirect(method = METHOD, at = @At(value = "INVOKE", target = OWNER + "access$getShowSpirit(" + OWNER + ")Z"), remap = false)
    private boolean ec$spirit(com.adm.features.impl.dungeon.InvincibilityTimer t) {
        return OdinMasksUsed.shows(0, ec$get(t, "Show Spirit Mask"));
    }

    @Redirect(method = METHOD, at = @At(value = "INVOKE", target = OWNER + "access$getShowBonzo(" + OWNER + ")Z"), remap = false)
    private boolean ec$bonzo(com.adm.features.impl.dungeon.InvincibilityTimer t) {
        return OdinMasksUsed.shows(1, ec$get(t, "Show Bonzo Mask"));
    }

    @Redirect(method = METHOD, at = @At(value = "INVOKE", target = OWNER + "access$getShowPhoenix(" + OWNER + ")Z"), remap = false)
    private boolean ec$phoenix(com.adm.features.impl.dungeon.InvincibilityTimer t) {
        return OdinMasksUsed.shows(2, ec$get(t, "Show Phoenix Pet"));
    }

    private static boolean ec$get(com.adm.features.impl.dungeon.InvincibilityTimer t, String name) {
        return t.getSettings().get(name) instanceof com.adm.clickgui.settings.impl.BooleanSetting b ? b.getValue() : true;
    }
}
