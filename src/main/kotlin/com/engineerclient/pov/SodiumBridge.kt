package com.engineerclient.pov

import net.fabricmc.loader.api.FabricLoader

/**
 * Whether Sodium is there to re-cull the terrain from a preview camera.
 *
 * Sodium replaces the whole vanilla terrain path: what a `renderLevel` draws is whatever the last
 * `SodiumWorldRenderer.setupTerrain` produced. In 26.2 Sodium runs that from inside the level
 * extract, from whichever camera is extracted, unless the camera has a captured frustum; a preview
 * pass lends it one unless Re-cull Terrain is on (`PovCapture.renderFeed`). Without a re-cull a
 * preview shows only the sections visible from your own frustum - from a teammate's eyes, mostly a
 * hole. The re-cull also puts the sections only the preview can see into the build queue, so a
 * cold direction fills in over the next few frames.
 *
 * Two known costs, both accepted (see `POV_SODIUM_BRIEF.md` §2): no cached cull tree is reusable
 * between cameras more than a section apart, so every switch is a fresh async cull; and Sodium's
 * `AsyncCameraTimingControl` latches "sync" mode while the camera keeps jumping 32+ blocks per
 * call, which drops occlusion culling for both views. It unlatches once the menu closes, and the
 * next frame's own cull restores the main view's lists with no flicker.
 */
object SodiumBridge {

    val available: Boolean = FabricLoader.getInstance().isModLoaded("sodium")
}
