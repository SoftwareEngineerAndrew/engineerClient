package com.engineerclient.misc

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.features.ModuleManager

/**
 * Odin's HUDs never off screen (HudOnScreenMixin): Odin's HUD editor pulls the HUDs onto the screen when it opens and
 * as you move or resize one, but in play each is drawn where it was saved, so a smaller window, a bigger GUI scale or a HUD grown wider could leave it off screen
 * until the editor was opened. Each is drawn clamped to the screen (as the editor would put it), its saved place left
 * as it is: back where it was once there's room.
 */
object OdinOnScreen {

    /** Runs [draw] (Odin drawing the HUDs in play) with each HUD inside the screen, then puts the saved places back. */
    @JvmStatic
    fun hudsOnScreen(draw: Runnable) {
        val huds = ModuleManager.hudSettingsCache.filter { it.isEnabled }.map { it.hud }
        val saved = IntArray(huds.size * 2)
        val width = mc.window.guiScaledWidth
        val height = mc.window.guiScaledHeight
        huds.forEachIndexed { i, hud ->
            saved[2 * i] = hud.x; saved[2 * i + 1] = hud.y
            // A minimised window has no size: leave every HUD where it is.
            if (width > 0 && height > 0) {
                hud.x = hud.x.coerceIn(0, (width - hud.scaledWidth).coerceAtLeast(0))
                hud.y = hud.y.coerceIn(0, (height - hud.scaledHeight).coerceAtLeast(0))
            }
        }
        try {
            draw.run()
        } finally {
            huds.forEachIndexed { i, hud -> hud.x = saved[2 * i]; hud.y = saved[2 * i + 1] }
        }
    }
}
