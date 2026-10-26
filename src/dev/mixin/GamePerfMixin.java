package com.devgineerclient.mixin;

import com.devgineerclient.perf.GameMonitor;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Game performance monitor: the frame's phases on the game thread (packets, tasks, ticks, then renderFrame's waits for an
 * image, extract, render, submit, present and the fps limiter). Each is a nanoTime when the monitor is on and a field read
 * when it isn't. All optional: a phase that doesn't match just isn't shown.
 */
@Mixin(Minecraft.class)
public class GamePerfMixin {
    @Inject(method = "runTick(Z)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$perfFrame(boolean advance, CallbackInfo ci) { GameMonitor.frame(); }

    @Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"), require = 0, expect = 0)
    private void dc$perfPacketsA(boolean advance, CallbackInfo ci) { GameMonitor.begin(GameMonitor.PACKETS); }
    @Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void dc$perfPacketsB(boolean advance, CallbackInfo ci) { GameMonitor.end(GameMonitor.PACKETS); }

    @Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"), require = 0, expect = 0)
    private void dc$perfTasksA(boolean advance, CallbackInfo ci) { GameMonitor.begin(GameMonitor.TASKS); }
    @Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void dc$perfTasksB(boolean advance, CallbackInfo ci) { GameMonitor.end(GameMonitor.TASKS); }

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$perfTickA(CallbackInfo ci) { GameMonitor.tickBegin(); }
    @Inject(method = "tick()V", at = @At("RETURN"), require = 0, expect = 0)
    private void dc$perfTickB(CallbackInfo ci) { GameMonitor.tickEnd(); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"), require = 0, expect = 0)
    private void dc$perfAcquireA(boolean advance, CallbackInfo ci) { GameMonitor.begin(GameMonitor.ACQUIRE); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void dc$perfAcquireB(boolean advance, CallbackInfo ci) { GameMonitor.end(GameMonitor.ACQUIRE); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"), require = 0, expect = 0)
    private void dc$perfExtractA(boolean advance, CallbackInfo ci) { GameMonitor.begin(GameMonitor.EXTRACT); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void dc$perfExtractB(boolean advance, CallbackInfo ci) { GameMonitor.end(GameMonitor.EXTRACT); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"), require = 0, expect = 0)
    private void dc$perfRenderA(boolean advance, CallbackInfo ci) { GameMonitor.begin(GameMonitor.RENDER); GameMonitor.gpuBegin(); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void dc$perfRenderB(boolean advance, CallbackInfo ci) { GameMonitor.gpuEnd(); GameMonitor.end(GameMonitor.RENDER); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"), require = 0, expect = 0)
    private void dc$perfSubmitA(boolean advance, CallbackInfo ci) { GameMonitor.begin(GameMonitor.SUBMIT); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void dc$perfSubmitB(boolean advance, CallbackInfo ci) { GameMonitor.end(GameMonitor.SUBMIT); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"), require = 0, expect = 0)
    private void dc$perfPresentA(boolean advance, CallbackInfo ci) { GameMonitor.begin(GameMonitor.PRESENT); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void dc$perfPresentB(boolean advance, CallbackInfo ci) { GameMonitor.end(GameMonitor.PRESENT); }

    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"), require = 0, expect = 0)
    private void dc$perfLimitA(boolean advance, CallbackInfo ci) { GameMonitor.begin(GameMonitor.LIMITER); }
    @Inject(method = "renderFrame(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void dc$perfLimitB(boolean advance, CallbackInfo ci) { GameMonitor.end(GameMonitor.LIMITER); }
}
