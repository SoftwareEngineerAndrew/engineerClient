package com.devgineerclient.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The arm swing (26.3 moved it out of LivingEntity's public fields into a private SwingState), for
 * the Dungeon Recorder's me line (PlayerState). Its timers come from RecSwingStateAccessor.
 */
@Mixin(LivingEntity.class)
public interface RecLivingEntityAccessor {

    @Accessor("swingState")
    LivingEntity.SwingState dc$recSwingState();
}
