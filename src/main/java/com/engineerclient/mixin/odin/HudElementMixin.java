package com.engineerclient.mixin.odin;

import com.engineerclient.misc.RandomStuff;
import com.engineerclient.splits.OdinSplitsLook;
import com.odtheking.odin.clickgui.settings.impl.HudElement;
import kotlin.Pair;
import kotlin.jvm.functions.Function2;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every Odin HUD is drawn by {@code HudLayer.DrawnHudContent}'s draw lambda (compiled into
 * HudLayer$DrawnHudContent$2$1, holding the element and whether it is the HUD editor's example),
 * which positions it, calls its render function, then records its size. Two things hook in here,
 * so they reach the elements of Odin's own modules without touching those modules:
 * <ul>
 * <li>Random Stuff's Hide Health/Mana Above %: Odin's Health HUD and Mana HUD are not drawn at all
 *     while the stat is above the threshold. The HUD editor (example = true) always draws them.</li>
 * <li>The Engineer Splits look: for Odin's two Splits HUDs, the render function call is answered by
 *     {@link OdinSplitsLook#render} instead - Odin still positions, scales and sizes the element.</li>
 * </ul>
 * Before Odin 0.3.6 this was HudElement.draw. Found by the lambda's exact signature, so an Odin that
 * numbers its lambdas differently leaves the HUDs as they are (injectors here are optional).
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.clickgui.HudLayer$DrawnHudContent$2$1", remap = false)
public class HudElementMixin {

    private static final String DRAW = "invoke(Lcom/odtheking/odin/utils/ui/compose/UiNode;Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V";

    @Shadow(remap = false) @Final HudElement $hud;
    @Shadow(remap = false) @Final boolean $example;

    @Inject(method = DRAW, at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$hideAboveThreshold(CallbackInfo ci) {
        if (!$example && RandomStuff.INSTANCE.hidesOdinHud($hud)) ci.cancel();
    }

    @Redirect(method = DRAW, at = @At(value = "INVOKE", target = "Lkotlin/jvm/functions/Function2;invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), remap = false)
    private Object ec$engineerLook(Function2<Object, Object, Object> render, Object context, Object example) {
        Pair<Integer, Integer> ours = OdinSplitsLook.render($hud, (GuiGraphicsExtractor) context, (Boolean) example);
        return ours != null ? ours : render.invoke(context, example);
    }
}
