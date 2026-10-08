package com.devgineerclient.mixin;

import com.devgineerclient.recorder.EntityCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Dungeon Recorder: the moves the packet handlers apply without going through
 * Entity.moveOrInterpolateTo (EntityMoveTapMixin), so the emove stream misses none. A position sync
 * snaps (Entity.snapTo) an entity that is not ticking or jumps more than 64 blocks; a teleport that
 * does not interpolate sets the position and rotation directly (setValuesFromPositionPacket). Each
 * is written as an emove row after the move, with interp 0 and the snap flag.
 */
@Mixin(ClientPacketListener.class)
public class EntitySnapTapMixin {

    @Inject(
        method = "handleEntityPositionSync",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;snapTo(Lnet/minecraft/world/phys/Vec3;FF)V", shift = At.Shift.AFTER),
        require = 0,
        expect = 0
    )
    private void dc$recSyncSnap(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        if (!EntityCapture.movesOn) return;
        // The recorder must never break the game: whatever it hits stays here.
        try {
            ClientLevel level = Minecraft.getInstance().level;
            Entity e = level == null ? null : level.getEntity(packet.id());
            if (e != null) EntityCapture.onSnap(e);
        } catch (Throwable ignored) {
        }
    }

    @Inject(
        method = "setValuesFromPositionPacket",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setXRot(F)V", shift = At.Shift.AFTER),
        require = 0,
        expect = 0
    )
    private static void dc$recTeleportSet(PositionMoveRotation values, Set<Relative> relatives, Entity e, boolean interpolate, CallbackInfoReturnable<Boolean> cir) {
        if (!EntityCapture.movesOn) return;
        try {
            EntityCapture.onSnap(e);
        } catch (Throwable ignored) {
        }
    }
}
