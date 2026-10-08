package com.coffeeclient.mixin;

import com.coffeeclient.misc.Termsim;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Set;

/**
 * Termsim's No Rotate (devoniansolo's ClientPacketListenerMixin, MC 26.2): while {@link Termsim#noRotateActive()}, a
 * teleport's position is taken but not its rotation - the camera stays put - and the PosRot sent
 * back after accepting it carries the server's rotation, as if it had been taken.
 */
@Mixin(ClientPacketListener.class)
public abstract class NoRotateMixin {
    @Unique private static boolean cc$teleporting = false;
    @Unique private static PositionMoveRotation cc$serverRotation = null;
    @Unique private static boolean cc$sent = false;

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;setValuesFromPositionPacket(Lnet/minecraft/world/entity/PositionMoveRotation;Ljava/util/Set;Lnet/minecraft/world/entity/Entity;Z)Z"))
    private void cc$beforeTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (Termsim.noRotateActive()) cc$teleporting = true;
    }

    @Inject(method = "setValuesFromPositionPacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"), locals = LocalCapture.CAPTURE_FAILSOFT)
    private static void cc$serverValues(PositionMoveRotation change, Set<Relative> relatives, Entity entity, boolean interpolate, CallbackInfoReturnable<Boolean> cir, PositionMoveRotation currentValues, PositionMoveRotation newValues) {
        if (!cc$teleporting || !Termsim.noRotateActive()) return;
        cc$serverRotation = newValues;
        cc$teleporting = false;
    }

    @WrapOperation(method = "setValuesFromPositionPacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setYRot(F)V"))
    private static void cc$keepYaw(Entity entity, float yRot, Operation<Void> original) {
        if (cc$serverRotation == null || !Termsim.noRotateActive()) original.call(entity, yRot);
    }

    @WrapOperation(method = "setValuesFromPositionPacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setXRot(F)V"))
    private static void cc$keepPitch(Entity entity, float xRot, Operation<Void> original) {
        if (cc$serverRotation == null || !Termsim.noRotateActive()) original.call(entity, xRot);
    }

    @WrapOperation(method = "setValuesFromPositionPacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setOldPosAndRot(Lnet/minecraft/world/phys/Vec3;FF)V"))
    private static void cc$keepOldRotation(Entity entity, Vec3 pos, float yRot, float xRot, Operation<Void> original) {
        var player = Minecraft.getInstance().player;
        if (cc$serverRotation == null || !Termsim.noRotateActive() || player == null) {
            original.call(entity, pos, yRot, xRot);
            return;
        }
        original.call(entity, pos, player.yRotO, player.xRotO);
    }

    /** The PosRot after accepting the teleport (handleMovePlayer's 2nd send): the server's rotation, not ours. */
    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V", ordinal = 1))
    private void cc$confirmServerRotation(Connection connection, Packet<?> packet, Operation<Void> original) {
        var player = Minecraft.getInstance().player;
        if (player == null || cc$serverRotation == null || !Termsim.noRotateActive()) {
            original.call(connection, packet);
            return;
        }
        original.call(connection, new ServerboundMovePlayerPacket.PosRot(player.getX(), player.getY(), player.getZ(),
            cc$serverRotation.yRot(), cc$serverRotation.xRot(), false, false));
        cc$sent = true;
    }

    /** The client's "last sent" rotation is the one the server now has, so the next move packet sends ours again. */
    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void cc$afterTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (!cc$sent || cc$serverRotation == null || !Termsim.noRotateActive()) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        ((LocalPlayerRotationAccessor) player).cc$setXRotLast(cc$serverRotation.xRot());
        ((LocalPlayerRotationAccessor) player).cc$setYRotLast(cc$serverRotation.yRot());
        cc$sent = false;
        cc$serverRotation = null;
    }
}
