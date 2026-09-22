package com.engineerclient.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the POV previews point the whole level pass at an offscreen target.
 *
 * {@code Minecraft.mainRenderTarget} is {@code private final} in 26.1.2 (26.2 moved it to a public
 * field on GameRenderer, which is why the SecurityCraft recipe needs no accessor there).
 * {@code LevelRenderer.renderLevel} re-reads {@code getMainRenderTarget()} on every call — for the
 * frame-graph "clear" pass and to size every internal target — so swapping the field around a
 * second {@code renderLevel} redirects that entire pass, and only that pass.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Accessor("mainRenderTarget")
    RenderTarget ec$getMainRenderTarget();

    @Mutable
    @Accessor("mainRenderTarget")
    void ec$setMainRenderTarget(RenderTarget target);
}
