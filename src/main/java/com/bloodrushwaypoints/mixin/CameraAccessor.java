package com.bloodrushwaypoints.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The camera's eye height, which only {@code Camera.tick()} maintains.
 *
 * <p>A POV pass must not tick the shared camera (that would also write its environment probe from
 * the teammate's position and pollute the real view's fog and cloud colour), so it writes the eye
 * height for the pass instead. Without this the preview is drawn from the LOCAL player's eye
 * height, which is visibly wrong whenever the teammate is sneaking, riding or dying.
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    @Accessor("eyeHeight")
    float brw$getEyeHeight();

    @Accessor("eyeHeight")
    void brw$setEyeHeight(float eyeHeight);

    @Accessor("eyeHeightOld")
    float brw$getEyeHeightOld();

    @Accessor("eyeHeightOld")
    void brw$setEyeHeightOld(float eyeHeightOld);
}
