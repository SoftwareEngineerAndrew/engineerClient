package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.adm.events.LevelEvent
import com.adm.events.ScoreUpdateEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.utils.skyblock.dungeon.DungeonUtils

/** Clear Info's Score Time: the run time when it reaches S (270) and the S+ secrets. */
object ScoreTime {
    private var saidS = false
    private var saidSecrets = false
    /** The world it was switched off in: a world load missed since then starts over. */
    private var offIn = java.lang.ref.WeakReference<Any?>(null)

    fun install() {
        on<LevelEvent.Load> {
            saidS = false
            saidSecrets = false
        }
        // The secret count, the secrets needed and the boss state all move the score, so a score change is when to look.
        on<ScoreUpdateEvent> {
            if (!saidS && score >= 270 && ClearInfo.showS && DungeonUtils.inDungeons) {
                saidS = true
                mc.execute { say(ClearInfo.sText) }
            }
            if (!saidSecrets && ClearInfo.showMinSecrets && DungeonUtils.inClear && secretsMet()) {
                saidSecrets = true
                mc.execute { say("&bMin Secrets") }
            }
        }
        SubscribeWhile.add(this, { if (offIn.get() !== mc.level) { saidS = false; saidSecrets = false } }, { offIn = java.lang.ref.WeakReference(mc.level) }) {
            ClearInfo.scoreTimeOn && (ClearInfo.showS || ClearInfo.showMinSecrets)
        }
    }

    private fun secretsMet(): Boolean {
        val needed = DungeonUtils.neededSecretsAmount
        return needed > 0 && DungeonUtils.secretCount >= needed && runSeconds(DungeonUtils.dungeonTime) >= 20
    }

    private fun say(what: String) {
        val time = DungeonUtils.dungeonTime
        val seconds = runSeconds(time)
        val shown = if (seconds < 0) time else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        val text = "$what &breached at &a$shown &7(${DungeonUtils.floor?.name ?: "?"})"
        EngineerClient.chat(text.replace('&', '§'))
        if (ClearInfo.scoreTitle) showTitle(text, 1.5)
        ClearInfo.scoreSound.play()
    }

    // "1h 2m 3s" style; -1 when there is no number followed by h, m or s.
    private fun runSeconds(time: String): Int {
        var total = 0
        var value = 0
        var digits = false
        var any = false
        for (c in time) {
            if (c in '0'..'9') {
                value = value * 10 + (c - '0')
                digits = true
                continue
            }
            val unit = when (c) { 'h' -> 3600; 'm' -> 60; 's' -> 1; else -> 0 }
            if (digits && unit != 0) {
                total += value * unit
                any = true
            }
            value = 0
            digits = false
        }
        return if (any) total else -1
    }
}
