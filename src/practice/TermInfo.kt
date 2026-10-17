package com.engineerclient.practice

import com.engineerclient.EngineerClient.mc
import com.engineerclient.splits.CoreLeap
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.util.Locale

/**
 * F7/M7 terminals (P3), ported from Devonian's Terminal Display, Terminal Hide Completion and its terminal section
 * tracking (Stages.TerminalSection). Not a module of its own any more; what it tracks shows in:
 *
 *  - Odin's Terminals Status, Look: ADM ([TerminalsStatusLook], [drawInfo]): the current section's progress, 3/7
 *    (green once the gate is down), or in detail its terms, levers, device and gate.
 *  - Timers' Section Time ([sectionTime]): when a section is done, how long it took, in purple, for a few seconds
 *    (server ticks). S1 runs from Goldor's first line, each next one from the last one's end.
 *  - Sub Splits' Core Leap Highlight ([CoreLeap], [watchingCore]): once S3 is done.
 *  - Coffee Client's Hide Completion Titles ([isCompletionTitle]).
 *
 * Terminals run from Goldor's first line (or the first task message) to "The Core entrance is
 * opening!". Chat and titles are read straight off the network.
 */
object TermInfo {
    /** The Term Info lines, ADM's look for Odin's Terminals Status HUD: [simple] just the total. */
    fun drawInfo(g: GuiGraphicsExtractor, example: Boolean, simple: Boolean): Pair<Int, Int> {
        if (example) return lines(g, if (simple) listOf("§c2/7") else listOf("§eTerms: 3/4", "§aLevers: 2/2", "§aDevice: §l✔", "§cGate: §l✘"))
        val s = current() ?: return 0 to 0
        return lines(g, if (simple) listOf("${if (s.gateDestroyed) "§a" else "§c"}${s.termsDone + s.leversDone + (if (s.deviceDone) 1 else 0)}/${s.terms + 3}")
        else listOf(
            "${colourFor(s.termsDone, s.terms)}Terms: ${s.termsDone}/${s.terms}",
            "${colourFor(s.leversDone, 2)}Levers: ${s.leversDone}/2",
            if (s.deviceDone) "§aDevice: §l✔" else "§cDevice: §l✘",
            if (s.gateDestroyed) "§aGate: §l✔" else "§cGate: §l✘",
        ))
    }

    /** The last section's time while it's up ([seconds] after it's done), else null. None in a P3 Sim practice (its Practice Time HUD is the clock). */
    fun sectionTime(seconds: Double): String? {
        if (com.engineerclient.p3sim.P3Sim.inSim && com.engineerclient.p3sim.Practice.active) return null
        val (text, at) = shownTime ?: return null
        return text.takeIf { System.currentTimeMillis() - at <= seconds * 1000 }
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

    /** S3 done: watching for the first teammate in the core, until Necron or a new world. */
    @Volatile private var coreWatch = false

    /** Core Leap's cue: S3 done (or the sim in S4), until Necron or a new world. */
    fun watchingCore() = coreWatch || simSection()?.number == 4
    /**
     * Sections over, counted for Core Leap as Sub Splits does: a section is over once its last completion
     * (n/n) and its gate are both in, in either order. Doesn't lean on every single term/lever/device
     * message being read the way [active] does, so a missed one doesn't keep Core Leap from showing in S4.
     */
    private var sectionsOver = 0
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


    private fun reset() {
        coreWatch = false
        CoreLeap.reset()
        sectionsOver = 0; lastIn = false; gateIn = false
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
        if (com.engineerclient.p3sim.P3Sim.inSim && !com.engineerclient.p3sim.Practice.active)
            shownTime = "§5${String.format(Locale.ROOT, "%.2f", ticks / 20.0)}" to System.currentTimeMillis()
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

    /** Starts tracking (from client init). */
    fun init() {
        on<LevelEvent.Load> { reset(); shownTime = null }
        on<TickEvent.Server> { serverTicks++ }

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
        EventBus.subscribe(this)
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
