package com.engineerclient.mixin;

import com.engineerclient.misc.DropAnimation;
import com.engineerclient.skyblock.ItemProtection;
import com.engineerclient.waypoints.BrWaypoints2;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link BrWaypoints2}'s wand be used by dropping it: with Edit Mode on, pressing drop while
 * holding the wand places a box instead, and the drop is cancelled here before anything reaches
 * the server — the item never leaves your hand.
 */
// 26.3 moved the drop key's LocalPlayer.drop into MultiPlayerGameMode.dropItem.
@Mixin(MultiPlayerGameMode.class)
public class LocalPlayerDropMixin {

    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void ec$wandDrop(LocalPlayer player, boolean fullStack, CallbackInfo ci) {
        if (BrWaypoints2.onDrop() || ItemProtection.blocksDrop(player)) ci.cancel();
    }

    /** Remembers the stack that was in hand when the drop packet goes out, for {@link DropAnimation}. */
    @Inject(method = "dropItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void ec$rememberDrop(LocalPlayer player, boolean fullStack, CallbackInfo ci, @Local(name = "prediction") ItemStack prediction) {
        if (!DropAnimation.shouldProceed()) return;
        DropAnimation.setDroppedData(prediction, player.getInventory().getSelectedSlot());
    }

    /** No Drop Animation: no arm swing on a drop (the swing is only drawn, never sent). */
    @WrapOperation(method = "dropItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z"))
    private boolean ec$noDropSwing(LocalPlayer player, InteractionHand hand, SwingAnimation animation, boolean flag, Operation<Boolean> original) {
        if (!DropAnimation.shouldProceed()) return original.call(player, hand, animation, flag);
        return true;
    }
}
