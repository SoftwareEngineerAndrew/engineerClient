package com.engineerclient.mixin;

import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraOffsetMixin {

    @Shadow private boolean detached;
    @Shadow private Level level;
    @Shadow private Entity entity;
    @Shadow private Vec3 position;
    @Shadow @Final private Vector3f forwards;
    @Shadow protected abstract void move(float forward, float up, float left);
    @Shadow private float getMaxZoom(float distance) { throw new AssertionError(); }

    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void ec$offset(float partialTicks, CallbackInfo ci) {
        if (detached) return;
        float offset = com.adm.features.impl.render.Camera.offsetBlocks();
        if (offset < 0f) move(-getMaxZoom(-offset), 0f, 0f);
        else if (offset > 0f) move(ec$maxForward(offset), 0f, 0f);
    }

    // Vanilla's getMaxZoom looking ahead instead of behind, so a forward offset stops short of a wall.
    private float ec$maxForward(float distance) {
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) * 2 - 1, y = (i >> 1 & 1) * 2 - 1, z = (i >> 2 & 1) * 2 - 1;
            Vec3 from = position.add(x * 0.1f, y * 0.1f, z * 0.1f);
            Vec3 to = from.add(forwards.x * distance, forwards.y * distance, forwards.z * distance);
            HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity));
            if (hit.getType() == HitResult.Type.MISS) continue;
            float d = (float) hit.getLocation().distanceToSqr(position);
            if (d < Mth.square(distance)) distance = Mth.sqrt(d);
        }
        return distance;
    }
}
