package com.engineerclient.mixin;

import com.engineerclient.p3sim.clear.replay.ReplayClient;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While an ADS replay drives your player the movement keys are off, but for the recorded crouch (the camera height and pose). */
@Mixin(KeyboardInput.class)
public abstract class AdsReplayKeysMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void ec$replayKeys(CallbackInfo ci) {
        if (!ReplayClient.active) return;
        this.keyPresses = new Input(false, false, false, false, false, ReplayClient.crouch(), false);
        this.moveVector = Vec2.ZERO;
    }
}
