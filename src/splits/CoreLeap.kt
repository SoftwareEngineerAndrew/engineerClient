package com.engineerclient.splits

import com.engineerclient.EngineerClient.mc
import com.engineerclient.practice.TermInfo
import com.engineerclient.splits.DungeonSplits.coreLeap
import com.engineerclient.splits.DungeonSplits.coreDropdown
import com.engineerclient.splits.DungeonSplits.corePreview
import com.engineerclient.splits.DungeonSplits.coreStyle
import com.engineerclient.splits.DungeonSplits.coreColor
import com.engineerclient.splits.DungeonSplits.coreFill
import com.engineerclient.splits.DungeonSplits.coreGap
import com.engineerclient.splits.DungeonSplits.coreGlowDepth
import com.engineerclient.splits.DungeonSplits.coreBracketSize
import com.engineerclient.splits.DungeonSplits.coreBracketWidth
import com.engineerclient.splits.DungeonSplits.coreOutline
import com.engineerclient.splits.DungeonSplits.coreOutlineWidth
import com.engineerclient.splits.DungeonSplits.coreOutlineColor
import com.engineerclient.splits.DungeonSplits.coreName
import com.engineerclient.splits.DungeonSplits.coreClass
import com.engineerclient.splits.DungeonSplits.coreNameAt
import com.engineerclient.splits.DungeonSplits.coreNameScale
import com.engineerclient.splits.DungeonSplits.coreNameColor
import com.engineerclient.splits.DungeonSplits.corePulse
import com.engineerclient.splits.DungeonSplits.corePulseSpeed
import com.engineerclient.splits.DungeonSplits.corePulseLow
import com.engineerclient.splits.DungeonSplits.coreFadeIn
import com.engineerclient.splits.DungeonSplits.coreOverScreens
import com.engineerclient.splits.DungeonSplits.coreOverLeap
import com.engineerclient.splits.DungeonSplits.coreHideInCore
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.roundedRect
import com.odtheking.odin.utils.render.roundedRectOutlined
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.entity.player.Player

internal enum class CoreStyle(private val label: String, val area: Boolean) {
    QUADRANT("Quadrant", true), SIDE("Side", true), VERTICAL("Top / Bottom", true), EDGE_GLOW("Edge Glow", false),
    BRACKET("Corner Bracket", false), LEAP_BOX("Leap Box", false);
    override fun toString() = label
}
internal enum class CoreFill(private val label: String) { SOLID("Solid"), TO_CENTRE("Fade To Crosshair"), TO_EDGE("Fade To Edge"); override fun toString() = label }
internal enum class CoreNamePos(private val label: String) { AREA("Area Middle"), CROSSHAIR("By Crosshair"); override fun toString() = label }

/**
 * Sub Splits' Core Leap Highlight: once S3 is done, the side of the screen where the first teammate inside the core
 * will be in Odin's Leap Menu, in gold, so you're already flicking there when you open the menu. Behind terminals
 * too: only ever drawn as part of the HUD, which every screen draws over, so it never covers or recolours a slot.
 * Its settings are Sub Splits' (the Core* ones); when to watch the core is [TermInfo]'s section tracking.
 */
object CoreLeap {
    /** You've been in the core since Core Leap started watching: it stays off until the next run. */
    private var selfBeenInCore = false

    /** Teammates inside the core box, by name, with when they were first seen there (ms). */
    private val inCoreSince = LinkedHashMap<String, Long>()

    /** DungeonSplits' core box (and P3 Sim's CORE_BOX): reaches down to the core's floor. */
    private fun inCore(p: Player) = p.x >= 39 && p.x < 71 && p.y < 155.5 && p.z >= 54 && p.z < 118

    /**
     * A Spirit Leap drags you to its target over several ticks, and a leap from S2 into S4 goes straight
     * through the core box: anyone moving faster than this per tick is in flight, not in the core. Running
     * is 0.3 a tick, a leap drag many blocks; an etherwarp into the core is one big jump, then still.
     */
    private const val FLIGHT_PER_TICK = 2.0
    private fun inFlight(p: Player): Boolean {
        val dx = p.x - p.xOld; val dy = p.y - p.yOld; val dz = p.z - p.zOld
        return dx * dx + dy * dy + dz * dz > FLIGHT_PER_TICK * FLIGHT_PER_TICK
    }


