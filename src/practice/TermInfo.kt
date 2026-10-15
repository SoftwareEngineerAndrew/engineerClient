package com.engineerclient.practice

import com.engineerclient.EngineerClient.mc
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
 *  - Coffee Client's Term Info ([drawInfo]): the current section's progress, 3/7 (green once the gate is down), or
 *    in detail its terms, levers, device and gate.
 *  - Timers' Section Time ([sectionTime]): when a section is done, how long it took, in purple, for a few seconds
 *    (server ticks). S1 runs from Goldor's first line, each next one from the last one's end.
 *  - Recore: told when S3 is done by this count ([Recore.s3Done]) and when the sim starts ([Recore.simStart]).
 *  - Coffee Client's Hide Completion Titles ([isCompletionTitle]) and Role Times ([sectionNumber], [deviceDone]).
 *
 * Terminals run from Goldor's first line (or the first task message) to "The Core entrance is
 * opening!". Chat and titles are read straight off the network.
 */
object TermInfo {
    /** The Term Info lines (Coffee Client's Term Info HUD): [simple] just the total. */
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

    /** The section being done (1-4), or null outside P3 (Coffee Client's Role Times). */
    fun sectionNumber(): Int? = current()?.number

    /** Section [n]'s device (1 is Simon Says, 4 is i4) counted done, as Devonian counts it - early ones too (Role Times). */
    fun deviceDone(n: Int): Boolean = sections.getOrNull(n - 1)?.deviceDone == true

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

    private fun reset() {
        for (s in sections) with(s) {
            termsDone = 0; deviceDone = false; leversDone = 0; gateDestroyed = number == 4
            lastIgn = ""; lastIndex = 0; lastType = ""
        }
        active = -1
        coreOpen = false
        TerminalHitboxes.reset()
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
        Recore.simStart(section)
        if (section !in 1..4) return
        for (i in 0 until section - 1) with(sections[i]) { termsDone = terms; leversDone = 2; deviceDone = true; gateDestroyed = true }
        active = section - 1
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

    /** "The Core entrance is opening!" came: terminals are over until the next P3. */
    @Volatile private var coreOpen = false
    val coreOpened: Boolean get() = coreOpen

    /**
     * The section whose terminals answer clicks now (1-4), 0 outside terminals, for Terminal Hitboxes. Measured: a
     * section's terminals open once the section before has its last task and its gate both in, which is when
     * [onTaskChat] moves [active] on. Your own sim: its own section, none during a practice's start timer.
     */
    fun liveSection(): Int {
        if (com.engineerclient.p3sim.P3Sim.localSim) {
            val g = com.engineerclient.p3sim.Fight.phase as? com.engineerclient.p3sim.GoldorPhase ?: return 0
            if (g.isHolding) return 0
            return simSection()?.number ?: 0
        }
        return if (active in 0..3) active + 1 else 0
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
            if (active == 3) Recore.s3Done()
        }
    }

    /**
     * Every inbound packet, first of all mods (TerminalTapMixin, priority 1, read-only), on the network thread.
     * blade-addons' Terminal Timestamps cancels each task line and each section's first gate line in channelRead0
     * ahead of Odin's hook (and posts its own copy into the chat window), so Odin's event never fires for them and
     * the count would stay at S1. Hypixel also sends a completion inside a bundle with its sound and title, which is
     * opened here by hand.
     */
    @JvmStatic
    fun tap(packet: net.minecraft.network.protocol.Packet<*>) {
        com.engineerclient.EngineerClient.safely("term info tap") {
            when (packet) {
                is ClientboundSystemChatPacket -> take(packet)
                is net.minecraft.network.protocol.game.ClientboundOpenScreenPacket -> TerminalHitboxes.opened(packet.title.string)
                is net.minecraft.network.protocol.game.ClientboundBundlePacket -> packet.subPackets().forEach {
                    if (it is ClientboundSystemChatPacket) take(it)
                    else if (it is net.minecraft.network.protocol.game.ClientboundOpenScreenPacket) TerminalHitboxes.opened(it.title.string)
                }
                else -> {}
            }
        }
    }

    /** Chat packets already read, by identity: [tap] and Odin's event can both deliver one. */
    private val seenChat: MutableSet<ClientboundSystemChatPacket> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private val seenChatOrder = ArrayDeque<ClientboundSystemChatPacket>()

    private fun take(p: ClientboundSystemChatPacket) {
        if (p.overlay()) return
        synchronized(seenChat) {
            if (!seenChat.add(p)) return
            seenChatOrder.addLast(p)
            while (seenChatOrder.size > 256) seenChat.remove(seenChatOrder.removeFirst())
        }
        // On the game thread, in the order they came, ahead of the game's own handling of each.
        val msg = p.content().string.replace(CONTROL_CODES, "")
        mc.execute { com.engineerclient.EngineerClient.safely("term info chat") { onChat(msg) } }
    }

    private fun onChat(msg: String) {
        when {
            // A lever can be flicked the tick before Goldor speaks: its message has started
            // terminals already, and starting again here would lose it.
            msg == GOLDOR_START -> if (active == -1) start()
            msg == CORE_OPEN -> { active = -1; coreOpen = true }
            else -> {
                // The first task message starts terminals too (Goldor's line missed).
                if (active == -1 && TASK.matches(msg)) start()
                onTaskChat(msg)
                TerminalHitboxes.chat(msg)
            }
        }
    }

    /** Starts tracking (from client init). */
    fun init() {
        on<LevelEvent.Load> { reset(); shownTime = null }
        on<TickEvent.Server> { serverTicks++ }

        // The second path to [take] ([tap] is the first): only what reaches Odin's hook.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) { take(this) }
        onReceive<net.minecraft.network.protocol.game.ClientboundOpenScreenPacket>(priority = 1000, ignoreCancelled = true) { TerminalHitboxes.opened(title.string) }
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
