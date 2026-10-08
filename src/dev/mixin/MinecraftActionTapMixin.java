package com.devgineerclient.mixin;

import com.devgineerclient.recorder.InputCapture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dungeon Recorder: the actions the game starts from your keys (attack, use, held attack, pick
 * block), at HEAD and at RETURN. A start without its return means something cancelled it before it
 * ran (see {@link InputCapture#actHead}). Priority 1 so HEAD runs ahead of other mods' HEAD
 * injections, which may cancel. Whether a cancelled call skips the RETURN callback depends on how
 * the other mixin was applied; check the done flag against the outbound packets.
 * Observe-only and optional (require = 0).
 */
@Mixin(value = Minecraft.class, priority = 1)
public class MinecraftActionTapMixin {

    @Inject(method = "startAttack()Z", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recAttackHead(CallbackInfoReturnable<Boolean> cir) {
        try { InputCapture.actHead("startAttack", null); } catch (Throwable ignored) { }
    }

    @Inject(method = "startAttack()Z", at = @At("RETURN"), require = 0, expect = 0)
    private void dc$recAttackReturn(CallbackInfoReturnable<Boolean> cir) {
        try { InputCapture.actReturn("startAttack", String.valueOf(cir.getReturnValueZ())); } catch (Throwable ignored) { }
    }

    @Inject(method = "startUseItem()V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recUseHead(CallbackInfo ci) {
        try { InputCapture.actHead("startUseItem", null); } catch (Throwable ignored) { }
    }

    @Inject(method = "startUseItem()V", at = @At("RETURN"), require = 0, expect = 0)
    private void dc$recUseReturn(CallbackInfo ci) {
        try { InputCapture.actReturn("startUseItem", null); } catch (Throwable ignored) { }
    }

    @Inject(method = "continueAttack(Z)V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recContinueHead(boolean down, CallbackInfo ci) {
        try { InputCapture.continueAttackHead(down); } catch (Throwable ignored) { }
    }

    @Inject(method = "continueAttack(Z)V", at = @At("RETURN"), require = 0, expect = 0)
    private void dc$recContinueReturn(boolean down, CallbackInfo ci) {
        try { InputCapture.actReturn("continueAttack", null); } catch (Throwable ignored) { }
    }

    @Inject(method = "pickBlockOrEntity()V", at = @At("HEAD"), require = 0, expect = 0)
    private void dc$recPickHead(CallbackInfo ci) {
        try { InputCapture.actHead("pickBlockOrEntity", null); } catch (Throwable ignored) { }
    }

    @Inject(method = "pickBlockOrEntity()V", at = @At("RETURN"), require = 0, expect = 0)
    private void dc$recPickReturn(CallbackInfo ci) {
        try { InputCapture.actReturn("pickBlockOrEntity", null); } catch (Throwable ignored) { }
    }
}
