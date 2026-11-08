package com.engineerclient.practice

import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.roundedRect
import com.odtheking.odin.utils.render.roundedRectOutlined
import com.odtheking.odin.utils.render.text
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.util.ARGB

/**
 * How a leap highlight looks (Recore's Core Leap Highlight, EE Highlight's): the part of the screen where a teammate sits in
 * Odin's Leap Menu (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right), so the flick is already on its way when the menu
 * opens. Its settings go on [module], in this order, under the [dropdown] named [dropdownName], showing while [shown] is
 * (the highlight is on). Only ever drawn by the HUD, which renders before any open screen, so a terminal (vanilla or Odin's)
 * always draws over it: it can flash behind one but never covers or recolours a slot.
 *
 * [there] says, for the settings' text, who the highlight is for ("someone is in the core"); [color] is its colour.
 */
class LeapHighlightLook(module: Module, dropdownName: String, private val shown: () -> Boolean, there: String, previewDesc: String, color: Color) {
    private val dropdown = module.registerSetting(DropdownSetting(dropdownName, desc = "").withDependency { shown() })
    /** The look settings show: the highlight on and its dropdown open. */
    val open get() = shown() && dropdown.value
    private val previewS = module.registerSetting(BooleanSetting("Preview", false, desc = previewDesc).withDependency { open })
    private val styleS = module.registerSetting(SelectorSetting("Style", Style.EDGE_GLOW, desc = "Quadrant: the quarter of the screen with their box. Side: the whole left or right half. Top / Bottom: the whole top or bottom half. Edge Glow: a glow along that quarter's two outer edges. Corner Bracket: an L near the crosshair pointing at it. Leap Box: their leap menu box, as Odin draws it.").withDependency { open })
    private val colorS = module.registerSetting(ColorSetting("Color", color, true, desc = "The highlight's colour and opacity.").withDependency { open })
    private val fillS = module.registerSetting(SelectorSetting("Fill", Fill.SOLID, desc = "Solid, or fading out toward the crosshair (strongest at the screen's edge), or the other way round. Quadrant, Side and Top / Bottom.").withDependency { open && style.area })
    private val gapS = module.registerSetting(NumberSetting("Centre Gap", 0, 0..120, 2, desc = "Leaves this much room around the crosshair clear (Odin's boxes start 24 from the centre). Quadrant, Side, Top / Bottom, Corner Bracket.", unit = "px").withDependency { open && (style != Style.LEAP_BOX && style != Style.EDGE_GLOW) })
    private val glowDepthS = module.registerSetting(NumberSetting("Glow Depth", 11, 4..50, 1, desc = "How far in from the edge the Edge Glow reaches, in % of the screen.", unit = "%").withDependency { open && style == Style.EDGE_GLOW })
    private val bracketSizeS = module.registerSetting(NumberSetting("Bracket Size", 24, 6..120, 2, desc = "Length of the Corner Bracket's arms.", unit = "px").withDependency { open && style == Style.BRACKET })
    private val bracketWidthS = module.registerSetting(NumberSetting("Bracket Width", 3, 1..12, 1, desc = "Thickness of the Corner Bracket's arms.", unit = "px").withDependency { open && style == Style.BRACKET })
    private val outlineS = module.registerSetting(BooleanSetting("Outline", false, desc = "A line around the highlighted area. Quadrant, Side, Top / Bottom and Leap Box.").withDependency { open && (style.area || style == Style.LEAP_BOX) })
    private val outlineWidthS = module.registerSetting(NumberSetting("Outline Width", 2, 1..8, 1, desc = "", unit = "px").withDependency { open && (outline && (style.area || style == Style.LEAP_BOX)) })
    private val outlineColorS = module.registerSetting(ColorSetting("Outline Color", Color(color.rgba, 0.8f), true, desc = "").withDependency { open && (outline && (style.area || style == Style.LEAP_BOX)) })
    private val showNameS = module.registerSetting(BooleanSetting("Show Name", false, desc = "Their name on the highlight.").withDependency { open })
    private val showClassS = module.registerSetting(BooleanSetting("Show Class", false, desc = "Their class under the name.").withDependency { open && showName })
    private val nameAtS = module.registerSetting(SelectorSetting("Name Position", NamePos.AREA, desc = "In the middle of the highlighted area, or just off the crosshair toward it.").withDependency { open && showName })
    private val nameScaleS = module.registerSetting(NumberSetting("Name Scale", 1.5, 0.5..4.0, 0.25, desc = "", unit = "x").withDependency { open && showName })
    private val nameColorS = module.registerSetting(ColorSetting("Name Color", Color(color.rgba, 0.8f), true, desc = "").withDependency { open && showName })
    private val pulseS = module.registerSetting(BooleanSetting("Pulse", false, desc = "Breathes between the colour's opacity and a fraction of it.").withDependency { open })
    private val pulseSpeedS = module.registerSetting(NumberSetting("Pulse Speed", 1.5, 0.25..6.0, 0.25, desc = "Pulses a second.", unit = "/s").withDependency { open && pulse })
    private val pulseLowS = module.registerSetting(NumberSetting("Pulse Low", 30, 0..100, 5, desc = "The faintest point of the pulse, in % of the colour's opacity.", unit = "%").withDependency { open && pulse })
    private val fadeInS = module.registerSetting(NumberSetting("Fade In", 0, 0..1000, 50, desc = "Fades in over this long once $there.", unit = "ms").withDependency { open })
    private val overScreensS = module.registerSetting(BooleanSetting("Over Screens", true, desc = "Also shown while a screen is open (terminals, chat, your inventory), in the background behind it: the screen always draws over it, so it never hides or recolours a terminal. Off: only with no screen open.").withDependency { open })
    private val overLeapS = module.registerSetting(BooleanSetting("Over Leap Menu", true, desc = "Keeps showing once the leap menu is open, behind it.").withDependency { open && overScreens })

