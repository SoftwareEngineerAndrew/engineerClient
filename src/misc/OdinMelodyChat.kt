package com.engineerclient.misc

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.practice.TermInfo
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.boss.MelodyMessage
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Melody progress from the party chat. Teammates' mods announce it ("Melody 33%", "Melody terminal is at 67%"); this
 * keeps the furthest percent heard per teammate in P3 ([heard], read by Term Progress as well) and, for teammates Odin's
 * Melody Message does not know from its own websocket, puts a row into its Progress GUI: clay from the percent, pane
 * and purple unknown. Odin's `melodies` map and `MelodyData` are private, so both are reached by reflection, once. If
 * that fails it is logged once and only the Odin rows go; the chat state stays. Odin's GUI only draws while its
 * websocket is connected, so with it down these rows are not shown.
 */
object OdinMelodyChat {

    private val odinRows = BooleanSetting("Chat Melody", true, desc = "Teammates' melody progress from the party chat also gets a row here, when they aren't broadcasting through Odin. Added by engineerClient.")

    /** A teammate's furthest percent heard, and when ([at], System.nanoTime) the last percent message came. */
    class Heard(val percent: Int, val at: Long)

    private val PARTY = Regex("^Party > (?:\\[[^]]*] )?(\\w{1,16}): (.*)$")
    private val PERCENT = Regex("(\\d+)%")
    private val TERMINAL = Regex("^(\\w{1,16}) activated a terminal! \\(\\d+/\\d+\\)$")

    private val chat = ConcurrentHashMap<String, Heard>()
    /** The names whose Odin row we put there, so only those are taken out again. */
    private val added = ConcurrentHashMap.newKeySet<String>()
    private var section = 0

    /** Teammates whose melody progress is known from the party chat right now (name as sent, percent, time). */
    internal fun heard(): Map<String, Heard> = chat

    private fun inP3() = DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P3

    /** The Odin row's clay for a percent: 25 or 33 is 2, 50 to 67 is 3, 75 is 4, less than 25 is 1. */
    private fun clayOf(percent: Int) = when {
        percent >= 75 -> 4
        percent >= 50 -> 3
        percent >= 25 -> 2
        else -> 1
    }

    // ------------------------------------------------------------------ Odin's private bits, found once

    private class Odin(val map: MutableMap<String, Any>, val make: Constructor<*>, val getClay: Method, val setClay: Method)

    private var odin: Odin? = null
    private var odinFailed = false

    private fun odin(): Odin? {
        odin?.let { return it }
        if (odinFailed) return null
        try {
            val field = MelodyMessage::class.java.getDeclaredField("melodies").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val map = field.get(MelodyMessage) as MutableMap<String, Any>
            val data = Class.forName("com.odtheking.odin.features.impl.boss.MelodyMessage\$MelodyData")
            val int = Int::class.javaObjectType
            val make = data.getDeclaredConstructor(int, int, int, DungeonClass::class.java).apply { isAccessible = true }
            val getClay = data.getDeclaredMethod("getClay").apply { isAccessible = true }
            val setClay = data.getDeclaredMethod("setClay", int).apply { isAccessible = true }
            return Odin(map, make, getClay, setClay).also { odin = it }
        } catch (t: Throwable) {
            odinFailed = true
            EngineerClient.logger.error("[ec] chat melody rows in Odin's Melody Message failed, turned off", t)
            return null
        }
    }

    /** Odin's row for [name] at least at [percent]'s clay; made if there is none, never lowered. */
    private fun putRow(name: String, percent: Int) {
        val o = odin() ?: return
        val clay = clayOf(percent)
        val row = o.map[name]
        if (row == null) {
            val clazz = DungeonUtils.dungeonTeammates.firstOrNull { it.name == name }?.clazz ?: return
            o.map[name] = o.make.newInstance(null, null, clay, clazz)
            added += name
        } else if (((o.getClay.invoke(row) as Int?) ?: 1) < clay) {
            o.setClay.invoke(row, clay)
        }
    }

    private fun dropRow(name: String) {
        if (added.remove(name)) odin()?.map?.remove(name)
    }

    private fun clear() {
        chat.clear()
        for (n in added.toList()) dropRow(n)
    }

    private fun onChat(message: String) {
        if (!inP3()) return
        TERMINAL.matchEntire(message)?.let {
            val name = it.groupValues[1]
            chat.keys.removeIf { k -> k.equals(name, ignoreCase = true) }
            added.firstOrNull { k -> k.equals(name, ignoreCase = true) }?.let(::dropRow)
            return
        }
        val party = PARTY.matchEntire(message) ?: return
        val sender = party.groupValues[1]
        if (sender.equals(mc.user.name, ignoreCase = true)) return
        val percent = PERCENT.find(party.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() ?: return
        // The furthest progress wins, so a stray number doesn't move it back; the time is of the latest message.
        chat[sender] = Heard(maxOf(percent, chat[sender]?.percent ?: 0), System.nanoTime())
        if (odinRows.value) EngineerClient.safely("chat melody odin row") { putRow(sender, chat.getValue(sender).percent) }
    }

    /** Adds the setting to Odin's Melody Message; before the configs are loaded, so a saved value applies. */
    fun install() {
        MelodyMessage.registerSetting(odinRows)
        on<MessageEvent.Chat> { EngineerClient.safely("chat melody") { onChat(message) } }
        on<TickEvent.End> {
            EngineerClient.safely("chat melody section") {
                val sec = if (inP3()) TermInfo.sectionNumber() ?: 0 else 0
                if (sec != section) { section = sec; clear() }
            }
        }
        on<LevelEvent.Load> {
            // Odin empties its own map on a world load.
            chat.clear(); added.clear(); section = 0
        }
        EventBus.subscribe(this)
    }
}
