package com.engineerclient.mixin;

import com.engineerclient.render.StreamerText;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public abstract class StreamerFontMixin {

    @Shadow public abstract int width(FormattedCharSequence text);

    @ModifyVariable(method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;", at = @At("HEAD"), argsOnly = true)
    private String ec$prepareString(String text) {
        if (!StreamerText.active) return text;
        return StreamerText.rewrite(text);
    }

    @ModifyVariable(method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;", at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence ec$prepareSeq(FormattedCharSequence text) {
        if (!StreamerText.active) return text;
        return StreamerText.rewrite(text);
    }

    @ModifyVariable(method = "width(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true)
    private String ec$widthString(String text) {
        if (!StreamerText.active) return text;
        return StreamerText.rewrite(text);
    }

    @ModifyVariable(method = "width(Lnet/minecraft/util/FormattedCharSequence;)I", at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence ec$widthSeq(FormattedCharSequence text) {
        if (!StreamerText.active) return text;
        return StreamerText.rewrite(text);
    }

    @ModifyReturnValue(method = "width(Lnet/minecraft/network/chat/FormattedText;)I", at = @At("RETURN"))
    private int ec$widthText(int width, FormattedText text) {
        if (!StreamerText.active || !StreamerText.changes(text)) return width;
        return width(Language.getInstance().getVisualOrder(text));
    }
}
