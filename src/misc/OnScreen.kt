package com.engineerclient.misc

import com.engineerclient.EngineerClient.mc
import com.adm.features.ModuleManager

/**
 * ADM's HUDs never off screen (HudOnScreenMixin): ADM's HUD editor pulls the HUDs onto the screen when it opens and
 * as you move or resize one, but in play each is drawn where it was saved, so a smaller window, a bigger GUI scale or a HUD grown wider could leave it off screen
 * until the editor was opened. Each is drawn clamped to the screen (as the editor would put it), its saved place left
 * as it is: back where it was once there's room.
 */
object OnScreen {

    private var moved = IntArray(48)

    /** Runs [draw] (ADM drawing the HUDs in play) with each HUD inside the screen, then puts the saved places back. */
    @JvmStatic
    fun hudsOnScreen(draw: Runnable) {
        val width = mc.window.guiScaledWidth
        val height = mc.window.guiScaledHeight
        // A minimised window has no size: leave every HUD where it is.
        if (width <= 0 || height <= 0) return draw.run()
        val settings = ModuleManager.hudSettingsCache
        // Only the HUDs that had to move are kept, as (index, x, y).
        var n = 0
        for (i in settings.indices) {
            val setting = settings[i]
            if (!setting.isEnabled) continue
            val hud = setting.hud
            val x = hud.x.coerceIn(0, (width - hud.scaledWidth).coerceAtLeast(0))
            val y = hud.y.coerceIn(0, (height - hud.scaledHeight).coerceAtLeast(0))
            if (x == hud.x && y == hud.y) continue
            if (n + 3 > moved.size) moved = moved.copyOf(moved.size * 2)
            moved[n] = i; moved[n + 1] = hud.x; moved[n + 2] = hud.y
            n += 3
            hud.x = x; hud.y = y
        }
        try {
            draw.run()
        } finally {
            var k = 0
            while (k < n) {
                val hud = settings[moved[k]].hud
                hud.x = moved[k + 1]; hud.y = moved[k + 2]
                k += 3
            }
        }
    }
}
