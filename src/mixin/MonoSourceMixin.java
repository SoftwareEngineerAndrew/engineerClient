package com.engineerclient.mixin;

import com.engineerclient.visual.Sounds;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Channel.class)
public abstract class MonoSourceMixin implements Sounds.MonoSource {
    @Shadow @Final private int source;
    @Unique private Vec3 ec$pos = Vec3.ZERO;
    @Unique private boolean ec$relative;

    @WrapOperation(method = "setSelfPosition", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alSourcefv(II[F)V"))
    private void ec$place(int src, int param, float[] xyz, Operation<Void> original, @Local(argsOnly = true) Vec3 pos) {
        ec$pos = pos;
        if (Sounds.monoActive()) Sounds.centre(source, pos, ec$relative);
        else original.call(src, param, xyz);
    }

    @WrapOperation(method = "setRelative", at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/AL10;alSourcei(III)V"))
    private void ec$relative(int src, int param, int value, Operation<Void> original) {
        ec$relative = value != 0;
        if (!Sounds.monoActive()) {
            original.call(src, param, value);
            return;
        }
        original.call(src, param, AL10.AL_TRUE);
        Sounds.centre(source, ec$pos, ec$relative);
    }

    @Override
    public void ecRecentre() {
        if (Sounds.monoActive()) Sounds.centre(source, ec$pos, ec$relative);
    }
}
