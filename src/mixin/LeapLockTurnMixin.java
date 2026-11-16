package com.engineerclient.mixin;

import com.odtheking.odin.features.impl.dungeon.LeapMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a leap is shown before it lands, the mouse turns only the view; your own look stays where it was. */
@Mixin(Entity.class)
public abstract class LeapLockTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void ec$leapTurn(double xo, double yo, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player || !LeapMenu.locked()) return;
        LeapMenu.turn(xo, yo);
        ci.cancel();
    }
}
