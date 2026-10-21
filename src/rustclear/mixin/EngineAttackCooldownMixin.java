package net.rustclear.mod.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.rustclear.mod.engine.Engine;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No attack cooldown, as on Hypixel (1.8 combat): swapping hotbar slots or swinging never drains the attack
 * gauge, so the held item never sinks slowly back up after a swap and the crosshair/hotbar meter stays full.
 * Both sides (the client draws the hand, the server scales vanilla hits). Engine world only.
 */
@Mixin(Player.class)
public class EngineAttackCooldownMixin {
    private boolean rustclear$engine() {
        Player self = (Player) (Object) this;
        return self.level().isClientSide() ? Engine.isEngineClient() : EngineServer.isEngineLevel(self.level());
    }

    @Inject(method = "getAttackStrengthScale", at = @At("HEAD"), cancellable = true)
    private void rustclear$fullAttackStrength(float partial, CallbackInfoReturnable<Float> cir) {
        if (rustclear$engine()) {
            cir.setReturnValue(1.0F);
        }
    }

    @Inject(method = "getItemSwapScale", at = @At("HEAD"), cancellable = true)
    private void rustclear$fullItemSwap(float partial, CallbackInfoReturnable<Float> cir) {
        if (rustclear$engine()) {
            cir.setReturnValue(1.0F);
        }
    }

    @Inject(method = "cannotAttackWithItem", at = @At("HEAD"), cancellable = true)
    private void rustclear$noMinimumCharge(ItemStack stack, int ticks, CallbackInfoReturnable<Boolean> cir) {
        if (rustclear$engine()) {
            cir.setReturnValue(false);
        }
    }
}
