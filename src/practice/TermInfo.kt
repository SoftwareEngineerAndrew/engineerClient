package com.engineerclient.practice

import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.engineerclient.enumSelector
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.darker
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.features.impl.dungeon.LeapMenu
import com.odtheking.odin.utils.render.roundedFill
import com.engineerclient.roundedRect
import com.engineerclient.roundedRectOutlined
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.util.ARGB
import net.minecraft.world.entity.player.Player
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.util.Locale

/**
 * F7/M7 terminals (P3), ported from Devonian's Terminal Display, Terminal Hide Completion and its
 * terminal section tracking (Stages.TerminalSection):
 *
 *  - Term Info HUD: the current section's progress, 3/7 (green once the gate is down), or in
 *    detail its terms, levers, device and gate.
 *  - Numbers 4th/5th: Odin's terminal solver colours only the next 3 Numbers clicks (Order 1-3); this
 *    colours the 4th and 5th too, continuing Odin's fade (NumbersPreviewMixin).
 *  - Section Times: when a section is done, how long it took, in purple, for a few seconds (server ticks). S1 runs
 *    from Goldor's first line, each next one from the last one's end.
 *  - Highlight Levers: a blue box around each P3 lever's hitbox (and the lights device's), in F7/M7 boss.
 *  - Core Leap: once S3 is done, the side of the screen where the first teammate inside the core will be in
 *    Odin's Leap Menu, in gold, so you're already flicking there when you open the menu. Behind terminals too:
 *    only ever drawn as part of the HUD, which every screen draws over, so it never covers or recolours a slot.
 *
 * Terminals run from Goldor's first line (or the first task message) to "The Core entrance is
 * opening!". Chat and titles are read straight off the network.
 */