    /** Keeps [inCoreSince] up to date: teammates in the core box, by when they were first seen there. */
    fun track() {
        if (!DungeonSplits.enabled || !coreLeap || !TermInfo.watchingCore()) { inCoreSince.clear(); return }
        val level = mc.level ?: return
        if (mc.player?.let { inCore(it) && !inFlight(it) } == true) selfBeenInCore = true
        val now = System.currentTimeMillis()
        for (m in DungeonUtils.leapTeammates) {
            if (m.name == "Empty") continue
            val p = m.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == m.name }
            // Unseen (out of render distance) keeps its place; dead or out of the box loses it. Passing
            // through on a leap's drag is neither: not in, and whatever was known stays.
            if (p == null) continue
            if (com.engineerclient.DeadPlayers.isDead(m) || !inCore(p)) inCoreSince.remove(m.name)
            else if (!inFlight(p)) inCoreSince.putIfAbsent(m.name, now)
        }
    }

    private fun isLeapScreen(screen: net.minecraft.client.gui.screens.Screen) =
        (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.title?.string.let { it == "Spirit Leap" || it == "Teleport to Player" }

    /**
     * Core Leap: the part of the screen where the first teammate seen in the core sits in Odin's Leap Menu
     * (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right), so the flick is already on its way when the
     * menu opens. Only ever drawn by the HUD, which renders before any open screen, so a terminal (vanilla or
     * Odin's) always draws over it: it can flash behind one but never covers or recolours a slot.
     */
    fun draw(gfx: GuiGraphicsExtractor) {
        if (!DungeonSplits.enabled || !coreLeap || mc.gui.hud.isHidden) return
        val self = mc.player ?: return
        val screen = mc.gui.screen()
        val preview = corePreview
        if (screen != null) {
            if (!(coreOverScreens || preview)) return
            if (!coreOverLeap && isLeapScreen(screen)) return
        }
        val i: Int
        val name: String
        val clazz: String
        val skin: net.minecraft.world.entity.player.PlayerSkin
        val since: Long
        if (preview) {
            i = 1; name = "Teammate"; clazz = "mage"; skin = self.skin
            since = System.currentTimeMillis() / 3000 * 3000
        } else {
            if (!TermInfo.watchingCore()) return
            if (coreHideInCore && selfBeenInCore) return
            val first = inCoreSince.minByOrNull { it.value } ?: return
            val mates = DungeonUtils.leapTeammates
            i = mates.indexOfFirst { it.name == first.key }.takeIf { it in 0..3 } ?: return
            name = mates[i].name; clazz = mates[i].clazz.name.lowercase(); skin = mates[i].playerSkin ?: self.skin
            since = first.value
        }

        val now = System.currentTimeMillis()
        var fade = 1f
        if (coreFadeIn > 0) fade *= ((now - since) / coreFadeIn.toFloat()).coerceIn(0f, 1f)
        if (corePulse) {
            val t = ((Math.sin(now / 1000.0 * corePulseSpeed * 2 * Math.PI) + 1) / 2).toFloat()
            val low = corePulseLow / 100f
            fade *= low + (1 - low) * t
        }
        if (fade <= 0f) return
        fun Color.faded() = Color(rgba, alphaFloat * fade)
        val color = coreColor.faded()

        val w = mc.window.guiScaledWidth
        val h = mc.window.guiScaledHeight
        val cx = w / 2
        val cy = h / 2
        val right = i % 2 == 1
        val bottom = i / 2 == 1
        val sx = if (right) 1 else -1
        val sy = if (bottom) 1 else -1
        val g = coreGap
        val qx0 = if (right) cx + g else 0
        val qx1 = if (right) w else cx - g
        val qy0 = if (bottom) cy + g else 0
        val qy1 = if (bottom) h else cy - g
        // Where a name in the middle of the area goes.
        var midX = (qx0 + qx1) / 2
        var midY = (qy0 + qy1) / 2

        when (coreStyle) {
            CoreStyle.QUADRANT, CoreStyle.SIDE, CoreStyle.VERTICAL -> {
                val x0 = if (coreStyle == CoreStyle.VERTICAL) 0 else qx0
                val x1 = if (coreStyle == CoreStyle.VERTICAL) w else qx1
                val y0 = if (coreStyle == CoreStyle.SIDE) 0 else qy0
                val y1 = if (coreStyle == CoreStyle.SIDE) h else qy1
                midX = (x0 + x1) / 2; midY = (y0 + y1) / 2
                val ax = if (coreStyle == CoreStyle.VERTICAL) 0 else sx
                val ay = if (coreStyle == CoreStyle.SIDE) 0 else sy
                when (coreFill) {
                    CoreFill.SOLID -> gfx.fill(x0, y0, x1, y1, color.rgba)
                    CoreFill.TO_CENTRE -> fadeRect(gfx, x0, y0, x1, y1, color, ax, ay)
                    CoreFill.TO_EDGE -> fadeRect(gfx, x0, y0, x1, y1, color, -ax, -ay)
                }
                if (coreOutline) frame(gfx, x0, y0, x1, y1, coreOutlineWidth, coreOutlineColor.faded().rgba)
            }
            CoreStyle.EDGE_GLOW -> {
                val d = (minOf(w, h) * coreGlowDepth / 100f).toInt().coerceAtLeast(2)
                val x0 = if (right) cx else 0
                val x1 = if (right) w else cx
                val y0 = if (bottom) cy else 0
                val y1 = if (bottom) h else cy
                if (bottom) fadeRect(gfx, x0, h - d, x1, h, color, 0, 1) else fadeRect(gfx, x0, 0, x1, d, color, 0, -1)
                if (right) fadeRect(gfx, w - d, y0, w, y1, color, 1, 0) else fadeRect(gfx, 0, y0, d, y1, color, -1, 0)
                midX = (x0 + x1) / 2; midY = (y0 + y1) / 2
            }
            CoreStyle.BRACKET -> {
                // An L whose corner points into the quadrant, its arms running back toward the crosshair.
                val l = coreBracketSize
                val t = coreBracketWidth
                val px = cx + sx * (g + l)
                val py = cy + sy * (g + l)
                gfx.fill(minOf(px, px - sx * l), minOf(py, py - sy * t), maxOf(px, px - sx * l), maxOf(py, py - sy * t), color.rgba)
                // The second arm starts past the first, so the corner is not drawn twice.
                gfx.fill(minOf(px, px - sx * t), minOf(py - sy * t, py - sy * l), maxOf(px, px - sx * t), maxOf(py - sy * t, py - sy * l), color.rgba)
                midX = px + sx * 12; midY = py + sy * 12
            }
            CoreStyle.LEAP_BOX -> {
                // Odin's box for that player: 24px off the centre, its Render Scale from the near corner.
                val bw = LeapMenu.BOX_WIDTH
                val bh = LeapMenu.BOX_HEIGHT
                val lx = if (right) 0 else -bw
                val ly = if (bottom) 0 else -bh
                val scale = com.engineerclient.misc.OdinLeapScale.get()
                gfx.pose().pushMatrix()
                gfx.pose().translate((cx + sx * 24).toFloat(), (cy + sy * 24).toFloat())
                gfx.pose().scale(scale, scale)
                if (coreOutline) gfx.roundedRectOutlined(lx, ly, lx + bw, ly + bh, color.rgba, coreOutlineColor.faded().rgba, coreOutlineWidth.toFloat(), 9f)
                else gfx.roundedRect(lx, ly, lx + bw, ly + bh, color.rgba, 9f)
                val face = (bh * 0.76).toInt()
                PlayerFaceExtractor.extractRenderState(gfx, skin, lx + 9, ly + 9, face, ARGB.color((64 * fade).toInt(), 255, 255, 255))
                gfx.text(name, lx + 15 + face, ly + (bh / 2.5).toInt(), color, shadow = false)
                gfx.text(clazz, lx + 15 + face, ly + (bh / 1.7).toInt(), color, shadow = false)
                gfx.pose().popMatrix()
                return
            }
        }

        if (!coreName) return
        val lines = listOfNotNull(name, if (coreClass) clazz.replaceFirstChar { it.uppercase() } else null)
        val s = coreNameScale.toFloat()
        val tw = (lines.maxOf { mc.font.width(it) } * s).toInt()
        val th = (lines.size * 10 * s).toInt()
        val (tx, ty) = when (coreNameAt) {
            CoreNamePos.AREA -> midX - tw / 2 to midY - th / 2
            // Just off the crosshair, on the quadrant's side.
            CoreNamePos.CROSSHAIR -> (if (right) cx + 10 else cx - 10 - tw) to (if (bottom) cy + 10 else cy - 10 - th)
        }
        gfx.pose().pushMatrix()
        gfx.pose().translate(tx.toFloat(), ty.toFloat())
        gfx.pose().scale(s, s)
        val nameColor = coreNameColor.faded()
        lines.forEachIndexed { n, l -> gfx.text(l, 0, n * 10, nameColor, shadow = false) }
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

    /** A new run (world load, or a P3 Sim start). */
    fun reset() {
        inCoreSince.clear()
        selfBeenInCore = false
    }

    /** Draws it on the HUD from now on (Sub Splits being made). */
    fun install() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("engineerclient", "core_leap")) { gfx, _ ->
            // The HUD is drawn under any open screen too, so this is also the behind-the-terminal draw.
            com.engineerclient.EngineerClient.safely("core leap") { track(); draw(gfx) }
        }
    }
}
