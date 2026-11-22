package com.engineerclient.dungeon

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Colors
import com.engineerclient.EngineerClient
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.DungeonUtils
import java.text.DecimalFormat

object ExplosiveShot : Module(
    name = "Explosive Shot",
    category = Category.DUNGEON,
    description = "Explosive Shot damage per enemy and its cooldown timer.",
    key = null,
    toggled = true,
) {
    private val onlyInBoss by BooleanSetting("Only In Boss", true, desc = "Only says the damage during the boss fight.")
    private val timerOnlyInClear by BooleanSetting("Timer Only In Clear", false, desc = "Only shows the cooldown timer before the boss.")
    private val timerLabel by BooleanSetting("Timer Label", true, desc = "Shows \"Explosive Shot:\" before the time.")

    private val timerHud by HUD("Explosive Shot Timer", "Time left on Explosive Shot's cooldown.", true, 420, 82, 1f) { example ->
        if (!example && timerOnlyInClear && (!DungeonUtils.inDungeons || DungeonUtils.inBoss)) return@HUD 0 to 0
        val left = if (example) 23400L else readyAt - System.currentTimeMillis()
        if (left <= 0) return@HUD 0 to 0
        val tenths = (left + 99) / 100
        if (tenths != shownTenths || timerLabel != shownLabel) {
            shownTenths = tenths
            shownLabel = timerLabel
            shownText = (if (timerLabel) "§6Explosive Shot: §f" else "§f") + "${tenths / 10}.${tenths % 10}s"
            shownWidth = mc.font.width(shownText)
        }
        text(mc.font, shownText, 0, 0, Colors.WHITE.rgba, true)
        shownWidth to 9
    }

    private const val LEAD = "Your Explosive Shot hit "
    private const val USED = "Used Explosive Shot!"
    private val hitLine = Regex("(\\d+) enem(?:y|ies) for ([\\d,.]+) damage\\.")
    private val cooldownLine = Regex("^Your Regular Ability is currently on cooldown for (\\d+) more seconds?\\.$")
    private val perEnemy = DecimalFormat("###,###,###,###")

    private var readyAt = 0L
    private var shownTenths = -1L
    private var shownLabel = true
    private var shownText = ""
    private var shownWidth = 0

    // 40s, 2s less per 5 Archer levels
    private fun cooldownMs(): Long {
        val lvl = DungeonUtils.currentDungeonPlayer.clazzLvl.coerceAtLeast(0)
        return (40 - 2 * (lvl / 5)).coerceAtLeast(20) * 1000L
    }

    init {
        on<LevelEvent.Load> { readyAt = 0L }

        on<MessageEvent.Chat> {
            if (message == USED) {
                if (DungeonUtils.currentDungeonPlayer.clazz == DungeonClass.ARCHER) readyAt = System.currentTimeMillis() + cooldownMs()
                return@on
            }
            cooldownLine.matchEntire(message)?.let { m ->
                if (DungeonUtils.currentDungeonPlayer.clazz != DungeonClass.ARCHER) return@on
                val secs = m.groupValues[1].toLongOrNull() ?: return@on
                val left = readyAt - System.currentTimeMillis()
                if (left <= (secs - 1) * 1000 || left > secs * 1000) readyAt = System.currentTimeMillis() + secs * 1000
                return@on
            }
            if (!message.startsWith(LEAD) || !DungeonUtils.inDungeons || onlyInBoss && !DungeonUtils.inBoss) return@on
            val (enemies, total) = hitLine.matchEntire(message.substring(LEAD.length))?.destructured ?: return@on
            val count = enemies.toIntOrNull() ?: return@on
            if (count <= 0) return@on
            val damage = total.replace(",", "").toDoubleOrNull() ?: return@on
            EngineerClient.msg("§aExplosive Shot did §e${perEnemy.format(damage / count)}§a damage per enemy.")
        }
    }
}
