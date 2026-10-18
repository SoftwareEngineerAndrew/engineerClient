package com.engineerclient.mixin;

import com.engineerclient.p3sim.SimServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ADS: the dungeon rooms' dispensers are empty, so a redstone pulse (a tripwire's hooks powering) makes vanilla's "dispenser fail" click (level event 1001).
 * Hypixel's does not (two in 509 recorded runs; the real arrow volleys are the traps module's), so in the sim's own world the click is dropped.
 */
@Mixin(DispenserBlock.class)
public class AdsTrapsQuietDispenserMixin {
    @Redirect(
        method = "dispenseFrom",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;levelEvent(ILnet/minecraft/core/BlockPos;I)V"),
        require = 0
    )
    private void ec$quietFail(ServerLevel level, int type, BlockPos pos, int data) {
        if (type == 1001 && SimServer.INSTANCE.isSimLevel(level)) return;
        level.levelEvent(type, pos, data);
    }
}
