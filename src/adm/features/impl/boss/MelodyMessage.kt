package com.adm.features.impl.boss

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.SetSlotEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.features.impl.render.ClickGUIModule
import com.adm.utils.getPositionString
import com.adm.utils.network.WebUtils.gson
import com.adm.utils.network.webSocket
import com.adm.utils.render.getStringWidth
import com.adm.utils.render.text
import com.adm.utils.sendChatMessage
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.LocationUtils
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.M7Phases
import com.engineerclient.terminals.TerminalEvent
import com.engineerclient.terminals.TerminalTypes
import com.engineerclient.terminals.TerminalUtils
import com.engineerclient.terminals.sim.TermSimGUI
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import java.util.concurrent.ConcurrentHashMap

object MelodyMessage : Module(
    name = "Melody Message",
    description = "Helpful messages for the melody terminal in floor 7."
) {
    private val melodyProgress by BooleanSetting("Melody Progress", false, desc = "Tells the party about melody terminal progress.")
    private val melodySendCoords by BooleanSetting("Melody Send Coords", false, desc = "Sends the coordinates of the melody terminal.").withDependency { melodyProgress }

    private val melodyGui by HUD("Progress GUI", "Each teammate's melody: from the party link, Odin's socket, else party chat.", true) { example ->
        var rows = 0
        var captionWidth = 0
        val self = mc.user.name
        if (example) {
            captionWidth = drawRow(preview, 0, self)
            rows = 1
        }
        if (melodyWebSocket.connected || melodies.isNotEmpty()) {
            var row = 0
            for ((name, data) in melodies) {
                if (name != self) {
                    captionWidth = maxOf(captionWidth, drawRow(data, row, name))
                    rows = maxOf(rows, row + 1)
                }
                row++
            }
        }
        (cell * 5 + 2 + captionWidth) to (cell * rows)
    }

    private val showPlayer by SelectorSetting("Show Player", ShowPlayer.NONE, desc = "How player details should be rendered in the Melody GUI.")

    val melodyWebSocket = webSocket {
        onMessage { message ->
            val update = try { gson.fromJson(message, UpdateMessage::class.java) } catch (_: Exception) { return@onMessage }
            val name = update.username
            val clazz = DungeonUtils.dungeonTeammates.find { it.name == name }?.clazz ?: return@onMessage
            // the link or the socket outranks a row from chat
            val data = melodies[name]?.takeIf { !it.chat } ?: MelodyData(null, null, null, clazz).also { melodies[name] = it }
            when (update.type) {
                CLOSED -> melodies.remove(name)
                CLAY -> data.clay = update.slot
                PURPLE -> data.purple = update.slot
                PANE -> data.pane = update.slot
            }
        }
    }

    private val melodies = ConcurrentHashMap<String, MelodyData>()
    private val sent = MelodyData(null, null, null, DungeonClass.EMPTY)
    private val preview = MelodyData(3, 1, 2, DungeonClass.ARCHER)

    init {
        on<TerminalEvent.Open> {
            if (melodySendCoords && inLiveMelody(terminal.type)) sendChatMessage(getPositionString())
        }

        on<MessageEvent.Chat> {
            when (message) {
                CORE_OPENING -> {
                    melodyWebSocket.shutdown()
                    melodies.clear()
                }
                GOLDOR_START -> LocationUtils.lobbyId?.let { melodyWebSocket.connect("${ClickGUIModule.webSocketUrl}$it") }
            }
        }

        on<SetSlotEvent> {
            if (melodyProgress) onMelodySlot(slotIndex, itemStack.item)
        }

        on<LevelEvent.Load> {
            melodyWebSocket.shutdown()
            melodies.clear()
            sent.clay = null
            sent.purple = null
            sent.pane = null
        }

        on<TerminalEvent.Close> {
            if (terminal.type == TerminalTypes.MELODY) melodyWebSocket.send(update(CLOSED, 0))
        }
    }

    /** A teammate's percent from party chat: their row, unless the link or the socket already gives one. */
    fun chatProgress(name: String, percent: Int) {
        val have = melodies[name]
        if (have != null && !have.chat) return
        val clay = when { percent >= 75 -> 4; percent >= 50 -> 3; percent >= 25 -> 2; else -> 1 }
        if (have == null) {
            val clazz = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, true) }?.clazz ?: return
            melodies[name] = MelodyData(null, null, clay, clazz).apply { chat = true }
        } else if ((have.clay ?: 1) < clay) have.clay = clay
    }

    /** Takes out [name]'s chat row, or every chat row. */
    fun chatDone(name: String? = null) {
        melodies.entries.removeIf { it.value.chat && (name == null || it.key.equals(name, true)) }
    }

    private fun inLiveMelody(type: TerminalTypes) =
        type == TerminalTypes.MELODY && DungeonUtils.getF7Phase() == M7Phases.P3 && mc.gui.screen() !is TermSimGUI

    private fun onMelodySlot(slot: Int, item: Item) {
        val term = TerminalUtils.currentTerm ?: return
        if (!inLiveMelody(term.type)) return

        if (item == Items.DYED_TERRACOTTA.pick(DyeColor.LIME)) {
            val row = slot / 9
            if (sent.clay == row) return
            melodyWebSocket.send(update(CLAY, row))
            when (row) {
                2 -> sendCommand("pc Melody 33%")
                3 -> sendCommand("pc Melody 67%")
            }
            sent.clay = row
            return
        }

        val purple = item == Items.STAINED_GLASS_PANE.pick(DyeColor.MAGENTA)
        if (!purple && item != Items.STAINED_GLASS_PANE.pick(DyeColor.LIME)) return
        val column = slot % 9 - 1
        if (column !in 0..4 || slot >= 36) return
        if (purple) {
            if (sent.purple == column) return
            sent.purple = column
            melodyWebSocket.send(update(PURPLE, column))
        } else {
            if (sent.pane == column) return
            sent.pane = column
            melodyWebSocket.send(update(PANE, column))
        }
    }

    private fun update(type: Int, slot: Int): String = gson.toJson(UpdateMessage(mc.user.name, type, slot))

    private val cell by lazy { getStringWidth("§d■") }

    private fun GuiGraphicsExtractor.drawRow(data: MelodyData, row: Int, name: String): Int {
        val y = cell * row - 1
        val pane = data.pane
        val purple = data.purple
        for (column in 0 until 5) {
            val square = if (data.chat) UNKNOWN_SQUARE else if (column == pane) PANE_SQUARE else if (column == purple) PURPLE_SQUARE else EMPTY_SQUARE
            text(square, cell * column, y)
        }
        val clay = data.clay ?: return 0
        val mode = showPlayer
        val key = clay * 4 + mode.ordinal
        if (data.captionKey != key || data.captionName !== name) {
            data.captionKey = key
            data.captionName = name
            data.caption = "$clay ${playerTag(mode, data.dungeonClass, name)}"
            data.captionWidth = getStringWidth(data.caption)
        }
        text(data.caption, cell * 5 + 2, y)
        return data.captionWidth
    }

    private fun playerTag(mode: ShowPlayer, clazz: DungeonClass, name: String): String = when (mode) {
        ShowPlayer.NONE -> ""
        ShowPlayer.CLASS -> "§${clazz.colorCode}${clazz.name.lowercase()}"
        ShowPlayer.NAME -> "§6$name"
        ShowPlayer.CLASS_AND_NAME -> "§6$name §8(§${clazz.colorCode}${clazz.name.lowercase()}§8)"
    }

    private const val CLOSED = 0
    private const val CLAY = 1
    private const val PURPLE = 2
    private const val PANE = 5

    private const val CORE_OPENING = "The Core entrance is opening!"
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

    private const val PANE_SQUARE = "§a■"
    private const val PURPLE_SQUARE = "§d■"
    private const val EMPTY_SQUARE = "§f■"
    private const val UNKNOWN_SQUARE = "§8■"

    private data class UpdateMessage(val username: String, val type: Int, val slot: Int)

    private data class MelodyData(var purple: Int?, var pane: Int?, var clay: Int?, val dungeonClass: DungeonClass) {
        /** Only the clay is known, from party chat. */
        var chat = false
        var captionKey = -1
        var captionName: String? = null
        var caption = ""
        var captionWidth = 0
    }

    private enum class ShowPlayer(private val label: String) {
        NONE("None"), CLASS("Class"), NAME("Name"), CLASS_AND_NAME("Class & Name");

        override fun toString(): String = label
    }
}