    val preview get() = previewS.value
    private val style get() = styleS.value
    private val outline get() = outlineS.value
    private val showName get() = showNameS.value
    private val pulse get() = pulseS.value
    private val overScreens get() = overScreensS.value

    enum class Style(private val label: String, val area: Boolean) {
        QUADRANT("Quadrant", true), SIDE("Side", true), VERTICAL("Top / Bottom", true), EDGE_GLOW("Edge Glow", false),
        BRACKET("Corner Bracket", false), LEAP_BOX("Leap Box", false);
        override fun toString() = label
    }
    enum class Fill(private val label: String) { SOLID("Solid"), TO_CENTRE("Fade To Crosshair"), TO_EDGE("Fade To Edge"); override fun toString() = label }
    enum class NamePos(private val label: String) { AREA("Area Middle"), CROSSHAIR("By Crosshair"); override fun toString() = label }

    /** Someone to highlight: [i] is their place in Odin's Leap Menu, [since] when they got there; [extra] a line under the name. */
    class Target(val i: Int, val name: String, val clazz: String, val skin: net.minecraft.world.entity.player.PlayerSkin, val since: Long, val extra: String? = null)

    /** Whether it may show now, as far as the open screen goes (none, or one it's set to show behind). */
    fun screenAllows(): Boolean {
        val screen = mc.gui.screen() ?: return true
        if (!(overScreens || preview)) return false
        return overLeapS.value || !isLeapScreen(screen)
    }

    /** Draws [targets], earliest first: a Side or Top / Bottom half two of them share is drawn once, as the first one's. */
    fun draw(gfx: GuiGraphicsExtractor, targets: List<Target>) {
        val drawnAreas = HashSet<Int>()
        for (t in targets) drawTarget(gfx, t, drawnAreas, targets.size > 1)
    }

