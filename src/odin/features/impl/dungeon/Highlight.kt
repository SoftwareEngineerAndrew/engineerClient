package com.odtheking.odin.features.impl.dungeon

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player

object Highlight : Module(
    name = "Highlight",
    description = "Blade's mob highlight: starred mobs, tanks, minibosses, Fels, Shadow Assassins, bats, withers and Mimics."
) {
    private val hideNonNames by BooleanSetting("Hide non-starred names", true, desc = "Hides names of entities that are not starred.")

    private val teammateClassGlow by BooleanSetting("Teammate Class Glow", true, desc = "Highlights dungeon teammates based on their class color.")

    private val dungeonMobSpawns = hashSetOf("Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor", "Sniper", "Super Archer", "Spider", "Fels", "Withermancer", "Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer")
    // https://regex101.com/r/QQf502/2
    private val starredRegex = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")

    init {
        on<TickEvent.End> {
            if (!hideNonNames || !DungeonUtils.inClear) return@on

            val entitiesToRemove = mutableListOf<Entity>()
            level.entitiesForRendering().forEach { e ->
                if (!e.isAlive || e !is ArmorStand) return@forEach

                val entityName = e.name.string
                if (!dungeonMobSpawns.any { it in entityName }) return@forEach

                val isStarred = starredRegex.matches(entityName)

                if (e.isInvisible && !isStarred) entitiesToRemove.add(e)
            }
            entitiesToRemove.forEach { it.remove(Entity.RemovalReason.DISCARDED) }
        }
    }

    @JvmStatic
    fun getTeammateColor(entity: Entity): Int? {
        if (!enabled || !teammateClassGlow || !DungeonUtils.inDungeons || entity !is Player) return null
        val name = entity.gameProfile.name
        return DungeonUtils.dungeonTeammates.find { it.name == name }?.clazz?.color?.rgba
    }
}