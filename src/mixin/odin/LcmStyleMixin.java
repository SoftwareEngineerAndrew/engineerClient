package com.engineerclient.mixin.odin;

import com.engineerclient.lcm.LcmStyle;
import com.engineerclient.mapicons.MapSizes;
import kotlin.Pair;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Odin's Dungeon Map HUD (the lambda behind its "Dungeon Map" HUD setting, which draws the background, the map
 * and the players and returns its size): with LCM Style on, {@link LcmStyle} draws all of it instead.
 * With Custom Map Padding (and no LCM Style), {@link MapSizes} draws it with its background fitted to the rooms.
 * If either can't draw a frame, Odin's own map is drawn.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.map.DungeonMap$mapHud$2", remap = false)
public class LcmStyleMixin {

    @Inject(method = "invoke(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Z)Lkotlin/Pair;", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ec$lcmStyle(GuiGraphicsExtractor g, boolean example, CallbackInfoReturnable<Pair<Integer, Integer>> cir) {
        Pair<Integer, Integer> size = LcmStyle.active() ? LcmStyle.render(g, example) : MapSizes.renderHud(g, example);
        if (size != null) cir.setReturnValue(size);
    }
}
