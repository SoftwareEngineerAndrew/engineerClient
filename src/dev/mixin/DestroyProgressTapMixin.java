package com.devgineerclient.mixin;

import com.devgineerclient.recorder.InputCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: block-breaking stages as the client draws them, yours and other players'
 * (the server's block_destruction packet and your own mining both end up here). Observe-only and
 * optional (require = 0).
 */
@Mixin(value = ClientLevel.class, priority = 1)
public class DestroyProgressTapMixin {

    @Inject(method = "destroyBlockProgress(ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recBreak(int breakerId, BlockPos pos, int progress, CallbackInfo ci) {
        try { InputCapture.breakProgress(breakerId, pos, progress); } catch (Throwable ignored) { }
    }
}
