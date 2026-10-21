package net.rustclear.mod.mixin;

import net.minecraft.client.Minecraft;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A left click reaches the server only as a block hit or an attack; a click on air doesn't at all.
 * In the Rustclear world every press is passed on (EngineServer.clientLeftClick), so shortbows
 * shoot on left click. Vanilla's handling of the click goes on as before.
 */
@Mixin(Minecraft.class)
public class EngineLeftClickMixin {
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void rustclear$engineLeftClick(CallbackInfoReturnable<Boolean> cir) {
        EngineServer.clientLeftClick();
    }
}
