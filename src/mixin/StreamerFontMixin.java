package com.engineerclient.mixin;

import com.engineerclient.render.StreamerText;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Font.class)
public abstract class StreamerFontMixin {

    @Shadow public abstract int width(FormattedCharSequence text);

    @ModifyVariable(method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;", at = @At("HEAD"), argsOnly = true)
    private String ec$prepareString(String text) {
        return StreamerText.rewrite(text);
    }

    @ModifyVariable(method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;", at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence ec$prepareSeq(FormattedCharSequence text) {
        return StreamerText.rewrite(text);
    }

    @ModifyVariable(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true)
    private String ec$widthString(String text) {
        return StreamerText.rewrite(text);
    }

    @ModifyVariable(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I", at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence ec$widthSeq(FormattedCharSequence text) {
        return StreamerText.rewrite(text);
    }

    @Inject(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("HEAD"), cancellable = true)
    private void ec$widthText(FormattedText text, CallbackInfoReturnable<Integer> cir) {
        String s = text.getString();
        if (StreamerText.rewrite(s).equals(s)) return;
        cir.setReturnValue(width(Language.getInstance().getVisualOrder(text)));
    }
}
