package com.engineerclient.mixin;

import com.engineerclient.devices.DeviceSolvers;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The device solvers' clicks ({@link DeviceSolvers}): a right click on a block or an entity, just before the game
 * uses it, cancelled when a solver blocks it (Block Wrong Clicks). After Odin's own hook at the same spot (a later
 * priority), so its events still see every click.
 */
@Mixin(value = Minecraft.class, priority = 1100)
public class DeviceInputMixin {
    @Shadow @Nullable public HitResult hitResult;

    @Inject(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"), cancellable = true)
    private void ec$deviceUseBlock(CallbackInfo ci) {
        if (hitResult instanceof BlockHitResult hit && DeviceSolvers.onUseBlock(hit)) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"), cancellable = true)
    private void ec$deviceUseEntity(CallbackInfo ci) {
        if (hitResult instanceof EntityHitResult hit && DeviceSolvers.onUseEntity(hit.getEntity())) ci.cancel();
    }
}
