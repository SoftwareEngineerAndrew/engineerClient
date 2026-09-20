package com.bloodrushwaypoints.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The private halves of {@code GameRenderer.extract} that a POV pass needs.
 *
 * <p>BRW deliberately does NOT call the public {@code extract(delta, true)}: that also runs
 * {@code extractGui}, which resets the frame's {@code GuiRenderState} and replays every screen and
 * HUD handler (Odin's leap menu included) a second time, with the POV window size and camera. The
 * four pieces below are the ones a second world pass actually needs, so the GUI state built before
 * {@code renderLevel} survives untouched.
 *
 * <ul>
 *   <li>{@code extractWindow} — window size drives the HUD projection and, via
 *       {@code Camera.update}, the aspect ratio.</li>
 *   <li>{@code extractOptions} — carries {@code cameraType} and {@code hideGui} into the render
 *       state; {@code hideGui} is what keeps the local player's hand out of the preview.</li>
 *   <li>{@code extractCamera} — camera render state + fog for the preview eye.</li>
 * </ul>
 *
 * <p>The lightmap extraction that {@code extract} also does is skipped: it is a function of the
 * local player and the frame already ran it with the same value.
 */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {

    @Invoker("extractWindow")
    void brw$extractWindow();

    @Invoker("extractOptions")
    void brw$extractOptions();

    @Invoker("extractCamera")
    void brw$extractCamera(DeltaTracker deltaTracker, float worldPartialTicks, float cameraEntityPartialTicks);
}
