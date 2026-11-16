package com.engineerclient.mixin;

import com.engineerclient.visual.PerfMonitor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Performance, Record Performance: the world and the GUI inside GameRenderer.render (see GamePerfMixin). */
@Mixin(GameRenderer.class)
public class GamePerfRenderMixin {
    @WrapOperation(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"), require = 0, expect = 0)
    private void ec$perfWorld(GameRenderer renderer, Operation<Void> original) {
        PerfMonitor.begin(PerfMonitor.WORLD); original.call(renderer); PerfMonitor.end(PerfMonitor.WORLD);
    }

    @WrapOperation(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"), require = 0, expect = 0)
    private void ec$perfGui(GuiRenderer gui, Operation<Void> original) {
        PerfMonitor.begin(PerfMonitor.GUI); original.call(gui); PerfMonitor.end(PerfMonitor.GUI);
    }
}
