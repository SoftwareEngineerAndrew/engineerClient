package com.engineerclient.mixin.odin;

import com.engineerclient.leap.LeapExtras;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Odin's leap menu drawing its four boxes (the render handler, compiled into LeapMenu$2), shrunk
 * and faded for POV Previews: each box's scale times {@link LeapExtras#getOverlayScale}, and its
 * background colour's alpha times {@link LeapExtras#getOverlayAlpha} - the head and name are drawn
 * with other calls and stay solid. Both are 1 unless the previews are up.
 *
 * Found by the handler's exact signature, so an Odin that numbers its handlers differently simply
 * leaves the boxes as they are (injectors here are optional) rather than patching the wrong one.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.features.impl.dungeon.LeapMenu$2", remap = false)
public class LeapMenuRenderMixin {

    private static final String RENDER = "invoke(Lcom/odtheking/odin/events/ScreenEvent$Render;)Ljava/lang/Object;";

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;scale(FF)Lorg/joml/Matrix3x2f;"), index = 0, remap = false)
    private float ec$scaleX(float x) {
        return x * LeapExtras.getOverlayScale();
    }

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;scale(FF)Lorg/joml/Matrix3x2f;"), index = 1, remap = false)
    private float ec$scaleY(float y) {
        return y * LeapExtras.getOverlayScale();
    }

    @ModifyArg(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/odtheking/odin/utils/render/DrawContextUtilsKt;roundedFill(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIII)V"), index = 5, remap = false)
    private int ec$fadeBackground(int argb) {
        return LeapExtras.fade(argb);
    }
}
