package com.engineerclient.terminals

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.Station
import com.engineerclient.party.TermProgress
import com.engineerclient.pov.PoseShare
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.utils.Colors
import com.adm.utils.playSoundSettings
import com.adm.utils.render.text
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.nio.ByteBuffer

/**
 * A warning when a chosen P3 terminal is done. Hypixel's chat says who, not which: the which comes from that player's
 * game, as an event over the party link ([KIND]: [15][1][terminal 0-16], sent by whoever finished it) or else from the
 * terminal their Term Progress last said they had open.
 */
object TermDoneWarning {
    const val KIND = 15
    private const val TERMINAL_DONE = 1
    private val TERMINAL_LINE = Regex("^(\\w{1,16}) activated a terminal! \\(\\d/\\d\\)$")
    private const val GOLDOR_LINE = "[BOSS] Goldor: Who dares trespass into my domain?"

    enum class Pick(private val label: String, val n: Int) {
        Off("Off", -1), Any("Any", 0), T1("T1", 1), T2("T2", 2), T3("T3", 3), T4("T4", 4), T5("T5", 5);
        override fun toString() = label
    }

    private val stations by lazy { Station.all().filter { it.kind == Station.Kind.TERMINAL } }
    private val done = HashSet<Int>()
    private var shown = ""
    private var shownUntil = 0L

    fun draw(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        val s = when {
            example -> "§a§lS2 T4 done §7(Player)"
            Terminals.doneWarningOn && System.currentTimeMillis() < shownUntil -> shown
            else -> return 0 to 0
        }
        g.text(s, 0, 0, Colors.WHITE, true)
        return mc.font.width(s) to 9
    }

    fun install() {
        PoseShare.onExtra(KIND) { sender, b -> EngineerClient.safely("term done") { heard(sender, b) } }
        on<MessageEvent.Chat> {
            if (message == GOLDOR_LINE) { done.clear(); return@on }
            val name = TERMINAL_LINE.matchEntire(message)?.groupValues?.get(1) ?: return@on
            if (!inP3()) return@on
            if (name.equals(mc.user.name, ignoreCase = true)) {
                val t = TermProgress.ownLastTerminal() ?: return@on
                PoseShare.shareReliable(byteArrayOf(KIND.toByte(), TERMINAL_DONE.toByte(), t.toByte()))
                terminalDone(t, name)
            } else TermProgress.lastTerminalOf(name.lowercase())?.let { terminalDone(it, name) }
        }
        on<LevelEvent.Load> { done.clear(); shownUntil = 0 }
    }

    private fun inP3() = DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P3

    private fun heard(sender: String, b: ByteBuffer) {
        if (b.remaining() != 2 || b.get().toInt() != TERMINAL_DONE) return
        val t = b.get().toInt() and 255
        if (t >= stations.size) return
        val name = DungeonUtils.dungeonTeammatesNoSelf.firstOrNull { it.name.equals(sender, ignoreCase = true) }?.name ?: return
        mc.execute { if (inP3()) terminalDone(t, name) }
    }

    private fun terminalDone(t: Int, name: String) {
        if (!done.add(t) || !Terminals.doneWarningOn || name.equals(mc.user.name, ignoreCase = true)) return
        val st = stations[t]
        val pick = Terminals.donePick(st.section)
        if (pick == Pick.Off || (pick != Pick.Any && st.label != "T${pick.n}")) return
        shown = "§a§lS${st.section} ${st.label} done §7($name)"
        shownUntil = System.currentTimeMillis() + (Terminals.doneWarningTime * 1000).toLong()
        if (Terminals.doneWarningSound) playSoundSettings(Terminals.doneSound())
    }
}