object TermInfo : Module(
    name = "Term Info",
    category = Category.custom("Engineer Client"),
    description = "F7 terminals: section progress HUD, hides the terminal completion titles, and each section's time when it's done.",
    key = null,
) {
    private val simple by BooleanSetting("Simple Mode", false, desc = "Only the total progress of the section, e.g. 3/7 (green once the gate is down). Off: terms, levers, device and gate on their own lines.")
    private val sectionTimes by BooleanSetting("Section Times", true, desc = "When a section is done, how long it took, in purple. S1 from Goldor's first line, the rest from the last section's end.")
    private val sectionSeconds by NumberSetting("Section Time Seconds", 1.5, 0.5, 10.0, 0.5, desc = "How long a section's time stays up.", unit = "s").withDependency { sectionTimes }
    private val coreLeap by BooleanSetting("Core Leap Highlight", true, desc = "Once S3 is done, the side of the screen where the first teammate inside the core will be in Odin's Leap Menu, in gold, so you flick there as the menu opens. Shown behind terminals too, never over them.")
    private val coreDropdown by DropdownSetting("Core Leap Look", desc = "").withDependency { coreLeap }
    private val corePreview by BooleanSetting("Core Preview", false, desc = "Shows the highlight now, top right, as if someone were in the core (also over this menu), to try the looks below. Turn it off after.").withDependency { coreLeap && coreDropdown }
    private val coreStyleIndex by enumSelector("Core Style", CoreStyle.QUADRANT, desc = "Quadrant: the quarter of the screen with their box. Side: the whole left or right half. Top / Bottom: the whole top or bottom half. Edge Glow: a glow along that quarter's two outer edges. Corner Bracket: an L near the crosshair pointing at it. Leap Box: their leap menu box, as Odin draws it.").withDependency { coreLeap && coreDropdown }
    private val coreStyle: CoreStyle get() = CoreStyle.entries[coreStyleIndex]
    private val coreColor by ColorSetting("Core Color", Color(255, 170, 0, 0.25f), true, desc = "The highlight's colour and opacity.").withDependency { coreLeap && coreDropdown }
    private val coreFillIndex by enumSelector("Core Fill", CoreFill.SOLID, desc = "Solid, or fading out toward the crosshair (strongest at the screen's edge), or the other way round. Quadrant, Side and Top / Bottom.").withDependency { coreLeap && coreDropdown && coreStyle.area }
    private val coreFill: CoreFill get() = CoreFill.entries[coreFillIndex]
    private val coreGap by NumberSetting("Core Centre Gap", 0, 0, 120, 2, desc = "Leaves this much room around the crosshair clear (Odin's boxes start 24 from the centre). Quadrant, Side, Top / Bottom, Corner Bracket.", unit = "px").withDependency { coreLeap && coreDropdown && coreStyle != CoreStyle.LEAP_BOX && coreStyle != CoreStyle.EDGE_GLOW }
    private val coreGlowDepth by NumberSetting("Core Glow Depth", 16, 4, 50, 1, desc = "How far in from the edge the Edge Glow reaches, in % of the screen.", unit = "%").withDependency { coreLeap && coreDropdown && coreStyle == CoreStyle.EDGE_GLOW }
    private val coreBracketSize by NumberSetting("Core Bracket Size", 24, 6, 120, 2, desc = "Length of the Corner Bracket's arms.", unit = "px").withDependency { coreLeap && coreDropdown && coreStyle == CoreStyle.BRACKET }
    private val coreBracketWidth by NumberSetting("Core Bracket Width", 3, 1, 12, 1, desc = "Thickness of the Corner Bracket's arms.", unit = "px").withDependency { coreLeap && coreDropdown && coreStyle == CoreStyle.BRACKET }
    private val coreOutline by BooleanSetting("Core Outline", false, desc = "A line around the highlighted area. Quadrant, Side, Top / Bottom and Leap Box.").withDependency { coreLeap && coreDropdown && (coreStyle.area || coreStyle == CoreStyle.LEAP_BOX) }
    private val coreOutlineWidth by NumberSetting("Core Outline Width", 2, 1, 8, 1, desc = "", unit = "px").withDependency { coreLeap && coreDropdown && coreOutline && (coreStyle.area || coreStyle == CoreStyle.LEAP_BOX) }
    private val coreOutlineColor by ColorSetting("Core Outline Color", Color(255, 170, 0, 0.8f), true, desc = "").withDependency { coreLeap && coreDropdown && coreOutline && (coreStyle.area || coreStyle == CoreStyle.LEAP_BOX) }
    private val coreName by BooleanSetting("Core Show Name", false, desc = "Their name on the highlight.").withDependency { coreLeap && coreDropdown }
    private val coreClass by BooleanSetting("Core Show Class", false, desc = "Their class under the name.").withDependency { coreLeap && coreDropdown && coreName }
    private val coreNameAtIndex by enumSelector("Core Name Position", NamePos.AREA, desc = "In the middle of the highlighted area, or just off the crosshair toward it.").withDependency { coreLeap && coreDropdown && coreName }
    private val coreNameAt: NamePos get() = NamePos.entries[coreNameAtIndex]
    private val coreNameScale by NumberSetting("Core Name Scale", 1.5, 0.5, 4.0, 0.25, desc = "", unit = "x").withDependency { coreLeap && coreDropdown && coreName }
    private val coreNameColor by ColorSetting("Core Name Color", Color(255, 170, 0, 0.8f), true, desc = "").withDependency { coreLeap && coreDropdown && coreName }
    private val corePulse by BooleanSetting("Core Pulse", false, desc = "Breathes between the colour's opacity and a fraction of it.").withDependency { coreLeap && coreDropdown }
    private val corePulseSpeed by NumberSetting("Core Pulse Speed", 1.5, 0.25, 6.0, 0.25, desc = "Pulses a second.", unit = "/s").withDependency { coreLeap && coreDropdown && corePulse }
    private val corePulseLow by NumberSetting("Core Pulse Low", 30, 0, 100, 5, desc = "The faintest point of the pulse, in % of the colour's opacity.", unit = "%").withDependency { coreLeap && coreDropdown && corePulse }
    private val coreFadeIn by NumberSetting("Core Fade In", 0, 0, 1000, 50, desc = "Fades in over this long once someone is in the core.", unit = "ms").withDependency { coreLeap && coreDropdown }
    private val coreOverScreens by BooleanSetting("Core Over Screens", true, desc = "Also shown while a screen is open (terminals, chat, your inventory), in the background behind it: the screen always draws over it, so it never hides or recolours a terminal. Off: only with no screen open.").withDependency { coreLeap && coreDropdown }
    private val coreOverLeap by BooleanSetting("Core Over Leap Menu", false, desc = "Keeps showing once the leap menu is open, behind it.").withDependency { coreLeap && coreDropdown && coreOverScreens }
    private val coreHideInCore by BooleanSetting("Core Hide When In Core", true, desc = "Off for the rest of the run once you've been in the core yourself (after S3), even if you step back out.").withDependency { coreLeap && coreDropdown }

    private enum class CoreStyle(private val label: String, val area: Boolean) {
        QUADRANT("Quadrant", true), SIDE("Side", true), VERTICAL("Top / Bottom", true), EDGE_GLOW("Edge Glow", false),
        BRACKET("Corner Bracket", false), LEAP_BOX("Leap Box", false);
        override fun toString() = label
    }
    private enum class CoreFill(private val label: String) { SOLID("Solid"), TO_CENTRE("Fade To Crosshair"), TO_EDGE("Fade To Edge"); override fun toString() = label }
    private enum class NamePos(private val label: String) { AREA("Area Middle"), CROSSHAIR("By Crosshair"); override fun toString() = label }
    private val highlightLevers by BooleanSetting("Highlight Levers", true, desc = "A blue box around each P3 lever's hitbox in F7/M7 boss (and the P3 Sim Beta), so you can see where to click.")
    private val leverColor by ColorSetting("Lever Color", Color(40, 120, 255, 1f), true, desc = "").withDependency { highlightLevers }
    private val leverFill by BooleanSetting("Lever Fill", false, desc = "Fills the box too, faintly.").withDependency { highlightLevers }
    private val leverWalls by BooleanSetting("Levers Through Walls", true, desc = "Drawn over blocks in the way.").withDependency { highlightLevers }
    private val deviceLevers by BooleanSetting("Lights Device Levers", true, desc = "The 20 levers of S2's lights device too.").withDependency { highlightLevers }
    private val numbersPreview by BooleanSetting("Numbers 4th/5th Preview", false, desc = "Odin's terminal solver shows the next 3 clicks in Numbers; this colours the 4th and 5th too.")
    private val order4 by ColorSetting("Order 4", Colors.MINECRAFT_GREEN.darker(0.5f).darker(0.5f).darker(0.5f), true, desc = "Color of the Numbers solver for the 4th item.").withDependency { numbersPreview }
    private val order5 by ColorSetting("Order 5", Colors.MINECRAFT_GREEN.darker(0.5f).darker(0.5f).darker(0.5f).darker(0.5f), true, desc = "Color of the Numbers solver for the 5th item.").withDependency { numbersPreview }

    /** The colour for the Numbers solution's [index]th click (0-based) from the 4th on, or null to leave Odin's. */
    @JvmStatic
    fun numbersColor(index: Int): Color? = if (!enabled || !numbersPreview) null else when (index) {
        3 -> order4
        4 -> order5
        else -> null
    }

    private val infoHud by HUD("Term Info", "The current terminal section's progress.", true, 633, 416, 2.05f) { example ->
        if (example) return@HUD lines(this, if (simple) listOf("§c2/7") else listOf("§eTerms: 3/4", "§aLevers: 2/2", "§aDevice: §l✔", "§cGate: §l✘"))
        val s = current() ?: return@HUD 0 to 0
        lines(this, if (simple) listOf("${if (s.gateDestroyed) "§a" else "§c"}${s.termsDone + s.leversDone + (if (s.deviceDone) 1 else 0)}/${s.terms + 3}")
        else listOf(
            "${colourFor(s.termsDone, s.terms)}Terms: ${s.termsDone}/${s.terms}",
            "${colourFor(s.leversDone, 2)}Levers: ${s.leversDone}/2",
            if (s.deviceDone) "§aDevice: §l✔" else "§cDevice: §l✘",
            if (s.gateDestroyed) "§aGate: §l✔" else "§cGate: §l✘",
        ))
    }

    private val timeHud by HUD("Section Time", "The last terminal section's time, for a few seconds after it's done.", true, 420, 330, 5f) { example ->
        if (example) return@HUD lines(this, listOf("§514.35"))
        if (!sectionTimes) return@HUD 0 to 0
        // P3 Sim practice: the Practice Time HUD has the screen while it shows a practice's end.
        if (com.engineerclient.p3sim.Practice.endTicks >= 0 && System.currentTimeMillis() - com.engineerclient.p3sim.Practice.endMs < 4000) return@HUD 0 to 0
        val (text, at) = shownTime ?: return@HUD 0 to 0
        if (System.currentTimeMillis() - at > sectionSeconds * 1000) return@HUD 0 to 0
        lines(this, listOf(text))
    }

    private val CONTROL_CODES = Regex("§.")
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPEN = "The Core entrance is opening!"
    private const val GATE = "The gate has been destroyed!"
    private val TASK = Regex("^(\\w+) (?:activated|completed) a (terminal|lever|device)! \\((\\d)/\\d\\)$")
    private val GATE_CORE_TITLE = Regex("gate (?:has been )?destroyed|core entrance is opening", RegexOption.IGNORE_CASE)
    private val TASK_COUNT = Regex("^\\w+ (?:activated|completed) a (?:terminal|lever|device)! \\((\\d+)/(\\d+)\\)$")
    private val TITLE = Regex("^(\\w{1,16}) (?:activated a (?:terminal|lever)|completed a device)! \\(\\d+/\\d+\\)$")

    private class Section(val terms: Int, val number: Int) {
        var termsDone = 0
        var deviceDone = false
        var leversDone = 0
        var gateDestroyed = number == 4
        var lastIgn = ""
        var lastIndex = 0
        var lastType = ""
    }

    private val sections = listOf(Section(4, 1), Section(5, 2), Section(4, 3), Section(4, 4))
    /** The section being done (0-3); -1 outside terminals, 4 once S4 is done. */
    @Volatile private var active = -1
    /** Odin's server ticks, counted here; a section's time is in these, so server lag doesn't count. */
    @Volatile private var serverTicks = 0
    @Volatile private var sectionStart = 0
    @Volatile private var shownTime: Pair<String, Long>? = null

    private fun current() = simSection() ?: if (com.engineerclient.p3sim.P3Sim.localSim) null else sections.getOrNull(active)

    /**
     * P3 Sim: the section in progress straight off the sim, not its chat: a restart, a later section's or a timed start
     * and a practice do part of P3 without chat, and a restart starts again in the same world. Your own sim's only:
     * in someone else's (a party) it goes by the chat, as on Hypixel, from the start the sim sends ([simStart]).
     */
    private fun simSection(): Section? {
        if (!com.engineerclient.p3sim.P3Sim.localSim) return null
        val g = com.engineerclient.p3sim.Fight.phase as? com.engineerclient.p3sim.GoldorPhase ?: return null
        val n = g.section.takeIf { it in 1..4 } ?: return null
        val st = g.stations.filter { it.section == n }
        val kind = com.engineerclient.p3sim.Station.Kind.TERMINAL
        return Section(st.count { it.kind == kind }, n).apply {
            termsDone = st.count { it.kind == kind && it.done }
            leversDone = st.count { it.kind == com.engineerclient.p3sim.Station.Kind.LEVER && it.done }
            deviceDone = st.any { it.kind == com.engineerclient.p3sim.Station.Kind.DEVICE && it.done }
            gateDestroyed = n == 4 || g.gateIsDown(n)
        }
    }

    private fun colourFor(n: Int, max: Int) = when (n) { max -> "§a"; 0 -> "§c"; else -> "§e" }

    private fun lines(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        lines.forEachIndexed { i, l -> gfx.text(l, 0, i * 10, Colors.WHITE, shadow = true) }
        return (lines.maxOfOrNull { mc.font.width(it) } ?: 0) to lines.size * 10
    }

    /** The 8 terminal-section levers (S1-S4), as the P3 Sim places them. */
    private val SECTION_LEVERS = listOf(
        BlockPos(106, 124, 113), BlockPos(94, 124, 113), BlockPos(27, 124, 127), BlockPos(23, 132, 138),
        BlockPos(2, 122, 55), BlockPos(14, 122, 55), BlockPos(84, 121, 34), BlockPos(86, 128, 46),
    )
    /** The lights device's 5x4 levers (x 58-62, y 133-136, z 142). */
    private val DEVICE_LEVERS = (58..62).flatMap { x -> (133..136).map { y -> BlockPos(x, y, 142) } }

    /** Highlight Levers: each lever's own shape (it depends on how the lever faces), where it is a lever. */
    private fun RenderEvent.Extract.drawLevers() {
        if (!enabled || !highlightLevers) return
        if (!com.engineerclient.p3sim.P3Sim.inSim && !(DungeonUtils.inBoss && DungeonUtils.floor?.floorNumber == 7)) return
        val level = mc.level ?: return
        val fill = leverColor.withAlpha(leverColor.alphaFloat * 0.2f)
        for (pos in if (deviceLevers) SECTION_LEVERS + DEVICE_LEVERS else SECTION_LEVERS) {
            val state = level.getBlockState(pos)
            if (state.block !is LeverBlock) continue
            val shape = state.getShape(level, pos)
            if (shape.isEmpty) continue
            val box = shape.bounds().move(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
            if (leverFill) drawFilledBox(box, fill, depth = !leverWalls)
            drawWireFrameBox(box, leverColor, depth = !leverWalls)
        }
    }

    /** S3 done: watching for the first teammate in the core, until Necron or a new world. */
    @Volatile private var coreWatch = false
    /**
     * Sections over, counted for Core Leap as Sub Splits does: a section is over once its last completion
     * (n/n) and its gate are both in, in either order. Doesn't lean on every single term/lever/device
     * message being read the way [active] does, so a missed one doesn't keep Core Leap from showing in S4.
     */
    private var sectionsOver = 0
    /** You've been in the core since Core Leap started watching: it stays off until the next run. */
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

    /** Teammates inside the core box, by name, with when they were first seen there (ms). */
    private val inCoreSince = LinkedHashMap<String, Long>()
    private val CORE_GOLD = Color(255, 170, 0, 0.25f)

    /** DungeonSplits' core box (and P3 Sim's CORE_BOX): reaches down to the core's floor. */
    private fun inCore(p: Player) = p.x >= 39 && p.x < 71 && p.y < 155.5 && p.z >= 54 && p.z < 118

    private fun watchingCore() = coreWatch || simSection()?.number == 4

    /** Keeps [inCoreSince] up to date: teammates in the core box, by when they were first seen there. */
    private fun trackCore() {
        if (!enabled || !coreLeap || !watchingCore()) { inCoreSince.clear(); return }
        val level = mc.level ?: return
        if (mc.player?.let { inCore(it) } == true) selfBeenInCore = true
        val now = System.currentTimeMillis()
        for (m in DungeonUtils.leapTeammates) {
            if (m.name == "Empty") continue
            val p = m.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == m.name }
            // Unseen (out of render distance) keeps its place; dead or out of the box loses it.
            if (p == null) continue
            if (com.engineerclient.DeadPlayers.isDead(m) || !inCore(p)) inCoreSince.remove(m.name)
            else inCoreSince.putIfAbsent(m.name, now)
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
    private fun drawCoreLeap(gfx: GuiGraphicsExtractor) {
        if (!enabled || !coreLeap || mc.options.hideGui) return
        val self = mc.player ?: return
        val screen = mc.screen
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
            if (!watchingCore()) return
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
            NamePos.AREA -> midX - tw / 2 to midY - th / 2
            // Just off the crosshair, on the quadrant's side.
            NamePos.CROSSHAIR -> (if (right) cx + 10 else cx - 10 - tw) to (if (bottom) cy + 10 else cy - 10 - th)
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

    private fun reset() {
        coreWatch = false
        inCoreSince.clear()
        sectionsOver = 0; lastIn = false; gateIn = false
        selfBeenInCore = false
        for (s in sections) with(s) {
            termsDone = 0; deviceDone = false; leversDone = 0; gateDestroyed = number == 4
            lastIgn = ""; lastIndex = 0; lastType = ""
        }
        active = -1
    }

    private fun start() {
        reset()
        active = 0
        sectionStart = serverTicks
    }

    /**
     * The P3 sim starting at section [section] (2-4: the earlier ones done, no Goldor line; 5 the
     * core): counts from that section, as if TermInfo had seen the run so far.
     */
    @JvmStatic
    fun simStart(section: Int) {
        reset()
        shownTime = null
        if (section !in 1..4) return
        for (i in 0 until section - 1) with(sections[i]) { termsDone = terms; leversDone = 2; deviceDone = true; gateDestroyed = true }
        active = section - 1
        sectionsOver = section - 1
        // S1-S3 done: the core's watched from S4's start (as simSection() has it on the host's game).
        coreWatch = section == 4
        sectionStart = serverTicks
    }

    /** P3 Sim: takes down the section time on screen (a practice restarting). */
    @JvmStatic
    fun simClearTime() { shownTime = null }

    /**
     * P3 Sim: a section's time as the sim measured it ([ticks], server ticks), shown as Section Time. Term Info
     * can't follow the sim by its chat: restarts, later starts and practices do part of P3 without it.
     */
    @JvmStatic
    fun simSectionTime(ticks: Int) {
        if (com.engineerclient.p3sim.P3Sim.inSim) shownTime = "§5${String.format(Locale.ROOT, "%.2f", ticks / 20.0)}" to System.currentTimeMillis()
    }

    /** Devonian's TerminalSection.onChat, for the active section only. */
    private fun onTaskChat(msg: String) {
        val cur = current() ?: return
        if (msg == GATE) cur.gateDestroyed = true
        else {
            val match = TASK.matchEntire(msg) ?: return
            val ign = match.groupValues[1]
            val type = match.groupValues[2]
            val index = match.groupValues[3].toIntOrNull() ?: return
            if (index == cur.lastIndex) {
                if (ign == cur.lastIgn) return
                // Same count from someone else: a device done in a later section (its message can
                // come while this one is still going).
                if (type == "device") {
                    when (cur.number) {
                        1 -> if (!sections[3].deviceDone) sections[3].deviceDone = true
                            else if (!sections[1].deviceDone) sections[1].deviceDone = true
                            else sections[2].deviceDone = true
                        2 -> if (!sections[2].deviceDone) sections[2].deviceDone = true
                            else sections[3].deviceDone = true
                        3 -> sections[3].deviceDone = true
                    }
                    return
                } else if (cur.lastType == "device") cur.deviceDone = false
            } else if (index == 2 && cur.lastIndex == 0) {
                cur.deviceDone = true
            } else if (index == 1 && type != "device") {
                cur.deviceDone = false
            }

            when (type) {
                "terminal" -> cur.termsDone++
                "lever" -> cur.leversDone++
                "device" -> {
                    if (cur.deviceDone && cur.number == 2) sections[3].deviceDone = true
                    cur.deviceDone = true
                }
            }
            cur.lastIgn = ign; cur.lastIndex = index; cur.lastType = type
        }

        if (cur.termsDone >= cur.terms && cur.leversDone >= 2 && cur.deviceDone && cur.gateDestroyed) {
            // In P3 Sim the sim times the sections itself ([simSectionTime]).
            if (!com.engineerclient.p3sim.P3Sim.inSim) shownTime = "§5${String.format(Locale.ROOT, "%.2f", (serverTicks - sectionStart) / 20.0)}" to System.currentTimeMillis()
            sectionStart = serverTicks
            active++
            if (active == 3) coreWatch = true
        }
    }

    init {
        // Shown by default (Odin starts a toggleable HUD hidden); a saved config still decides.
        infoHud.enabled = true; timeHud.enabled = true
        on<LevelEvent.Load> { reset(); shownTime = null }
        on<TickEvent.Server> { serverTicks++ }
        on<RenderEvent.Extract> { com.engineerclient.EngineerClient.safely("highlight levers") { drawLevers() } }
        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, Identifier.fromNamespaceAndPath("engineerclient", "core_leap")) { gfx, _ ->
            // The HUD is drawn under any open screen too, so this is also the behind-the-terminal draw.
            com.engineerclient.EngineerClient.safely("core leap") { trackCore(); drawCoreLeap(gfx) }
        }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            when {
                // A lever can be flicked the tick before Goldor speaks: its message has started
                // terminals already, and starting again here would lose it.
                msg == GOLDOR_START -> if (active == -1) start()
                msg == CORE_OPEN -> { active = -1; coreWatch = true }
                msg.startsWith("[BOSS] Necron:") -> coreWatch = false
                else -> {
                    // The first task message starts terminals too (Goldor's line missed).
                    if (active == -1 && TASK.matches(msg)) start()
                    onTaskChat(msg)
                    if (active != -1) countSectionEnd(msg)
                }
            }
        }

    }




    /**
     * Whether a title or subtitle is a terminal one: "X activated a terminal! (3/7)" and the like while
     * terminals run (or in the P3 sim), the gate destroyed and core entrance opening ones whenever. For
     * Coffee Client's Hide Completion Titles.
     */
    fun isCompletionTitle(raw: String): Boolean {
        val text = raw.replace(CONTROL_CODES, "")
        // The gate and core titles: by their text, whenever (the core one comes as terminals end).
        if (GATE_CORE_TITLE.containsMatchIn(text)) return true
        return (active in 0..3 || simSection() != null) && TITLE.matches(text)
    }
}
