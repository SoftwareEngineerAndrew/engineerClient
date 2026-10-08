package com.devgineerclient.mixin;

import com.devgineerclient.recorder.PacketFate;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Recorder: the end of one drain of the game thread's packet queue (Minecraft.runTick calls
 * processQueuedPackets once a frame, before the tick), so {@link PacketFate} writes one `applied`
 * line per drain, its time taken right as the packets took effect. Optional (require = 0): without
 * it the batch is written at the next tick end instead.
 */
@Mixin(PacketProcessor.class)
public class PacketProcessorDrainMixin {

    @Inject(method = "processQueuedPackets()V", at = @At("RETURN"), require = 0, expect = 0)
    private void dc$drained(CallbackInfo ci) {
        try {
            PacketFate.INSTANCE.flushApplied();
        } catch (Throwable ignored) {
            // Never let the recorder break the game.
        }
    }
}
