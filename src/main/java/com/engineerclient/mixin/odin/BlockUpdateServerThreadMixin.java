package com.engineerclient.mixin.odin;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Odin posts its BlockUpdateEvent from LevelChunk.setBlockState, which in singleplayer (the P3 Sim)
 * also runs on the integrated server's thread: every block change reached Odin twice, the server's
 * copy early and off the client thread (Simon Says' and the arrows device's solvers got confused).
 * Only the client's own changes go through. On a server (Hypixel) there is no integrated server,
 * so nothing changes there.
 */
@Pseudo
@Mixin(targets = "com.odtheking.odin.events.BlockUpdateEvent", remap = false)
public class BlockUpdateServerThreadMixin {
    @Inject(method = "postAndCatch()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void ec$clientOnly(CallbackInfoReturnable<Boolean> cir) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null && server.isSameThread()) cir.setReturnValue(false);
    }
}
