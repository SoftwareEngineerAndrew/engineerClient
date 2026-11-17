package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.adm.events.EventDispatcher
import com.adm.events.LevelEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.engineerclient.p3sim.Fight
import com.engineerclient.p3sim.GoldorPhase
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.Practice
import com.engineerclient.p3sim.Station
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.util.Locale

/**
 * P3 terminal sections from Hypixel's chat: which one is live and what's done in it, for Section Time, Term Progress,
 * Terminals Status, Recore and Terminal Hitboxes.
 */
object TermInfo {
    private const val GOLDOR_LINE = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_LINE = "The Core entrance is opening!"
    private const val GATE_LINE = "The gate has been destroyed!"

    private const val LINE_GOLDOR = -1
    private const val LINE_CORE = -2
    private const val LINE_GATE = -3

    // A task line's code: its kind in the low bits, then flags.
    private const val KIND_TERMINAL = 1
    private const val KIND_LEVER = 2
    private const val KIND_DEVICE = 3
    private const val KIND_MASK = 3
    private const val FLAG_COUNTED = 4
    private const val FLAG_OWN_TERMINAL = 8

    private class Part(val number: Int, val terminals: Int) {
        var terminalsIn = 0
        var leversIn = 0
        var deviceIn = false
        var gateDown = number == 4
        var prevName = ""
        var prevCount = 0
        var prevKind = 0

        fun clear() {
            terminalsIn = 0; leversIn = 0; deviceIn = false; gateDown = number == 4
            prevName = ""; prevCount = 0; prevKind = 0
        }

        fun complete() = terminalsIn >= terminals && leversIn >= 2 && deviceIn && gateDown
    }

    private class Shown(val text: String, val at: Long)

    private val parts = arrayOf(Part(1, 4), Part(2, 5), Part(3, 4), Part(4, 4))

    // A device line repeating the count belongs to a later section; these are the ones it can be, in order.
    private val laterDevice = arrayOf(intArrayOf(3, 1, 2), intArrayOf(2, 3), intArrayOf(3))

    @Volatile private var live = -1
    @Volatile private var serverTicks = 0
    @Volatile private var partStart = 0
    @Volatile private var shown: Shown? = null
    @Volatile private var coreOpen = false

    val coreOpened: Boolean get() = coreOpen

    val sectionDoneAt: Long get() = shown?.at ?: 0L

    fun sectionTime(seconds: Double): String? {
        if (P3Sim.inSim && Practice.active) return null
        val s = shown ?: return null
        return if (System.currentTimeMillis() - s.at <= seconds * 1000) s.text else null
    }

    fun sectionNumber(): Int? = livePart()?.number

    fun sectionProgress(): Pair<Int, Int>? {
        val p = livePart() ?: return null
        return p.terminalsIn + p.leversIn + (if (p.deviceIn) 1 else 0) to p.terminals + 3
    }

    fun deviceDone(n: Int): Boolean = n in 1..4 && parts[n - 1].deviceIn

    fun liveSection(): Int {
        if (!P3Sim.localSim) return if (live in 0..3) live + 1 else 0
        val g = Fight.phase as? GoldorPhase ?: return 0
        if (g.isHolding) return 0
        return simPart()?.number ?: 0
    }

    private fun livePart(): Part? {
        if (P3Sim.localSim) return simPart()
        val i = live
        return if (i in 0..3) parts[i] else null
    }

    // The local sim restarts and skips sections without chat, so its own state is read instead.
    private fun simPart(): Part? {
        val g = Fight.phase as? GoldorPhase ?: return null
        val n = g.section
        if (n !in 1..4) return null
        var terms = 0; var termsDone = 0; var levers = 0; var device = false
        for (st in g.stations) {
            if (st.section != n) continue
            when (st.kind) {
                Station.Kind.TERMINAL -> { terms++; if (st.done) termsDone++ }
                Station.Kind.LEVER -> if (st.done) levers++
                Station.Kind.DEVICE -> if (st.done) device = true
            }
        }
        return Part(n, terms).also {
            it.terminalsIn = termsDone; it.leversIn = levers; it.deviceIn = device
            it.gateDown = n == 4 || g.gateIsDown(n)
        }
    }

    private fun clearAll() {
        for (p in parts) p.clear()
        live = -1
        coreOpen = false
        TerminalHitboxes.reset()
    }

    private fun begin() {
        clearAll()
        live = 0
        partStart = serverTicks
    }

    @JvmStatic
    fun simStart(section: Int) {
        clearAll()
        shown = null
        Recore.simStart(section)
        if (section !in 1..4) return
        for (i in 0 until section - 1) {
            val p = parts[i]
            p.terminalsIn = p.terminals; p.leversIn = 2; p.deviceIn = true; p.gateDown = true
        }
        live = section - 1
        partStart = serverTicks
    }

    @JvmStatic
    fun simClearTime() { shown = null }

    @JvmStatic
    fun simSectionTime(ticks: Int) {
        if (P3Sim.inSim && !Practice.active) shown = Shown(timeText(ticks), System.currentTimeMillis())
    }

    private fun timeText(ticks: Int) = "§5" + String.format(Locale.ROOT, "%.2f", ticks / 20.0)

    private fun isDigit(c: Char) = c in '0'..'9'

    private fun isNameChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || isDigit(c) || c == '_'

