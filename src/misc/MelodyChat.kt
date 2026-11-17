package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.practice.TermInfo
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.features.impl.boss.MelodyMessage
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import java.util.concurrent.ConcurrentHashMap

/**
 * Melody progress from the party chat ("Melody 33%", "Melody terminal is at 67%"): the furthest percent heard per teammate
 * in P3 ([heard], read by Term Progress too), and Melody Message's row for teammates neither the link nor Odin's socket covers.
 */
object MelodyChat {

    private val chatRows = BooleanSetting("Chat Melody", true, desc = "Teammates who only post melody in party chat get a row too.")

    /** A teammate's furthest percent heard, and when ([at], System.nanoTime) the last percent message came. */
    class Heard(val percent: Int, val at: Long)

    private val PARTY = Regex("""^Party > (?:\[[^]]*] )?(\w{1,16}): (.*)$""")
    private val PERCENT = Regex("""(\d+)%""")
    private val TERMINAL = Regex("""^(\w{1,16}) activated a terminal! \(\d+/\d+\)$""")

    private val chat = ConcurrentHashMap<String, Heard>()
    private var section = 0
    private var p3 = false

    /** Teammates whose melody progress is known from the party chat right now (name as sent, percent, time). */
    internal fun heard(): Map<String, Heard> = chat

    private fun clear() {
        chat.clear()
        MelodyMessage.chatDone()
    }

    private fun onChat(message: String) {
        if (!p3 || !message.startsWith("Party > ") && !message.endsWith(")")) return
        TERMINAL.matchEntire(message)?.let {
            val name = it.groupValues[1]
            chat.keys.removeIf { k -> k.equals(name, ignoreCase = true) }
            MelodyMessage.chatDone(name)
            return
        }
        val party = PARTY.matchEntire(message) ?: return
        val sender = party.groupValues[1]
        if (sender.equals(mc.user.name, ignoreCase = true)) return
        val percent = PERCENT.find(party.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() ?: return
        // The furthest progress wins, so a stray number doesn't move it back; the time is of the latest message.
        val heard = Heard(maxOf(percent, chat[sender]?.percent ?: 0), System.nanoTime())
        chat[sender] = heard
        if (chatRows.value) MelodyMessage.chatProgress(sender, heard.percent)
    }

    fun install() {
        MelodyMessage.registerSetting(chatRows)
        on<MessageEvent.Chat> { EngineerClient.safely("chat melody") { onChat(message) } }
        on<TickEvent.End> {
            EngineerClient.safely("chat melody section") {
                p3 = DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P3
                val sec = if (p3) TermInfo.sectionNumber() ?: 0 else 0
                if (sec != section) { section = sec; clear() }
            }
        }
        on<LevelEvent.Load> { chat.clear(); section = 0; p3 = false }
        EventBus.subscribe(this)
    }
}
