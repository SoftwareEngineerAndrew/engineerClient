package com.engineerclient.mixin;

import com.engineerclient.recorder.EntityCapture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dungeon Recorder: an entity the level renderer is about to draw (it passed culling), with its
 * name tag and outline as they will be drawn: what the player could actually see. Read only.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderTapMixin {

    @Inject(
        method = "extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
        at = @At("RETURN"),
        require = 0,
        expect = 0
    )
    private void ec$recDrawn(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
        if (!EntityCapture.drawnOn) return;
        // The recorder must never break the game: whatever it hits stays here.
        try {
            EntityCapture.onExtract((EntityRenderDispatcher) (Object) this, entity, cir.getReturnValue());
        } catch (Throwable ignored) {
        }
    }
}