    /** "<name> activated|completed a terminal|lever|device! (n/m)" as a task code, 0 for anything else. */
    private fun taskCode(s: String): Int {
        val end = s.length - 1
        var i = end - 1
        while (i > 0 && isDigit(s[i])) i--
        val totalDigits = end - 1 - i
        if (totalDigits == 0 || s[i] != '/') return 0
        val slash = i--
        while (i > 0 && isDigit(s[i])) i--
        val countDigits = slash - 1 - i
        if (countDigits == 0 || s[i] != '(' || i < 3 || s[i - 1] != ' ' || s[i - 2] != '!') return 0
        val kindEnd = i - 2

        val space = s.indexOf(' ')
        if (space <= 0) return 0
        for (c in 0 until space) if (!isNameChar(s[c])) return 0
        val activated = s.startsWith("activated a ", space + 1)
        if (!activated && !s.startsWith("completed a ", space + 1)) return 0
        val kindStart = space + 13
        val kind = when (kindEnd - kindStart) {
            8 -> if (s.startsWith("terminal", kindStart)) KIND_TERMINAL else 0
            5 -> if (s.startsWith("lever", kindStart)) KIND_LEVER else 0
            6 -> if (s.startsWith("device", kindStart)) KIND_DEVICE else 0
            else -> 0
        }
        if (kind == 0) return 0
        var code = 0
        if (countDigits == 1 && totalDigits == 1) code = kind or FLAG_COUNTED
        if (activated && kind == KIND_TERMINAL && space <= 16) code = code or FLAG_OWN_TERMINAL
        return code
    }

    private fun lineCode(s: String): Int {
        if (s.isEmpty()) return 0
        return when (s[s.length - 1]) {
            ')' -> taskCode(s)
            '?' -> if (s == GOLDOR_LINE) LINE_GOLDOR else 0
            '!' -> if (s == CORE_LINE) LINE_CORE else if (s == GATE_LINE) LINE_GATE else 0
            else -> 0
        }
    }

    private fun onLine(s: String, code: Int) {
        when (code) {
            // A lever can come the tick before Goldor speaks and has started terminals already.
            LINE_GOLDOR -> if (live == -1) begin()
            LINE_CORE -> { live = -1; coreOpen = true }
            LINE_GATE -> livePart()?.let { it.gateDown = true; checkDone(it) }
            else -> {
                if (code and FLAG_COUNTED != 0) {
                    if (live == -1) begin()
                    livePart()?.let { task(it, s, code and KIND_MASK) }
                }
                if (code and FLAG_OWN_TERMINAL != 0) TerminalHitboxes.terminalActivated(s.substring(0, s.indexOf(' ')))
            }
        }
    }

    private fun task(p: Part, s: String, kind: Int) {
        val name = s.substring(0, s.indexOf(' '))
        val count = s[s.length - 4] - '0'
        if (count == p.prevCount) {
            if (name == p.prevName) return
            if (kind == KIND_DEVICE) {
                if (p.number <= 3) {
                    val order = laterDevice[p.number - 1]
                    var pick = order[order.size - 1]
                    for (j in order) if (!parts[j].deviceIn) { pick = j; break }
                    parts[pick].deviceIn = true
                }
                return
            }
            if (p.prevKind == KIND_DEVICE) p.deviceIn = false
        } else if (count == 2 && p.prevCount == 0) {
            p.deviceIn = true
        } else if (count == 1 && kind != KIND_DEVICE) {
            p.deviceIn = false
        }

        when (kind) {
            KIND_TERMINAL -> p.terminalsIn++
            KIND_LEVER -> p.leversIn++
            KIND_DEVICE -> {
                if (p.deviceIn && p.number == 2) parts[3].deviceIn = true
                p.deviceIn = true
            }
        }
        p.prevName = name; p.prevCount = count; p.prevKind = kind
        checkDone(p)
    }

    private fun checkDone(p: Part) {
        if (!p.complete()) return
        // The sim times its own sections (simSectionTime).
        if (!P3Sim.inSim) shown = Shown(timeText(serverTicks - partStart), System.currentTimeMillis())
        partStart = serverTicks
        live++
        if (live == 3) Recore.s3Done()
    }

    // Packets already taken, by identity: the wire tap and the event can both deliver one.
    private val taken = arrayOfNulls<ClientboundSystemChatPacket>(32)
    private var takenAt = 0

    private fun firstTake(p: ClientboundSystemChatPacket): Boolean = synchronized(taken) {
        for (q in taken) if (q === p) return false
        taken[takenAt] = p
        takenAt = (takenAt + 1) and 31
        true
    }

    private fun take(p: ClientboundSystemChatPacket, text: String) {
        val code = lineCode(text)
        if (code == 0 || !firstTake(p)) return
        mc.execute { EngineerClient.safely("term info chat") { onLine(text, code) } }
    }

    private fun takeChat(p: ClientboundSystemChatPacket) {
        if (!p.overlay()) take(p, EventDispatcher.chatText(p))
    }

    /**
     * Every inbound packet ahead of other mods' channelRead0 hooks (TerminalTapMixin), on the network thread: other
     * mods cancel the task and gate lines there before the event fires, and Hypixel bundles a completion with its
     * sound and title.
     */
    @JvmStatic
    fun tap(packet: Packet<*>) {
        EngineerClient.safely("term info tap") {
            when (packet) {
                is ClientboundSystemChatPacket -> takeChat(packet)
                is ClientboundOpenScreenPacket -> TerminalHitboxes.opened(packet.title.string)
                is ClientboundBundlePacket -> for (sub in packet.subPackets()) {
                    if (sub is ClientboundSystemChatPacket) takeChat(sub)
                    else if (sub is ClientboundOpenScreenPacket) TerminalHitboxes.opened(sub.title.string)
                }
                else -> {}
            }
        }
    }

    fun init() {
        on<LevelEvent.Load> { clearAll(); shown = null }
        on<TickEvent.Server> { serverTicks++ }
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) { if (!overlay()) take(this, it.chatText) }
        onReceive<ClientboundOpenScreenPacket>(priority = 1000, ignoreCancelled = true) { TerminalHitboxes.opened(title.string) }
        EventBus.subscribe(this)
    }
}