    /**
     * One [Target]'s highlight. [drawnAreas] holds the Side / Top / Bottom halves already filled, so a half two
     * targets share isn't filled twice (twice as strong); with [several], a name goes in its own quarter there.
     */
    private fun drawTarget(gfx: GuiGraphicsExtractor, t: Target, drawnAreas: MutableSet<Int>, several: Boolean) {
        val now = System.currentTimeMillis()
        var fade = 1f
        val fadeIn = fadeInS.value
        if (fadeIn > 0) fade *= ((now - t.since) / fadeIn.toFloat()).coerceIn(0f, 1f)
        if (pulse) {
            val p = ((Math.sin(now / 1000.0 * pulseSpeedS.value * 2 * Math.PI) + 1) / 2).toFloat()
            val low = pulseLowS.value / 100f
            fade *= low + (1 - low) * p
        }
        if (fade <= 0f) return
        fun Color.faded() = Color(rgba, alphaFloat * fade)
        val c = colorS.value.faded()

        val i = t.i
        val w = mc.window.guiScaledWidth
        val h = mc.window.guiScaledHeight
        val cx = w / 2
        val cy = h / 2
        val right = i % 2 == 1
        val bottom = i / 2 == 1
        val sx = if (right) 1 else -1
        val sy = if (bottom) 1 else -1
        val g = gapS.value
        val qx0 = if (right) cx + g else 0
        val qx1 = if (right) w else cx - g
        val qy0 = if (bottom) cy + g else 0
        val qy1 = if (bottom) h else cy - g
        // Where a name in the middle of the area goes.
        var midX = (qx0 + qx1) / 2
        var midY = (qy0 + qy1) / 2

        when (style) {
            Style.QUADRANT, Style.SIDE, Style.VERTICAL -> {
                val x0 = if (style == Style.VERTICAL) 0 else qx0
                val x1 = if (style == Style.VERTICAL) w else qx1
                val y0 = if (style == Style.SIDE) 0 else qy0
                val y1 = if (style == Style.SIDE) h else qy1
                // Sharing a half with another target: the name stays in this one's quarter, so the two don't overlap.
                if (!several || style == Style.QUADRANT) { midX = (x0 + x1) / 2; midY = (y0 + y1) / 2 }
                val area = when (style) { Style.SIDE -> if (right) 1 else 0; Style.VERTICAL -> if (bottom) 2 else 0; else -> i }
                if (drawnAreas.add(area)) {
                    val ax = if (style == Style.VERTICAL) 0 else sx
                    val ay = if (style == Style.SIDE) 0 else sy
                    when (fillS.value) {
                        Fill.SOLID -> gfx.fill(x0, y0, x1, y1, c.rgba)
                        Fill.TO_CENTRE -> fadeRect(gfx, x0, y0, x1, y1, c, ax, ay)
                        Fill.TO_EDGE -> fadeRect(gfx, x0, y0, x1, y1, c, -ax, -ay)
                    }
                    if (outline) frame(gfx, x0, y0, x1, y1, outlineWidthS.value, outlineColorS.value.faded().rgba)
                }
            }
            Style.EDGE_GLOW -> {
                val d = (minOf(w, h) * glowDepthS.value / 100f).toInt().coerceAtLeast(2)
                val x0 = if (right) cx else 0
                val x1 = if (right) w else cx
                val y0 = if (bottom) cy else 0
                val y1 = if (bottom) h else cy
                if (bottom) fadeRect(gfx, x0, h - d, x1, h, c, 0, 1) else fadeRect(gfx, x0, 0, x1, d, c, 0, -1)
                if (right) fadeRect(gfx, w - d, y0, w, y1, c, 1, 0) else fadeRect(gfx, 0, y0, d, y1, c, -1, 0)
                midX = (x0 + x1) / 2; midY = (y0 + y1) / 2
            }
            Style.BRACKET -> {
                // An L whose corner points into the quadrant, its arms running back toward the crosshair.
                val l = bracketSizeS.value
                val bt = bracketWidthS.value
                val px = cx + sx * (g + l)
                val py = cy + sy * (g + l)
                gfx.fill(minOf(px, px - sx * l), minOf(py, py - sy * bt), maxOf(px, px - sx * l), maxOf(py, py - sy * bt), c.rgba)
                // The second arm starts past the first, so the corner is not drawn twice.
                gfx.fill(minOf(px, px - sx * bt), minOf(py - sy * bt, py - sy * l), maxOf(px, px - sx * bt), maxOf(py - sy * bt, py - sy * l), c.rgba)
                midX = px + sx * 12; midY = py + sy * 12
            }
            Style.LEAP_BOX -> {
                // Odin's box for that player: 24px off the centre, its Render Scale from the near corner.
                val bw = LeapMenu.BOX_WIDTH
                val bh = LeapMenu.BOX_HEIGHT
                val lx = if (right) 0 else -bw
                val ly = if (bottom) 0 else -bh
                val scale = com.engineerclient.misc.OdinLeapScale.get()
                gfx.pose().pushMatrix()
                gfx.pose().translate((cx + sx * 24).toFloat(), (cy + sy * 24).toFloat())
                gfx.pose().scale(scale, scale)
                if (outline) gfx.roundedRectOutlined(lx, ly, lx + bw, ly + bh, c.rgba, outlineColorS.value.faded().rgba, outlineWidthS.value.toFloat(), 9f)
                else gfx.roundedRect(lx, ly, lx + bw, ly + bh, c.rgba, 9f)
                val face = (bh * 0.76).toInt()
                PlayerFaceExtractor.extractRenderState(gfx, t.skin, lx + 9, ly + 9, face, ARGB.color((64 * fade).toInt(), 255, 255, 255))
                gfx.text(t.name, lx + 15 + face, ly + (bh / 2.5).toInt(), c, shadow = false)
                gfx.text(t.extra?.let { "${t.clazz} · $it" } ?: t.clazz, lx + 15 + face, ly + (bh / 1.7).toInt(), c, shadow = false)
                gfx.pose().popMatrix()
                return
            }
        }

        if (!showName) return
        val lines = listOfNotNull(t.name, if (showClassS.value) t.clazz.replaceFirstChar { it.uppercase() } else null, t.extra)
        val s = nameScaleS.value.toFloat()
        val tw = (lines.maxOf { mc.font.width(it) } * s).toInt()
        val th = (lines.size * 10 * s).toInt()
        val (tx, ty) = when (nameAtS.value) {
            NamePos.AREA -> midX - tw / 2 to midY - th / 2
            // Just off the crosshair, on the quadrant's side.
            NamePos.CROSSHAIR -> (if (right) cx + 10 else cx - 10 - tw) to (if (bottom) cy + 10 else cy - 10 - th)
        }
        gfx.pose().pushMatrix()
        gfx.pose().translate(tx.toFloat(), ty.toFloat())
        gfx.pose().scale(s, s)
        val nc = nameColorS.value.faded()
        lines.forEachIndexed { n, l -> gfx.text(l, 0, n * 10, nc, shadow = false) }
        gfx.pose().popMatrix()
    }

