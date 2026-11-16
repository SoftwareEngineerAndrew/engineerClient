package com.engineerclient.mixin;

import com.adm.features.impl.dungeon.LeapMenu;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a leap is shown before it lands, you stand still where you are. */
@Mixin(KeyboardInput.class)
public abstract class LeapLockInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("RETURN"))
    private void ec$leapStill(CallbackInfo ci) {
        if (!LeapMenu.locked()) return;
        this.keyPresses = Input.EMPTY;
        this.moveVector = Vec2.ZERO;
    }
}
