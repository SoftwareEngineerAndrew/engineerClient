package com.engineerclient.mixin;

import com.engineerclient.visual.PerfMonitor;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Performance, Record Performance: the frame's phases on the game thread (packets, tasks, ticks, then renderFrame's waits for an
 * image, extract, render, submit, present and the fps limiter). Each is a nanoTime when the monitor is on and a field read
 * when it isn't. All optional: a phase that doesn't match just isn't shown.
 */
@Mixin(Minecraft.class)
public class GamePerfMixin {
    @Inject(method = "runTick(Z)V", at = @At("HEAD"), require = 0, expect = 0)
    private void ec$perfFrame(boolean advance, CallbackInfo ci) { PerfMonitor.frame(); }

    @WrapOperation(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"), require = 0, expect = 0)
    private void ec$perfPackets(PacketProcessor processor, Operation<Void> original) {
        PerfMonitor.begin(PerfMonitor.PACKETS); original.call(processor); PerfMonitor.end(PerfMonitor.PACKETS);
    }

    @WrapOperation(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"), require = 0, expect = 0)
    private void ec$perfTasks(Minecraft minecraft, Operation<Void> original) {
        PerfMonitor.begin(PerfMonitor.TASKS); original.call(minecraft); PerfMonitor.end(PerfMonitor.TASKS);
    }

    @WrapMethod(method = "tick()V", require = 0, expect = 0)
    private void ec$perfTick(Operation<Void> original) {
        PerfMonitor.tickBegin(); original.call(); PerfMonitor.tickEnd();
    }

    @WrapOperation(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"), require = 0, expect = 0)
    private void ec$perfAcquire(GpuSurface surface, Operation<Void> original) {
        PerfMonitor.begin(PerfMonitor.ACQUIRE); original.call(surface); PerfMonitor.end(PerfMonitor.ACQUIRE);
    }

    @WrapOperation(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"), require = 0, expect = 0)
    private void ec$perfExtract(GameRenderer renderer, DeltaTracker deltaTracker, boolean advance, Operation<Void> original) {
        PerfMonitor.begin(PerfMonitor.EXTRACT); original.call(renderer, deltaTracker, advance); PerfMonitor.end(PerfMonitor.EXTRACT);
    }

    @WrapOperation(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"), require = 0, expect = 0)
    private void ec$perfRender(GameRenderer renderer, Operation<Void> original) {
        PerfMonitor.begin(PerfMonitor.RENDER); PerfMonitor.gpuBegin();
        original.call(renderer);
        PerfMonitor.gpuEnd(); PerfMonitor.end(PerfMonitor.RENDER);
    }

    @WrapOperation(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"), require = 0, expect = 0)
    private void ec$perfSubmit(CommandEncoder encoder, Operation<Void> original) {
        PerfMonitor.begin(PerfMonitor.SUBMIT); original.call(encoder); PerfMonitor.end(PerfMonitor.SUBMIT);
    }

    @WrapOperation(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"), require = 0, expect = 0)
    private void ec$perfPresent(GpuSurface surface, Operation<Void> original) {
        PerfMonitor.begin(PerfMonitor.PRESENT); original.call(surface); PerfMonitor.end(PerfMonitor.PRESENT);
    }

    @WrapOperation(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"), require = 0, expect = 0)
    private void ec$perfLimit(int fps, Operation<Void> original) {
        PerfMonitor.begin(PerfMonitor.LIMITER); original.call(fps); PerfMonitor.end(PerfMonitor.LIMITER);
    }
}
