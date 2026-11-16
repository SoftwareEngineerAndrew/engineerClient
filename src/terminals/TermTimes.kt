package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.engineerclient.terminals.sim.TermSimGUI
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.utils.modMessage
import net.minecraft.network.chat.Component
import java.util.Locale

/** Each terminal's time and PB, and P3's section splits. */
object TermTimes {
    private val completeRegex = Regex("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)$")
    private val gateRegex = Regex("The gate has been destroyed!")
    private val goldorRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
    private val coreRegex = Regex("^The Core entrance is opening!$")
    private val decimal = Regex("\\d+\\.\\d+")

    private var completed = 0 to 7
    private val times = mutableListOf<Float>()
    private var gateBlown = false
    private var sectionTimer = 0L
    private var currentTick = 0L
    private var phaseTimer = 0L
    private var lastSplit = ""

    /** [message] with every decimal at 2 places while Two Decimals is on. */
    @JvmStatic
    fun format(message: Any?): Any? {
        if (!Terminals.twoDecimals || message !is String) return message
        return decimal.replace(message) { String.format(Locale.ROOT, "%.2f", it.value.toDouble()) }
    }

    fun install() {
        on<TerminalEvent.Solve> {
            if (!Terminals.timesOn) return@on
            val sim = mc.gui.screen() is TermSimGUI
            val pbs = if (sim) Terminals.termSimPBs else Terminals.terminalPBs
            pbs.time(terminal.type.name, (System.currentTimeMillis() - terminal.timeOpened) / 1000f, "s§7!",
                "§a${terminal.type.termName}${if (sim) " §7(termsim)" else ""} §7solved in §6", sendMessage = Terminals.chatTimes)
        }

        on<MessageEvent.Chat> {
            if (!Terminals.timesOn || !Terminals.sectionSplits) return@on
            when {
                gateRegex.matches(message) -> if (completed.first == completed.second) resetSection() else gateBlown = true
                goldorRegex.matches(message) -> resetSection(true)
                coreRegex.matches(message) -> {
                    resetSection()
                    modMessage(format("§bTimes: §a${times.joinToString(" §8| ") { "§a${it}s" }}§8, §bTotal: §a${phaseTimer.seconds}s") as String)
                }
            }
            completeRegex.find(message)?.destructured?.let { (_, _, _, current, total) ->
                lastSplit = format("§8(§7${sectionTimer.seconds}s §8| §7${phaseTimer.seconds}s§8)") as String
                val cur = current.toIntOrNull() ?: return@on
                if ((current == total && gateBlown) || cur < completed.first) resetSection()
                else completed = cur to (total.toIntOrNull() ?: return@on)
            }
        }

        on<MessageEvent.ModifyChat> {
            if (!Terminals.timesOn || !Terminals.sectionSplits) return@on
            completeRegex.find(message)?.destructured?.let { (name, activated, type, current, total) ->
                component = Component.literal("§6$name §a$activated a $type! (§c$current§a/$total) $lastSplit")
            }
        }

        on<TickEvent.Server> { if (!Terminals.realTime) currentTick += 50 }
        on<LevelEvent.Load> { resetSection(true) }
        EventBus.subscribe(this)
    }

    private val Long.seconds get() = ((if (Terminals.realTime) System.currentTimeMillis() else currentTick) - this) / 1000f

    private fun resetSection(full: Boolean = false) {
        if (full) {
            times.clear()
            phaseTimer = if (Terminals.realTime) System.currentTimeMillis() else currentTick
        } else times.add(sectionTimer.seconds)
        completed = 0 to 7
        sectionTimer = if (Terminals.realTime) System.currentTimeMillis() else currentTick
        gateBlown = false
    }
}
