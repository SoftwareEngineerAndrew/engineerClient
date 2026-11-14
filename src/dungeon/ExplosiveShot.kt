package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.engineerclient.EngineerClient
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import java.text.DecimalFormat

object ExplosiveShot : Module(
    name = "Explosive Shot",
    category = Category.DUNGEON,
    description = "After an Explosive Shot, says in chat how much damage it did per enemy.",
    key = null,
    toggled = true,
) {
    private val onlyInBoss by BooleanSetting("Only In Boss", true, desc = "Only during the boss fight.")

    private val regex = Regex("^Your Explosive Shot hit (\\d+) enem(?:y|ies) for ([\\d,.]+) damage\\.$")
    private val format = DecimalFormat("###,###,###,###")

    init {
        on<MessageEvent.Chat> {
            if (!DungeonUtils.inDungeons || (onlyInBoss && !DungeonUtils.inBoss)) return@on
            val match = regex.matchEntire(message) ?: return@on
            val count = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return@on
            val damage = match.groupValues[2].replace(",", "").toDoubleOrNull() ?: return@on
            EngineerClient.msg("§aExplosive Shot did §e${format.format(damage / count)}§a damage per enemy.")
        }
    }
}
