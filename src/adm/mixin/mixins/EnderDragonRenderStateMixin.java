package com.adm.mixin.mixins;

import com.adm.features.impl.boss.DragonTintState;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EnderDragonRenderState.class)
public class EnderDragonRenderStateMixin implements DragonTintState {
    @Unique
    private int ec$tint;

    @Override
    public int ec$tint() {
        return ec$tint;
    }

    @Override
    public void ec$setTint(int tint) {
        ec$tint = tint;
    }
}
