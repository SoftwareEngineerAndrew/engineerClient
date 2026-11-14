package com.engineerclient.mixin;

import com.engineerclient.visual.PerfMonitor;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Performance, Record Performance: the world and the GUI inside GameRenderer.render (see GamePerfMixin). */
@Mixin(GameRenderer.class)
public class GamePerfRenderMixin {
    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"), require = 0, expect = 0)
    private void ec$perfWorldA(CallbackInfo ci) { PerfMonitor.begin(PerfMonitor.WORLD); }
    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void ec$perfWorldB(CallbackInfo ci) { PerfMonitor.end(PerfMonitor.WORLD); }

    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"), require = 0, expect = 0)
    private void ec$perfGuiA(CallbackInfo ci) { PerfMonitor.begin(PerfMonitor.GUI); }
    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.AFTER), require = 0, expect = 0)
    private void ec$perfGuiB(CallbackInfo ci) { PerfMonitor.end(PerfMonitor.GUI); }
}
