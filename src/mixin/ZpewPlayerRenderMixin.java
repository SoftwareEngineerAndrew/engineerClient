package com.engineerclient.mixin;

import com.engineerclient.misc.Zpew;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Legit Zpew: your own model is drawn where the camera is, and never culled from its old spot. */
@Mixin(EntityRenderer.class)
public abstract class ZpewPlayerRenderMixin {

    @Inject(method = "extractRenderState", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;z:D", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void ec$zpewModel(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player) return;
        Vec3 pos = Zpew.cameraPos();
        if (pos == null) return;
        state.x = pos.x;
        state.y = pos.y;
        state.z = pos.z;
    }

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void ec$zpewShown(Entity entity, Frustum frustum, double x, double y, double z, float partialTick, CallbackInfoReturnable<Boolean> cir) {
        if (entity == Minecraft.getInstance().player && Zpew.cameraPos() != null) cir.setReturnValue(true);
    }
}
