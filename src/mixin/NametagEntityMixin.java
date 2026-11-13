package com.engineerclient.mixin;

import com.engineerclient.skyblock.NametagTweaks;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class NametagEntityMixin {
    @Shadow protected abstract Component getNameTag(Entity entity);

    @Inject(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V", at = @At("RETURN"))
    private void ec$fillOwn(Entity entity, EntityRenderState state, float partialTicks, double nameTagDistance, double belowNameDistance, CallbackInfo ci) {
        if (!NametagTweaks.INSTANCE.showsOwn(entity)) return;
        if (state.scoreText == null) state.scoreText = entity.belowNameDisplay();
        if (state.nameTag == null) state.nameTag = getNameTag(entity);
        if (state.nameTagAttachment == null) state.nameTagAttachment = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partialTicks));
    }
}
