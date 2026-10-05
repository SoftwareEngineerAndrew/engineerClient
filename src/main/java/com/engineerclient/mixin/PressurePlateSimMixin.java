package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * P3 Sim: S4's target plate (63, 127, 35) clicks silently, as on Hypixel (no pressure-plate sound in the recordings).
 * Only that plate in the sim's own world; every other plate, and every other world, plays vanilla's click.
 */
@Mixin(BasePressurePlateBlock.class)
public class PressurePlateSimMixin {
    private static final BlockPos EC$S4_PLATE = new BlockPos(63, 127, 35);

    @Redirect(
        method = "checkPressed",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;)V"),
        require = 0
    )
    private void ec$silentS4Plate(Level level, Entity except, BlockPos pos, SoundEvent sound, SoundSource source) {
        if (EC$S4_PLATE.equals(pos) && SimServer.INSTANCE.isSimLevel(level)) return;
        level.playSound(except, pos, sound, source);
    }
}
