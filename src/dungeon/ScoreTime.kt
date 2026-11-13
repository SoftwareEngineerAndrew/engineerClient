package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.ScoreUpdateEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

object ScoreTime : Module(
    name = "Score Time",
    category = Category.DUNGEON,
    description = "The run time when it reaches S (270) and the S+ secrets; Odin's Map Info does 300.",
    key = null,
) {
    private val showS by BooleanSetting("S Score", true, desc = "When the score reaches 270.")
    private val showMinSecrets by BooleanSetting("Min Secrets", false, desc = "When the secrets needed for S+ are found.")
    private val showAlert by BooleanSetting("Alert", true, desc = "A title as well as the chat line.")
    private val sound = SoundOption(this, "minecraft:entity.experience_orb.pickup")

    private var sDone = false
    private var minDone = false

    init {
        on<LevelEvent.Load> { sDone = false; minDone = false }
        on<ScoreUpdateEvent> {
            if (score < 270 || sDone || !showS || !DungeonUtils.inDungeons) return@on
            sDone = true
            mc.execute { announce("&eS") }
        }
        on<TickEvent.End> {
            if (minDone || !showMinSecrets || !DungeonUtils.inClear) return@on
            val needed = DungeonUtils.neededSecretsAmount
            if (needed <= 0 || DungeonUtils.secretCount < needed || (seconds() ?: 0) < 20) return@on
            minDone = true
            announce("&bMin Secrets")
        }
    }

    private fun announce(what: String) {
        val text = "$what &breached at &a${time()} &7(${DungeonUtils.floor?.name ?: "?"})"
        EngineerClient.chat(text.replace('&', '§'))
        if (showAlert) showTitle(text, 1.5)
        sound.play()
    }

    private val PART = Regex("""(\d+)([hms])""")

    private fun seconds(): Int? {
        val parts = PART.findAll(DungeonUtils.dungeonTime).toList()
        if (parts.isEmpty()) return null
        return parts.sumOf { m -> m.groupValues[1].toInt() * when (m.groupValues[2]) { "h" -> 3600; "m" -> 60; else -> 1 } }
    }

    private fun time(): String {
        val s = seconds() ?: return DungeonUtils.dungeonTime
        return "%d:%02d".format(s / 60, s % 60)
    }
}
