package com.engineerclient.mixin;

import com.engineerclient.render.Visibility;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public class RenderCleanupGlowMixin {
    @ModifyReturnValue(method = "isCurrentlyGlowing", at = @At("RETURN"))
    private boolean ec$disableGlowing(boolean glowing) {
        return glowing && !Visibility.INSTANCE.disablesGlowing();
    }
}
