package net.rustclear.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.HitResult;
import net.rustclear.mod.engine.Engine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adventure mode hides the outline of the block you look at; Hypixel's dungeons (survival there)
 * show it, so the Rustclear world does too. Everything else about when it shows stays vanilla.
 */
@Mixin(GameRenderer.class)
public class EngineOutlineMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private boolean renderBlockOutline;

    @Inject(method = "shouldRenderBlockOutline", at = @At("RETURN"), cancellable = true)
    private void rustclear$engineOutline(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || !renderBlockOutline || minecraft.gui.hud.isHidden()) {
            return;
        }
        if (minecraft.getCameraEntity() instanceof LocalPlayer && minecraft.hitResult != null
            && minecraft.hitResult.getType() == HitResult.Type.BLOCK && Engine.isEngineClient()) {
            cir.setReturnValue(true);
        }
    }
}
