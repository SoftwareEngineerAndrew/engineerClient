package com.bloodrushwaypoints.pov

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft

/**
 * Re-runs Sodium's terrain cull from a preview camera.
 *
 * Sodium replaces the whole vanilla terrain path: what a `renderLevel` draws is whatever the last
 * `SodiumWorldRenderer.setupTerrain` produced, and that only ever runs from `LevelRenderer.update`
 * with YOUR camera. Without a re-cull a preview therefore shows only the sections visible from
 * your own frustum — from a teammate's eyes, mostly a hole. The re-cull also puts the sections only
 * the preview can see into the build queue, so a cold direction fills in over the next few frames.
 *
 * Two known costs, both accepted (see `POV_SODIUM_BRIEF.md` §2): no cached cull tree is reusable
 * between cameras more than a section apart, so every switch is a fresh async cull; and Sodium's
 * `AsyncCameraTimingControl` latches "sync" mode while the camera keeps jumping 32+ blocks per
 * call, which drops occlusion culling for both views. It unlatches once the menu closes, and the
 * next frame's own `setupTerrain` restores the main view's lists with no flicker.
 *
 * Every Sodium reference lives in the nested [Impl] object, which is a separate class file: with
 * Sodium absent nothing here loads a `net.caffeinemc` class.
 */
object SodiumBridge {

    val available: Boolean = FabricLoader.getInstance().isModLoaded("sodium")

    /** [camera] must already have been `update`d — its cull frustum is what Sodium is handed. */
    fun recull(camera: Camera) {
        if (available) Impl.recull(camera)
    }

    private object Impl {

        fun recull(camera: Camera) {
            val renderer = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable() ?: return
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return
            // Exactly what Sodium's own `cullTerrain` overwrite builds: the frustum the camera
            // prepared in `Camera.update`, its viewport and cull matrix, and the fog parameters
            // Sodium itself stores on the GameRenderer.
            val frustum = camera.cullFrustum
            val viewport = (frustum as net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider).`sodium$createViewport`()
            val cullMatrix = (frustum as net.caffeinemc.mods.sodium.mixin.core.render.world.FrustumAccessor).`sodium$getMatrix`()
            val fog = (mc.gameRenderer as net.caffeinemc.mods.sodium.client.util.FogStorage).`sodium$getFogParameters`()

            net.caffeinemc.mods.sodium.client.gl.device.RenderDevice.enterManagedCode()
            try {
                // updateChunksImmediately = false: true is the Flawless-Frames path and would
                // block the frame on every pending section build.
                renderer.setupTerrain(camera, viewport, fog, player.isSpectator, false, cullMatrix)
            } finally {
                net.caffeinemc.mods.sodium.client.gl.device.RenderDevice.exitManagedCode()
            }
        }
    }
}
