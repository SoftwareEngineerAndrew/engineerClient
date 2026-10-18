package com.engineerclient.mixin;

import com.engineerclient.p3sim.run.ViewPin;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ADS: the chunks of a full run's world arrive at Hypixel's pace. Hypixel sends exactly 5 a server tick, nearest to the player first, so the 225 of its
 * window take 45 ticks. The integrated server's sender for its owner is built as a memory connection, which ignores the batch quota and sends every ready
 * chunk in the same tick (134 in the first tick and 48 in the second, in a recorded run).
 *
 * While a run is starting or on ({@link ViewPin#pacingChunks()}) the sender is built as a remote one and its rate is fixed at {@link ViewPin#CHUNKS_PER_TICK}
 * (the client's own readings of how fast it takes chunks would otherwise set it). Everything else about it, the nearest-first order included, is the
 * game's. In the boss practice and in every other world this does nothing.
 */
@Mixin(PlayerChunkSender.class)
public abstract class AdsEngineChunkPaceMixin {
    @Shadow @Final @Mutable private boolean memoryConnection;
    @Shadow private float desiredChunksPerTick;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ec$adsPaceAtBuild(boolean memoryConnection, CallbackInfo ci) {
        if (!ViewPin.pacingChunks()) return;
        this.memoryConnection = false;
        this.desiredChunksPerTick = ViewPin.CHUNKS_PER_TICK;
    }

    @ModifyVariable(method = "onChunkBatchReceivedByClient", at = @At("HEAD"), argsOnly = true)
    private float ec$adsPaceRate(float desired) {
        return ViewPin.pacingChunks() ? ViewPin.CHUNKS_PER_TICK : desired;
    }
}
