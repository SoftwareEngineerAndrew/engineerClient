package com.engineerclient.dungeon

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.engineerclient.EngineerClient
import com.adm.utils.skyblock.dungeon.DungeonUtils
import java.text.DecimalFormat

object ExplosiveShot : Module(
    name = "Explosive Shot",
    category = Category.DUNGEON,
    description = "After an Explosive Shot, says in chat how much damage it did per enemy.",
    key = null,
    toggled = true,
) {
    private val onlyInBoss by BooleanSetting("Only In Boss", true, desc = "Only during the boss fight.")

    private const val LEAD = "Your Explosive Shot hit "
    private val hitLine = Regex("(\\d+) enem(?:y|ies) for ([\\d,.]+) damage\\.")
    private val perEnemy = DecimalFormat("###,###,###,###")

    init {
        on<MessageEvent.Chat> {
            if (!message.startsWith(LEAD) || !DungeonUtils.inDungeons || onlyInBoss && !DungeonUtils.inBoss) return@on
            val (enemies, total) = hitLine.matchEntire(message.substring(LEAD.length))?.destructured ?: return@on
            val count = enemies.toIntOrNull() ?: return@on
            if (count <= 0) return@on
            val damage = total.replace(",", "").toDoubleOrNull() ?: return@on
            EngineerClient.msg("§aExplosive Shot did §e${perEnemy.format(damage / count)}§a damage per enemy.")
        }
    }
}
