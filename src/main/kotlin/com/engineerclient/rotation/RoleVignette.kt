package com.engineerclient.rotation

import com.engineerclient.EngineerClient
import com.odtheking.odin.utils.Color
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

/**
 * A coloured glow around the edge of the screen, so a role hand-off registers without reading
 * anything. It fades out on its own; nothing about the rotation depends on it.
 *
 * Registered as its own HUD layer rather than an Odin HUD element because it is full-screen and
 * has no anchor to drag.
 */
object RoleVignette {

    private const val FADE_TICKS = 34

    private var color: Color = Color(255, 255, 255)
    private var ticksLeft = 0
    private var startedAt = 0

    /**
     * Odin leap-menu quadrant to confine the glow to — 0 top-left, 1 top-right, 2 bottom-left,
     * 3 bottom-right, the same order as `DungeonUtils.leapTeammates` — or null for the whole
     * edge. The corner tells you where your leap target's box will be before the menu is open.
     */
    private var quadrant: Int? = null

    fun register() {
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.SLEEP,
            Identifier.fromNamespaceAndPath("engineerclient", "role_vignette"),
            RoleVignette::render,
        )
    }

    /** Flash the screen edge, or just one leap-menu corner of it. Later calls simply restart it. */
    fun flash(color: Color, ticks: Int = FADE_TICKS, quadrant: Int? = null) {
        this.color = color
        this.ticksLeft = ticks
        this.startedAt = ticks
        this.quadrant = quadrant?.takeIf { it in 0..3 }
    }

    fun tick() {
        if (ticksLeft > 0) ticksLeft--
    }

    fun clear() {
        ticksLeft = 0
    }

    private fun render(gfx: GuiGraphicsExtractor, delta: DeltaTracker) {
        if (ticksLeft <= 0 || EngineerClient.mc.options.hideGui) return
        val mc = EngineerClient.mc
        val w = mc.window.guiScaledWidth
        val h = mc.window.guiScaledHeight
        // Ease out, so it lands hard and leaves quietly.
        val t = ticksLeft.toFloat() / startedAt.coerceAtLeast(1)
        val strength = t * t
        val depth = (minOf(w, h) * 0.16f).toInt().coerceAtLeast(8)

        // Four gradient bands rather than a texture: cheap, and it never fights the crosshair.
        val edge = color.withAlpha(0.55f * strength).rgba
        val fade = color.withAlpha(0f).rgba
        val q = quadrant
        if (q == null) {
            gfx.fillGradient(0, 0, w, depth, edge, fade)
            gfx.fillGradient(0, h - depth, w, h, fade, edge)
            verticalBand(gfx, 0, depth, 0, h, edge, fade, leftToRight = true)
            verticalBand(gfx, w - depth, w, 0, h, edge, fade, leftToRight = false)
            return
        }
        // One corner: the two bands that meet there, each running to the middle of its edge, so
        // the glow sits exactly where that player's box will be in the leap menu.
        val right = q % 2 == 1
        val bottom = q / 2 == 1
        val x0 = if (right) w / 2 else 0
        val x1 = if (right) w else w / 2
        val y0 = if (bottom) h / 2 else 0
        val y1 = if (bottom) h else h / 2
        val cornerDepth = (depth * 1.4f).toInt()
        if (bottom) gfx.fillGradient(x0, h - cornerDepth, x1, h, fade, edge)
        else gfx.fillGradient(x0, 0, x1, cornerDepth, edge, fade)
        if (right) verticalBand(gfx, w - cornerDepth, w, y0, y1, edge, fade, leftToRight = false)
        else verticalBand(gfx, 0, cornerDepth, y0, y1, edge, fade, leftToRight = true)
    }

    /** fillGradient only runs top-to-bottom, so the side bands are drawn as thin columns. */
    private fun verticalBand(gfx: GuiGraphicsExtractor, x0: Int, x1: Int, y0: Int, y1: Int, edge: Int, fade: Int, leftToRight: Boolean) {
        val width = x1 - x0
        if (width <= 0) return
        val steps = 12
        for (i in 0 until steps) {
            val a = i.toFloat() / steps
            val sliceX0 = x0 + (width * a).toInt()
            val sliceX1 = x0 + (width * (i + 1f) / steps).toInt()
            val mix = if (leftToRight) 1f - a else a
            val c = blend(edge, fade, mix)
            gfx.fill(sliceX0, y0, sliceX1, y1, c)
        }
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private fun Color.withAlpha(alpha: Float): Color = Color(this.rgba, alpha)
}
