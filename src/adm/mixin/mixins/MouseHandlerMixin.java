package com.adm.mixin.mixins;

import com.mojang.blaze3d.platform.InputConstants;
import com.adm.AdmMod;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Shadow
    private double xpos;
    @Shadow
    private double ypos;

    @Unique
    private double beforeX;
    @Unique
    private double beforeY;

    @Inject(method = "grabMouse", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;xpos:D", ordinal = 0, opcode = Opcodes.PUTFIELD))
    private void cacheCursorPosition(CallbackInfo ci) {
        this.beforeX = this.xpos;
        this.beforeY = this.ypos;
    }

    @Inject(method = "releaseMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getWindow()Lcom/mojang/blaze3d/platform/Window;"))
    private void correctCursorPosition(CallbackInfo ci) {
        if (AdmMod.getMc().gui.screen() instanceof ContainerScreen && com.engineerclient.misc.RandomStuff.shouldHookMouse()) {
            InputConstants.releaseMouse(AdmMod.getMc().getWindow(), this.beforeX, this.beforeY);
            this.xpos = this.beforeX;
            this.ypos = this.beforeY;
        }
    }
}