package com.engineerclient.mixin;

import com.engineerclient.render.ScoreboardNametags;
import net.minecraft.client.renderer.SubmitNodeCollection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(SubmitNodeCollection.class)
public class NametagStyleMixin {
    @ModifyArg(method = "nameTag", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Content$Text;<init>(FFLnet/minecraft/util/FormattedCharSequence;ZIII)V"), index = 3)
    private static boolean ec$tagShadow(boolean shadow) {
        return shadow || ScoreboardNametags.tagShadow();
    }

    @ModifyArg(method = "nameTag", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Content$Text;<init>(FFLnet/minecraft/util/FormattedCharSequence;ZIII)V"), index = 5)
    private static int ec$tagBackground(int color) {
        return ScoreboardNametags.tagBackdrop() ? 0 : color;
    }
}
