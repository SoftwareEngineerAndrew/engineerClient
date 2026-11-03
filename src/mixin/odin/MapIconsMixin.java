package com.engineerclient.mixin.odin;

import com.engineerclient.mapicons.MapIcons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Odin's Dungeon Map drawing an icon (renderIcon: the room check marks and the guess tiles' "?"): skipped when
 * {@link MapIcons} has that kind turned off. renderIcon takes an inline-class IVec2, so its JVM name is mangled
 * (renderIcon-xaMhWKE); Mixin rejects a '-' in a plain selector ("Invalid name"), so it's matched by regex.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.map.MapRendererKt", remap = false)
public class MapIconsMixin {

    @Inject(method = "/^renderIcon-/", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ec$hideIcon(GuiGraphicsExtractor g, long pos, Identifier id, CallbackInfo ci) {
        if (!MapIcons.shows(id)) ci.cancel();
    }
}
