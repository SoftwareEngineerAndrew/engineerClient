package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.ScoreUpdateEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

/** Clear Info's Score Time: the run time when it reaches S (270) and the S+ secrets. */
object ScoreTime {
    private var sDone = false
    private var minDone = false

    fun install() {
        on<LevelEvent.Load> { sDone = false; minDone = false }
        on<ScoreUpdateEvent> {
            if (score < 270 || sDone || !ClearInfo.scoreTimeOn || !ClearInfo.showS || !DungeonUtils.inDungeons) return@on
            sDone = true
            mc.execute { announce("&eS") }
        }
        on<TickEvent.End> {
            if (minDone || !ClearInfo.scoreTimeOn || !ClearInfo.showMinSecrets || !DungeonUtils.inClear) return@on
            val needed = DungeonUtils.neededSecretsAmount
            if (needed <= 0 || DungeonUtils.secretCount < needed || (seconds() ?: 0) < 20) return@on
            minDone = true
            announce("&bMin Secrets")
        }
        EventBus.subscribe(this)
    }

    private fun announce(what: String) {
        val text = "$what &breached at &a${time()} &7(${DungeonUtils.floor?.name ?: "?"})"
        EngineerClient.chat(text.replace('&', '§'))
        if (ClearInfo.scoreTitle) showTitle(text, 1.5)
        ClearInfo.scoreSound.play()
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
