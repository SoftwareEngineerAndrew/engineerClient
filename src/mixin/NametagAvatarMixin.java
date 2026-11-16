package com.engineerclient.mixin;

import com.engineerclient.render.ScoreboardNametags;
import com.adm.features.impl.dungeon.Highlight;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public class NametagAvatarMixin {
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("HEAD"), cancellable = true)
    private void ec$showOwn(Avatar entity, double distanceSq, CallbackInfoReturnable<Boolean> cir) {
        if (ScoreboardNametags.INSTANCE.showsOwn(entity)) cir.setReturnValue(true);
        else if (Highlight.hidesTeammateName(entity)) cir.setReturnValue(false);
    }
}
