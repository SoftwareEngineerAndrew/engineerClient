package com.engineerclient.practice

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
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
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.entity.Entity

/**
 * F7/M7 core leap (was Term Info's Core Leap, then Sub Splits'). Paste Last In Core is set here and sent by
 * DungeonSplits, whose Goldor tracking times everyone into the core (it runs for this with Sub Splits off). Core Leap Highlight: once S3 is done, the side of the screen where the first teammate
 * inside the core will be in Odin's Leap Menu, in gold, so you're already flicking there when you open the menu.
 * Highlight All Players In Core: every teammate who got to the core before you, each in their own part of the
 * screen. Behind terminals too: only ever drawn as part of the HUD, which every screen draws over, so it never
 * covers or recolours a slot.
 */
object Recore : Module(
    name = "Recore",
    category = Category.custom("ADS", 860, 10),
    description = "The core after S4: the Core Leap Highlight (where the teammates already in the core are in Odin's Leap Menu, so you flick there as it opens) and Paste Last In Core.",
    key = null,
) {
    val pasteLast by BooleanSetting("Paste Last In Core", true, desc = "Says in party chat who was last into the core after S4 and their time (e.g. \"Last in core: Name 1.35s\"), once all five are in. Only when every time is exact (each teammate in view just before they came in) and nobody has died.")
    private val coreLeap by BooleanSetting("Core Leap Highlight", true, desc = "Once S3 is done, the side of the screen where the first teammate inside the core will be in Odin's Leap Menu, in gold, so you flick there as the menu opens. Shown behind terminals too, never over them.")
    private val allInCore by BooleanSetting("Highlight All Players In Core", true, desc = "Highlights every teammate who made it to the core before you, each where they'll be in the leap menu, not just the first one. All of them go once you're in the core yourself (with Hide When In Core).").withDependency { coreLeap }
    private val lookDropdown by DropdownSetting("Core Leap Look", desc = "").withDependency { coreLeap }
    /** The look settings show: Core Leap Highlight on and its dropdown open. */
    private val look get() = coreLeap && lookDropdown
    private val preview by BooleanSetting("Preview", false, desc = "Shows the highlight now, top right, as if someone were in the core (also over this menu), to try the looks below; with Highlight All Players In Core, bottom left too. Turn it off after.").withDependency { look }
    private val style by SelectorSetting("Style", Style.EDGE_GLOW, desc = "Quadrant: the quarter of the screen with their box. Side: the whole left or right half. Top / Bottom: the whole top or bottom half. Edge Glow: a glow along that quarter's two outer edges. Corner Bracket: an L near the crosshair pointing at it. Leap Box: their leap menu box, as Odin draws it.").withDependency { look }
    private val color by ColorSetting("Color", Color(255, 170, 0, 121 / 255f), true, desc = "The highlight's colour and opacity.").withDependency { look }
    private val fill by SelectorSetting("Fill", Fill.SOLID, desc = "Solid, or fading out toward the crosshair (strongest at the screen's edge), or the other way round. Quadrant, Side and Top / Bottom.").withDependency { look && (style.area) }
    private val gap by NumberSetting("Centre Gap", 0, 0..120, 2, desc = "Leaves this much room around the crosshair clear (Odin's boxes start 24 from the centre). Quadrant, Side, Top / Bottom, Corner Bracket.", unit = "px").withDependency { look && (style != Style.LEAP_BOX && style != Style.EDGE_GLOW) }
    private val glowDepth by NumberSetting("Glow Depth", 11, 4..50, 1, desc = "How far in from the edge the Edge Glow reaches, in % of the screen.", unit = "%").withDependency { look && (style == Style.EDGE_GLOW) }
    private val bracketSize by NumberSetting("Bracket Size", 24, 6..120, 2, desc = "Length of the Corner Bracket's arms.", unit = "px").withDependency { look && (style == Style.BRACKET) }
    private val bracketWidth by NumberSetting("Bracket Width", 3, 1..12, 1, desc = "Thickness of the Corner Bracket's arms.", unit = "px").withDependency { look && (style == Style.BRACKET) }
    private val outline by BooleanSetting("Outline", false, desc = "A line around the highlighted area. Quadrant, Side, Top / Bottom and Leap Box.").withDependency { look && (style.area || style == Style.LEAP_BOX) }
    private val outlineWidth by NumberSetting("Outline Width", 2, 1..8, 1, desc = "", unit = "px").withDependency { look && (outline && (style.area || style == Style.LEAP_BOX)) }
    private val outlineColor by ColorSetting("Outline Color", Color(255, 170, 0, 0.8f), true, desc = "").withDependency { look && (outline && (style.area || style == Style.LEAP_BOX)) }
    private val showName by BooleanSetting("Show Name", false, desc = "Their name on the highlight.").withDependency { look }
    private val showClass by BooleanSetting("Show Class", false, desc = "Their class under the name.").withDependency { look && showName }
    private val nameAt by SelectorSetting("Name Position", NamePos.AREA, desc = "In the middle of the highlighted area, or just off the crosshair toward it.").withDependency { look && showName }
    private val nameScale by NumberSetting("Name Scale", 1.5, 0.5..4.0, 0.25, desc = "", unit = "x").withDependency { look && showName }
    private val nameColor by ColorSetting("Name Color", Color(255, 170, 0, 0.8f), true, desc = "").withDependency { look && showName }
    private val pulse by BooleanSetting("Pulse", false, desc = "Breathes between the colour's opacity and a fraction of it.").withDependency { look }
    private val pulseSpeed by NumberSetting("Pulse Speed", 1.5, 0.25..6.0, 0.25, desc = "Pulses a second.", unit = "/s").withDependency { look && pulse }
    private val pulseLow by NumberSetting("Pulse Low", 30, 0..100, 5, desc = "The faintest point of the pulse, in % of the colour's opacity.", unit = "%").withDependency { look && pulse }
    private val fadeIn by NumberSetting("Fade In", 0, 0..1000, 50, desc = "Fades in over this long once someone is in the core.", unit = "ms").withDependency { look }
    private val overScreens by BooleanSetting("Over Screens", true, desc = "Also shown while a screen is open (terminals, chat, your inventory), in the background behind it: the screen always draws over it, so it never hides or recolours a terminal. Off: only with no screen open.").withDependency { look }
    private val overLeap by BooleanSetting("Over Leap Menu", true, desc = "Keeps showing once the leap menu is open, behind it.").withDependency { look && overScreens }
    private val hideInCore by BooleanSetting("Hide When In Core", true, desc = "Off for the rest of the run once you've been in the core yourself (after S3), even if you step back out.").withDependency { look }

    private enum class Style(private val label: String, val area: Boolean) {
        QUADRANT("Quadrant", true), SIDE("Side", true), VERTICAL("Top / Bottom", true), EDGE_GLOW("Edge Glow", false),
        BRACKET("Corner Bracket", false), LEAP_BOX("Leap Box", false);
        override fun toString() = label
    }
    private enum class Fill(private val label: String) { SOLID("Solid"), TO_CENTRE("Fade To Crosshair"), TO_EDGE("Fade To Edge"); override fun toString() = label }
    private enum class NamePos(private val label: String) { AREA("Area Middle"), CROSSHAIR("By Crosshair"); override fun toString() = label }

    private val CONTROL_CODES = Regex("§.")
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPEN = "The Core entrance is opening!"
    private const val GATE = "The gate has been destroyed!"
    private val TASK_COUNT = Regex("^\\w+ (?:activated|completed) a (?:terminal|lever|device)! \\((\\d+)/(\\d+)\\)$")

    /** Terminals going (Goldor's first line or the first task message, to the core opening). */
    private var terms = false
    /** S3 done: watching for teammates in the core, until Necron or a new world. */
    @Volatile private var coreWatch = false
    /**
     * Sections over, counted as Sub Splits does: a section is over once its last completion (n/n) and its gate
     * are both in, in either order. Doesn't lean on every single term/lever/device message being read the way
     * Term Info's section count does, so a missed one doesn't keep this from showing in S4.
     */
    private var sectionsOver = 0
    /** You've been in the core since watching started: it stays off until the next run. */
    private var selfBeenInCore = false
    private var lastIn = false
    private var gateIn = false

    private fun countSectionEnd(msg: String) {
        if (sectionsOver >= 3) return
        if (msg == GATE) gateIn = true
        else {
            val m = TASK_COUNT.matchEntire(msg) ?: return
            if (m.groupValues[1] != m.groupValues[2]) return
            lastIn = true
        }
        if (!lastIn || !gateIn) return
        sectionsOver++; lastIn = false; gateIn = false
        if (sectionsOver == 3) coreWatch = true
    }

    /** Term Info's own count got S3 done (it reads every task message): the core's watched from now. */
    fun s3Done() { if (enabled) coreWatch = true }

    /**
     * The P3 sim starting at section [section] (2-4: the earlier ones done, no Goldor line; 5 the core):
     * counts from that section, as if this had seen the run so far.
     */
    @JvmStatic
    fun simStart(section: Int) {
        reset()
        if (section !in 1..4) return
        terms = true
        sectionsOver = section - 1
        // S1-S3 done: the core's watched from S4's start (as simS4() has it on the host's game).
        coreWatch = section == 4
    }

    /** Teammates inside the core box, by name, with when they were first seen there (ms). */
    private val inCoreSince = LinkedHashMap<String, Long>()

    /** DungeonSplits' core box (and P3 Sim's CORE_BOX): reaches down to the core's floor. */
    private fun inCore(p: Entity) = p.x >= 39 && p.x < 71 && p.y < 155.5 && p.z >= 54 && p.z < 118

    /**
     * A Spirit Leap drags you to its target over several ticks, and a leap from S2 into S4 goes straight
     * through the core box: anyone moving faster than this per tick is in flight, not in the core. Running
     * is 0.3 a tick, a leap drag many blocks; an etherwarp into the core is one big jump, then still.
     */
    private const val FLIGHT_PER_TICK = 2.0
    private fun inFlight(p: Entity): Boolean {
        val dx = p.x - p.xOld; val dy = p.y - p.yOld; val dz = p.z - p.zOld
        return dx * dx + dy * dy + dz * dz > FLIGHT_PER_TICK * FLIGHT_PER_TICK
    }

    /** Your own P3 sim in S4 (straight off the sim, as Term Info has it: a restart or later start has no chat). */
    private fun simS4(): Boolean {
        if (!com.engineerclient.p3sim.P3Sim.localSim) return false
        return (com.engineerclient.p3sim.Fight.phase as? com.engineerclient.p3sim.GoldorPhase)?.section == 4
    }

    private fun watchingCore() = coreWatch || simS4()

    /** Keeps [inCoreSince] up to date: teammates in the core box, by when they were first seen there. */
    private fun trackCore() {
        if (!enabled || !coreLeap || !watchingCore()) { inCoreSince.clear(); return }
        val level = mc.level ?: return
        if (mc.player?.let { inCore(it) && !inFlight(it) } == true) selfBeenInCore = true
        val now = System.currentTimeMillis()
        for (m in DungeonUtils.leapTeammates) {
            if (m.name == "Empty") continue
            val p = m.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == m.name } ?: simBot(level, m.name)
            // Unseen (out of render distance) keeps its place; dead or out of the box loses it. Passing
            // through on a leap's drag is neither: not in, and whatever was known stays.
            if (p == null) continue
            if (com.engineerclient.DeadPlayers.isDead(m) || !inCore(p)) inCoreSince.remove(m.name)
            else if (!inFlight(p)) inCoreSince.putIfAbsent(m.name, now)
        }
    }

    /** In the P3 sim, a bot teammate: its Mannequin, by the name over it. */
    private fun simBot(level: net.minecraft.client.multiplayer.ClientLevel, name: String): Entity? {
        if (!com.engineerclient.p3sim.P3Sim.inSim) return null
        return level.entitiesForRendering().firstOrNull { it is net.minecraft.world.entity.decoration.Mannequin && !it.isRemoved && it.customName?.string?.replace(CONTROL_CODES, "") == name }
    }

    private fun isLeapScreen(screen: net.minecraft.client.gui.screens.Screen) =
        (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.title?.string.let { it == "Spirit Leap" || it == "Teleport to Player" }

    /** Someone to highlight: [i] is their place in Odin's Leap Menu, [since] when they were first seen in the core. */
    private class Target(val i: Int, val name: String, val clazz: String, val skin: net.minecraft.world.entity.player.PlayerSkin, val since: Long)

    /**
     * The part of the screen where the first teammate seen in the core (or each one, with Highlight All Players
     * In Core) sits in Odin's Leap Menu (0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right), so the flick is
     * already on its way when the menu opens. Only ever drawn by the HUD, which renders before any open screen,
     * so a terminal (vanilla or Odin's) always draws over it: it can flash behind one but never covers or
     * recolours a slot.
     */
    private fun drawRecore(gfx: GuiGraphicsExtractor) {
        if (!enabled || !coreLeap || mc.gui.hud.isHidden) return
        val self = mc.player ?: return
        val screen = mc.gui.screen()
        if (screen != null) {
            if (!(overScreens || preview)) return
            if (!overLeap && isLeapScreen(screen)) return
        }
        val targets = if (preview) {
            val since = System.currentTimeMillis() / 3000 * 3000
            listOfNotNull(
                Target(1, "Teammate", "mage", self.skin, since),
                if (allInCore) Target(2, "Teammate", "archer", self.skin, since) else null,
            )
        } else {
            if (!watchingCore()) return
            if (hideInCore && selfBeenInCore) return
            val mates = DungeonUtils.leapTeammates
            // Earliest first: a Side or Top / Bottom half two of them share is drawn once, as the first one's.
            val inOrder = inCoreSince.entries.sortedBy { it.value }
            (if (allInCore) inOrder else inOrder.take(1)).mapNotNull { (n, since) ->
                val i = mates.indexOfFirst { it.name == n }.takeIf { it in 0..3 } ?: return@mapNotNull null
                Target(i, mates[i].name, mates[i].clazz.name.lowercase(), mates[i].playerSkin ?: self.skin, since)
            }
        }
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
        if (fadeIn > 0) fade *= ((now - t.since) / fadeIn.toFloat()).coerceIn(0f, 1f)
        if (pulse) {
            val p = ((Math.sin(now / 1000.0 * pulseSpeed * 2 * Math.PI) + 1) / 2).toFloat()
            val low = pulseLow / 100f
            fade *= low + (1 - low) * p
        }
        if (fade <= 0f) return
        fun Color.faded() = Color(rgba, alphaFloat * fade)
        val c = color.faded()

        val i = t.i
        val w = mc.window.guiScaledWidth
        val h = mc.window.guiScaledHeight
        val cx = w / 2
        val cy = h / 2
        val right = i % 2 == 1
        val bottom = i / 2 == 1
        val sx = if (right) 1 else -1
        val sy = if (bottom) 1 else -1
        val g = gap
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
                    when (fill) {
                        Fill.SOLID -> gfx.fill(x0, y0, x1, y1, c.rgba)
                        Fill.TO_CENTRE -> fadeRect(gfx, x0, y0, x1, y1, c, ax, ay)
                        Fill.TO_EDGE -> fadeRect(gfx, x0, y0, x1, y1, c, -ax, -ay)
                    }
                    if (outline) frame(gfx, x0, y0, x1, y1, outlineWidth, outlineColor.faded().rgba)
                }
            }
            Style.EDGE_GLOW -> {
                val d = (minOf(w, h) * glowDepth / 100f).toInt().coerceAtLeast(2)
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
                val l = bracketSize
                val bt = bracketWidth
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
                if (outline) gfx.roundedRectOutlined(lx, ly, lx + bw, ly + bh, c.rgba, outlineColor.faded().rgba, outlineWidth.toFloat(), 9f)
                else gfx.roundedRect(lx, ly, lx + bw, ly + bh, c.rgba, 9f)
                val face = (bh * 0.76).toInt()
                PlayerFaceExtractor.extractRenderState(gfx, t.skin, lx + 9, ly + 9, face, ARGB.color((64 * fade).toInt(), 255, 255, 255))
                gfx.text(t.name, lx + 15 + face, ly + (bh / 2.5).toInt(), c, shadow = false)
                gfx.text(t.clazz, lx + 15 + face, ly + (bh / 1.7).toInt(), c, shadow = false)
                gfx.pose().popMatrix()
                return
            }
        }

        if (!showName) return
        val lines = listOfNotNull(t.name, if (showClass) t.clazz.replaceFirstChar { it.uppercase() } else null)
        val s = nameScale.toFloat()
        val tw = (lines.maxOf { mc.font.width(it) } * s).toInt()
        val th = (lines.size * 10 * s).toInt()
        val (tx, ty) = when (nameAt) {
            NamePos.AREA -> midX - tw / 2 to midY - th / 2
            // Just off the crosshair, on the quadrant's side.
            NamePos.CROSSHAIR -> (if (right) cx + 10 else cx - 10 - tw) to (if (bottom) cy + 10 else cy - 10 - th)
        }
        gfx.pose().pushMatrix()
        gfx.pose().translate(tx.toFloat(), ty.toFloat())
        gfx.pose().scale(s, s)
        val nc = nameColor.faded()
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

    private fun reset() {
        terms = false
        coreWatch = false
        inCoreSince.clear()
        sectionsOver = 0; lastIn = false; gateIn = false
        selfBeenInCore = false
    }

    override fun onEnable() {
        // Whatever was half-seen while off is stale.
        reset()
        super.onEnable()
    }

    init {
        on<LevelEvent.Load> { reset() }
        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("engineerclient", "recore")) { gfx, _ ->
            // The HUD is drawn under any open screen too, so this is also the behind-the-terminal draw.
            com.engineerclient.EngineerClient.safely("recore") { trackCore(); drawRecore(gfx) }
        }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            when {
                // A lever can be flicked the tick before Goldor speaks: its message has started terminals already.
                msg == GOLDOR_START -> if (!terms) { reset(); terms = true }
                msg == CORE_OPEN -> { terms = false; coreWatch = true }
                msg.startsWith("[BOSS] Necron:") -> coreWatch = false
                else -> {
                    // The first task message starts terminals too (Goldor's line missed).
                    if (!terms && TASK_COUNT.matches(msg)) { reset(); terms = true }
                    if (terms) countSectionEnd(msg)
                }
            }
        }
    }
}
