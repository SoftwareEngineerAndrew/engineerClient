package com.engineerclient.mixin.odin;

import com.engineerclient.misc.RandomStuff;
import com.engineerclient.splits.OdinSplitsLook;
import com.odtheking.odin.clickgui.settings.impl.HudElement;
import kotlin.Pair;
import kotlin.jvm.functions.Function2;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every Odin HUD element is drawn through {@code HudElement.draw}, which positions and scales it,
 * calls its render function, then records its size (for the HUD editor). Two things hook in here,
 * so they reach the elements of Odin's own modules without touching those modules:
 * <ul>
 * <li>Random Stuff's Hide Health/Mana Above %: Odin's Health HUD and Mana HUD are not drawn at all
 *     while the stat is above the threshold. The HUD editor (example = true) always draws them.</li>
 * <li>The Engineer Splits look: for Odin's two Splits HUDs, the render function call is answered by
 *     {@link OdinSplitsLook#render} instead - Odin still positions, scales and sizes the element.</li>
 * </ul>
 */
@Mixin(value = HudElement.class, remap = false)
public class HudElementMixin {

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$hideAboveThreshold(GuiGraphicsExtractor context, boolean example, CallbackInfo ci) {
        if (!example && RandomStuff.INSTANCE.hidesOdinHud((HudElement) (Object) this)) ci.cancel();
    }

    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lkotlin/jvm/functions/Function2;invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), remap = false)
    private Object ec$engineerLook(Function2<Object, Object, Object> render, Object context, Object example) {
        Pair<Integer, Integer> ours = OdinSplitsLook.render((HudElement) (Object) this, (GuiGraphicsExtractor) context, (Boolean) example);
        return ours != null ? ours : render.invoke(context, example);
    }
}
