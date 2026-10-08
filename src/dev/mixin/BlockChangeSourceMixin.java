package com.devgineerclient.mixin;

import com.devgineerclient.recorder.WorldCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: where a block change came from. Every change the client applies reaches
 * LevelChunk.setBlockState (Odin's BlockUpdateEvent, which the recorder writes as `blk`); the ones
 * made between HEAD and RETURN of these two methods are the server's: a block or section update
 * ("server") or the server settling blocks you predicted ("ack"). Anything else is the client's own.
 *
 * Optional (require = 0): if a target ever moves, the recorder only loses the source label.
 */
@Mixin(ClientLevel.class)
public class BlockChangeSourceMixin {

    @Inject(
        method = "setServerVerifiedBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private void dc$serverBlockHead(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        try {
            WorldCapture.sourceEnter("server");
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }

    @Inject(
        method = "setServerVerifiedBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void dc$serverBlockReturn(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        try {
            WorldCapture.sourceExit();
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleBlockChangedAck(I)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$ackHead(int sequence, CallbackInfo ci) {
        try {
            WorldCapture.sourceEnter("ack");
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "handleBlockChangedAck(I)V", at = @At("RETURN"), require = 0, expect = 0)
    private void dc$ackReturn(int sequence, CallbackInfo ci) {
        try {
            WorldCapture.sourceExit();
        } catch (Throwable ignored) {
        }
    }
}
