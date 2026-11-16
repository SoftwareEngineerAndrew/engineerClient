package com.engineerclient.mixin;

import com.engineerclient.render.ScoreboardNametags;
import com.adm.features.impl.dungeon.Highlight;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AvatarRenderer.class)
public class NametagAvatarMixin {
    @ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("RETURN"))
    private boolean ec$showOwn(boolean shown, Avatar entity, double distanceSq) {
        if (ScoreboardNametags.INSTANCE.showsOwn(entity)) return true;
        return shown && !Highlight.hidesTeammateName(entity);
    }
}
