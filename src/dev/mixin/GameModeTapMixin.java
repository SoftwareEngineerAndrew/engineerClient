package com.devgineerclient.mixin;

import com.devgineerclient.recorder.InputCapture;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dungeon Recorder: what each interaction came to on the client (the InteractionResult of an item
 * use or an entity interaction) and every attack. Observe-only and optional (require = 0).
 */
@Mixin(value = MultiPlayerGameMode.class, priority = 1)
public class GameModeTapMixin {

    @Inject(
        method = "useItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
        at = @At("RETURN"), require = 0, expect = 0
    )
    private void dc$recUseItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        try { InputCapture.used(hand, cir.getReturnValue()); } catch (Throwable ignored) { }
    }

    @Inject(
        method = "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;",
        at = @At("RETURN"), require = 0, expect = 0
    )
    private void dc$recInteract(Player player, Entity target, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        try { InputCapture.interacted(target, hit, hand, cir.getReturnValue()); } catch (Throwable ignored) { }
    }

    @Inject(
        method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"), require = 0, expect = 0
    )
    private void dc$recAttack(Player player, Entity target, CallbackInfo ci) {
        try { InputCapture.attacked(target); } catch (Throwable ignored) { }
    }
}
