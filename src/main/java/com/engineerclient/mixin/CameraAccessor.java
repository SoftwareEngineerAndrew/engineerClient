package com.engineerclient.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The camera's eye height, which only {@code Camera.tick()} maintains.
 *
 * <p>A POV pass must not tick the shared camera (that would also write its environment probe from
 * the teammate's position and pollute the real view's fog and cloud colour), so it writes the eye
 * height for the pass instead. Without this the preview is drawn from the LOCAL player's eye
 * height, which is visibly wrong whenever the teammate is sneaking, riding or dying.

 *
 * <p>The debug "captured frustum" is borrowed during a POV pass, so vanilla neither
 * re-culls the terrain nor rebuilds its occlusion graph from the teammate's eyes.
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    @Accessor("eyeHeight")
    float ec$getEyeHeight();

    @Accessor("eyeHeight")
    void ec$setEyeHeight(float eyeHeight);

    @Accessor("eyeHeightOld")
    float ec$getEyeHeightOld();

    @Accessor("eyeHeightOld")
    void ec$setEyeHeightOld(float eyeHeightOld);

    @Accessor("capturedFrustum")
    Frustum ec$getCapturedFrustum();

    @Accessor("capturedFrustum")
    void ec$setCapturedFrustum(Frustum frustum);
}
