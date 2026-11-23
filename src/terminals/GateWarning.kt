package com.engineerclient.terminals

import com.engineerclient.EngineerClient.mc
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.utils.Colors
import com.adm.utils.playSoundSettings
import com.adm.utils.render.text
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Warns when a gated section (1-3) is close to done and its gate is still up. */
object GateWarning {
    private val completedRegex = Regex("^.{1,16} (?:activated|completed) a (?:terminal|lever|device)! \\((\\d)/(\\d)\\)$")
    private val goldorRegex = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
    private val gateRegex = Regex("^The gate has been destroyed!$")
    private val coreRegex = Regex("^The Core entrance is opening!$")

    private var section = 1
    private var gate = false
    private var complete = false
    private var warned = false
    private var shownUntil = 0L
    private var progress = ""

    fun draw(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val s = when {
            example -> "§c§lGate not blown! §7(5/7)"
            Terminals.gateWarningOn && System.currentTimeMillis() < shownUntil -> "§c§lGate not blown! §7($progress)"
            else -> return 0 to 0
        }
        g.text(s, 0, 0, Colors.WHITE, true)
        return mc.font.width(s) to 9
    }

    fun install() {
        on<MessageEvent.Chat> {
            if (!DungeonUtils.inBoss) return@on
            completedRegex.find(message)?.let { m ->
                val done = m.groupValues[1].toInt()
                val total = m.groupValues[2].toInt()
                if (section <= 3 && !gate && !warned && total - done in 1..Terminals.gateWarningLeft) {
                    warned = true
                    progress = "$done/$total"
                    shownUntil = System.currentTimeMillis() + (Terminals.gateWarningTime * 1000).toLong()
                    if (Terminals.gateWarningSound) playSoundSettings(Terminals.gateSound())
                }
                if (done == total) { if (gate) next() else complete = true }
                return@on
            }
            when {
                gateRegex.matches(message) -> { gate = true; shownUntil = 0; if (complete) next() }
                goldorRegex.matches(message) || coreRegex.matches(message) -> reset()
            }
        }
        on<LevelEvent.Load> { reset() }
        SubscribeWhile.add(this, ::reset) { Terminals.gateWarningOn }
    }

    private fun next() {
        section++; gate = false; complete = false; warned = false; shownUntil = 0
    }

    private fun reset() {
        section = 1; gate = false; complete = false; warned = false; shownUntil = 0
    }
}
