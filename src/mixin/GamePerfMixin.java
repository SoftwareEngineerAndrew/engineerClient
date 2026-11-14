package com.engineerclient.mixin;

import com.engineerclient.visual.PerfMonitor;
import net.minecraft.client.Minecraft;
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

    @Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"), require = 0, expect = 0)
    private void ec$perfPacketsA(boolean advance, CallbackInfo ci) { PerfMonitor.begin(PerfMonitor.PACKETS); }
    @Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void ec$perfPacketsB(boolean advance, CallbackInfo ci) { PerfMonitor.end(PerfMonitor.PACKETS); }

    @Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"), require = 0, expect = 0)
    private void ec$perfTasksA(boolean advance, CallbackInfo ci) { PerfMonitor.begin(PerfMonitor.TASKS); }
    @Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void ec$perfTasksB(boolean advance, CallbackInfo ci) { PerfMonitor.end(PerfMonitor.TASKS); }

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0, expect = 0)
    private void ec$perfTickA(CallbackInfo ci) { PerfMonitor.tickBegin(); }
    @Inject(method = "tick()V", at = @At("RETURN"), require = 0, expect = 0)
    private void ec$perfTickB(CallbackInfo ci) { PerfMonitor.tickEnd(); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"), require = 0, expect = 0)
    private void ec$perfAcquireA(boolean advance, CallbackInfo ci) { PerfMonitor.begin(PerfMonitor.ACQUIRE); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void ec$perfAcquireB(boolean advance, CallbackInfo ci) { PerfMonitor.end(PerfMonitor.ACQUIRE); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"), require = 0, expect = 0)
    private void ec$perfExtractA(boolean advance, CallbackInfo ci) { PerfMonitor.begin(PerfMonitor.EXTRACT); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void ec$perfExtractB(boolean advance, CallbackInfo ci) { PerfMonitor.end(PerfMonitor.EXTRACT); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"), require = 0, expect = 0)
    private void ec$perfRenderA(boolean advance, CallbackInfo ci) { PerfMonitor.begin(PerfMonitor.RENDER); PerfMonitor.gpuBegin(); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void ec$perfRenderB(boolean advance, CallbackInfo ci) { PerfMonitor.gpuEnd(); PerfMonitor.end(PerfMonitor.RENDER); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"), require = 0, expect = 0)
    private void ec$perfSubmitA(boolean advance, CallbackInfo ci) { PerfMonitor.begin(PerfMonitor.SUBMIT); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void ec$perfSubmitB(boolean advance, CallbackInfo ci) { PerfMonitor.end(PerfMonitor.SUBMIT); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"), require = 0, expect = 0)
    private void ec$perfPresentA(boolean advance, CallbackInfo ci) { PerfMonitor.begin(PerfMonitor.PRESENT); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void ec$perfPresentB(boolean advance, CallbackInfo ci) { PerfMonitor.end(PerfMonitor.PRESENT); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"), require = 0, expect = 0)
    private void ec$perfLimitA(boolean advance, CallbackInfo ci) { PerfMonitor.begin(PerfMonitor.LIMITER); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void ec$perfLimitB(boolean advance, CallbackInfo ci) { PerfMonitor.end(PerfMonitor.LIMITER); }
}
