package com.engineerclient.mixin;

import com.engineerclient.misc.ScoreboardLines;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// On the client thread once applied: the netty pass throws out of ensureRunningOnSameThread before any return.
@Mixin(ClientPacketListener.class)
public class ScoreboardDirtyMixin {
    @Inject(method = {"handleSetScore", "handleResetScore", "handleAddObjective", "handleSetDisplayObjective", "handleSetPlayerTeamPacket"}, at = @At("RETURN"))
    private void ec$scoreboardChanged(CallbackInfo ci) {
        ScoreboardLines.markDirty();
    }
}
