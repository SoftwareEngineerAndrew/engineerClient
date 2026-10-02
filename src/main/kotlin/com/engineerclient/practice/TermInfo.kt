package com.engineerclient.practice

import com.engineerclient.recorder.EcRec
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.util.Locale

/**
 * F7/M7 terminals (P3), ported from Devonian's Terminal Display, Terminal Hide Completion and its
 * terminal section tracking (Stages.TerminalSection):
 *
 *  - Term Info HUD: the current section's progress, 3/7 (green once the gate is down), or in
 *    detail its terms, levers, device and gate.
 *  - Hide Completion Titles: the "X activated a terminal! (3/7)" titles, everyone's.
 *  - Section Times: when a section is done, how long it took, in purple, for a few seconds (server ticks). S1 runs
 *    from Goldor's first line, each next one from the last one's end.
 *
 * Terminals run from Goldor's first line (or the first task message) to "The Core entrance is
 * opening!". Chat and titles are read straight off the network.
 */
object TermInfo : Module(
    name = "Term Info",
    category = Category.custom("Engineer Client"),
    description = "F7 terminals: section progress HUD, hides the terminal completion titles, and each section's time when it's done.",
) {
    private val simple by BooleanSetting("Simple Mode", true, desc = "Only the total progress of the section, e.g. 3/7 (green once the gate is down). Off: terms, levers, device and gate on their own lines.")
    private val hideTitles by BooleanSetting("Hide Completion Titles", false, desc = "Hides the \"X activated a terminal! (3/7)\" titles during terminals, and the gate destroyed and core entrance opening titles.")
    private val sectionTimes by BooleanSetting("Section Times", true, desc = "When a section is done, how long it took, in purple. S1 from Goldor's first line, the rest from the last section's end.")
    private val sectionSeconds by NumberSetting("Section Time Seconds", 2.0, 0.5, 10.0, 0.5, desc = "How long a section's time stays up.", unit = "s").withDependency { sectionTimes }

    private val infoHud by HUD("Term Info", "The current terminal section's progress.", true, 10, 80, 1.5f) { example ->
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

    private val timeHud by HUD("Section Time", "The last terminal section's time, for a few seconds after it's done.", true, 200, 120, 2f) { example ->
        if (example) return@HUD lines(this, listOf("§514.35"))
        if (!sectionTimes) return@HUD 0 to 0
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

    private fun current() = sections.getOrNull(active)

    private fun colourFor(n: Int, max: Int) = when (n) { max -> "§a"; 0 -> "§c"; else -> "§e" }

    private fun lines(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        lines.forEachIndexed { i, l -> gfx.text(l, 0, i * 10, Colors.WHITE, shadow = true) }
        return (lines.maxOfOrNull { mc.font.width(it) } ?: 0) to lines.size * 10
    }

    private fun reset() {
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
            val now = System.currentTimeMillis()
            shownTime = "§5${String.format(Locale.ROOT, "%.2f", (serverTicks - sectionStart) / 20.0)}" to now
            sectionStart = serverTicks
            active++
        }
    }

    init {
        on<LevelEvent.Load> { reset(); shownTime = null }
        on<TickEvent.Server> { serverTicks++ }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CONTROL_CODES, "")
            when {
                msg == GOLDOR_START -> start()
                msg == CORE_OPEN -> active = -1
                else -> {
                    // The first task message starts terminals too (Goldor's line missed).
                    if (active == -1 && TASK.matches(msg)) start()
                    onTaskChat(msg)
                }
            }
            recState(msg)
        }

        onReceive<ClientboundSetTitleTextPacket> { if (hidden(text.string)) { it.cancel(); recHid("title", text.string) } }
        onReceive<ClientboundSetSubtitleTextPacket> { if (hidden(text.string)) { it.cancel(); recHid("subtitle", text.string) } }
    }

    /**
     * Every section's counts after a chat line, for the recorder, when they changed. On the network
     * thread, which is the one that changes them.
     */
    private fun recState(msg: String) = EcRec.changed("ec.terms", "terms", { o ->
        o.num("active", active).num("sectionStart", sectionStart)
        o.objs("sections", sections) { s, it ->
            s.num("number", it.number).num("terms", it.terms).num("termsDone", it.termsDone).num("leversDone", it.leversDone)
                .bool("deviceDone", it.deviceDone).bool("gate", it.gateDestroyed).str("lastIgn", it.lastIgn).num("lastIndex", it.lastIndex).str("lastType", it.lastType)
        }
    }, { o -> o.str("msg", msg).num("serverTicks", serverTicks).str("shown", shownTime?.first) })

    private fun recHid(what: String, text: String) = EcRec.line("ec.terms") { o -> o.str("hid", what).str("text", text).num("active", active) }

    /** Read-only, for the Dungeon Recorder's ec.clocks. */
    val recServerTicks: Int get() = serverTicks

    private fun hidden(raw: String): Boolean {
        if (!hideTitles) return false
        val text = raw.replace(CONTROL_CODES, "")
        // The gate and core titles: by their text, whenever (the core one comes as terminals end).
        if (GATE_CORE_TITLE.containsMatchIn(text)) return true
        return active in 0..3 && TITLE.matches(text)
    }
}
