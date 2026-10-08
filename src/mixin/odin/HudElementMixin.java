package com.engineerclient.mixin.odin;

import com.engineerclient.OdinHuds;
import com.odtheking.odin.clickgui.settings.impl.HUDSetting;
import com.odtheking.odin.features.Module;
import kotlin.Pair;
import kotlin.jvm.functions.Function2;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every Odin HUD element is made by {@code Module.HUD} from a draw function, which Odin's Compose
 * HUD layer calls to draw it and size it. That function is swapped for {@link OdinHuds}' wrapper
 * as the HUD is made, so Random Stuff's Hide Health/Mana Above % and the Engineer Splits look reach
 * the elements of Odin's own modules without touching those modules.
 */
@Mixin(value = Module.class, remap = false)
public class HudElementMixin {

    @ModifyVariable(method = "HUD", at = @At("HEAD"), argsOnly = true, remap = false)
    private Function2<GuiGraphicsExtractor, Boolean, Pair<Integer, Integer>> ec$wrap(Function2<GuiGraphicsExtractor, Boolean, Pair<Integer, Integer>> block) {
        return OdinHuds.wrap(block);
    }

    @Inject(method = "HUD", at = @At("RETURN"), remap = false)
    private void ec$made(CallbackInfoReturnable<HUDSetting> cir) {
        OdinHuds.made(cir.getReturnValue());
    }
}
