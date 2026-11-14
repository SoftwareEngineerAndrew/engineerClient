package com.odtheking.odin.features.impl.dungeon

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player

object Highlight : Module(
    name = "Mob Highlight",
    description = "Blade's mob highlight: starred mobs, tanks, minibosses, Fels, Shadow Assassins, bats and withers."
) {
    private val hideNonNames by BooleanSetting("Hide non-starred names", true, desc = "Hides names of entities that are not starred.")

    private val teammateClassGlow by BooleanSetting("Teammate Class Glow", true, desc = "Highlights dungeon teammates based on their class color.")

    private val dungeonMobSpawns = hashSetOf("Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor", "Sniper", "Super Archer", "Spider", "Fels", "Withermancer", "Lost Adventurer", "Angry Archaeologist", "Frozen Adventurer")
    // https://regex101.com/r/QQf502/2
    private val starredRegex = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")

    /** Only not drawn: removing the tag would also lose a ✯ it gets later, and the mob highlight finds mobs by their tags. */
    @JvmStatic
    fun hidesTag(entity: Entity): Boolean {
        if (!enabled || !hideNonNames || entity !is ArmorStand || !entity.isInvisible || !DungeonUtils.inClear) return false
        val name = entity.name.string
        return dungeonMobSpawns.any { it in name } && !starredRegex.matches(name)
    }

    @JvmStatic
    fun getTeammateColor(entity: Entity): Int? {
        if (!enabled || !teammateClassGlow || !DungeonUtils.inDungeons || entity !is Player) return null
        val name = entity.gameProfile.name
        return DungeonUtils.dungeonTeammates.find { it.name == name }?.clazz?.color?.rgba
    }
}