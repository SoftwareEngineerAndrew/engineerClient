package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TripWireHookBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ADS: a tripwire hook that attaches, powers or lets go plays vanilla's attach / click / detach sounds. Hypixel plays none of them (not one in 500
 * recorded runs; the traps module plays the lever clicks the real hooks make), so in the sim's own world they are silent.
 * Every other world plays vanilla's sounds.
 */
@Mixin(TripWireHookBlock.class)
public class AdsTrapsQuietHookMixin {
    @Redirect(
        method = "calculateState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"),
        require = 0
    )
    private static void ec$quietHook(Level level, Entity except, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
        if (SimServer.INSTANCE.isSimLevel(level)) return;
        level.playSound(except, pos, sound, source, volume, pitch);
    }
}
