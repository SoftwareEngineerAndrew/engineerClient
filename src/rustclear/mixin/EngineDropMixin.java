package net.rustclear.mod.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.rustclear.mod.engine.EngineServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In the Rustclear world the drop key is the class's, as in a Hypixel dungeon: Ctrl+Q (the whole
 * stack) is the class ability and Q the ultimate (EngineServer.clientDrop), and it never drops an
 * item. As engineerClient's P3 sim does it; 26.3 moved LocalPlayer.drop's work into
 * MultiPlayerGameMode.dropItem.
 */
@Mixin(MultiPlayerGameMode.class)
public class EngineDropMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void rustclear$classAbility(LocalPlayer player, boolean fullStack, CallbackInfo ci) {
        if (EngineServer.clientDrop(fullStack)) {
            ci.cancel();
        }
    }
}