    /**
     * A fill that is [color] at the side [ax]/[ay] point to (-1 left/top, 1 right/bottom, 0 the whole way) and
     * fades out across the rect: stacked rects, each a little shorter, so they add up toward that side.
     */
    private fun fadeRect(gfx: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Color, ax: Int, ay: Int) {
        if (ax == 0 && ay == 0) { gfx.fill(x0, y0, x1, y1, color.rgba); return }
        val steps = 24
        // Per layer, so all of them together come to the colour's own opacity.
        val layer = Color(color.rgba, 1f - Math.pow(1.0 - color.alphaFloat, 1.0 / steps).toFloat()).rgba
        for (k in 0 until steps) {
            val f = (steps - k).toFloat() / steps
            val rw = ((x1 - x0) * f).toInt()
            val rh = ((y1 - y0) * f).toInt()
            val rx0 = when (ax) { 1 -> x1 - rw; -1 -> x0; else -> x0 }
            val rx1 = when (ax) { 1 -> x1; -1 -> x0 + rw; else -> x1 }
            val ry0 = when (ay) { 1 -> y1 - rh; -1 -> y0; else -> y0 }
            val ry1 = when (ay) { 1 -> y1; -1 -> y0 + rh; else -> y1 }
            gfx.fill(rx0, ry0, rx1, ry1, layer)
        }
    }

    private fun frame(gfx: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, t: Int, rgba: Int) {
        gfx.fill(x0, y0, x1, y0 + t, rgba)
        gfx.fill(x0, y1 - t, x1, y1, rgba)
        gfx.fill(x0, y0 + t, x0 + t, y1 - t, rgba)
        gfx.fill(x1 - t, y0 + t, x1, y1 - t, rgba)
    }

    companion object {
        fun isLeapScreen(screen: Screen) =
            (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.title?.string.let { it == "Spirit Leap" || it == "Teleport to Player" }
    }
}
