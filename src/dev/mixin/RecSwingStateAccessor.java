package com.devgineerclient.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A swing's progress: ticks since it started and the animation fraction (what swingTime and
 * attackAnim were up to 26.2), for the Dungeon Recorder's me line (PlayerState).
 */
@Mixin(LivingEntity.SwingState.class)
public interface RecSwingStateAccessor {

    @Accessor("ticks")
    int dc$recTicks();

    @Accessor("animation")
    float dc$recAnimation();
}
