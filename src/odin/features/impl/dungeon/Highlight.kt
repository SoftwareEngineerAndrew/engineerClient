package com.odtheking.odin.features.impl.dungeon

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.renderBoundingBox
import com.odtheking.odin.utils.renderPos
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player

object Highlight : Module(
    name = "Highlights",
    description = "Blade's mob highlight: starred mobs, tanks, minibosses, Fels, Shadow Assassins, bats and withers, plus teammates."
) {
    private val hideNonNames by BooleanSetting("Hide non-starred names", true, desc = "Hides names of entities that are not starred.")

    private val teammateBox by BooleanSetting("Teammate Highlight", false, desc = "Outlines dungeon teammates in their class colour, through walls.")
    private val teammateName by BooleanSetting("Teammate Class Name", false, desc = "Shows each teammate's name and class letter above them, through walls.")

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

    private fun teammates(): List<Pair<Player, DungeonPlayer>> {
        val level = mc.level ?: return emptyList()
        val me = mc.player ?: return emptyList()
        return DungeonUtils.dungeonTeammates.mapNotNull { mate ->
            if (mate.name == me.name.string || mate.clazz == DungeonClass.EMPTY) return@mapNotNull null
            val p = mate.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == mate.name } ?: return@mapNotNull null
            p to mate
        }
    }

    /** The vanilla tag goes while the class name is drawn above them. */
    @JvmStatic
    fun hidesTeammateName(entity: Entity): Boolean =
        enabled && teammateName && entity is Player && DungeonUtils.inDungeons && entity !== mc.player &&
            DungeonUtils.dungeonTeammates.any { it.name == entity.name.string && it.clazz != DungeonClass.EMPTY }

    init {
        on<RenderExtractEvent> {
            if ((!teammateBox && !teammateName) || !DungeonUtils.inDungeons) return@on
            for ((p, mate) in teammates()) {
                if (teammateBox) drawWireFrameBox(p.renderBoundingBox, mate.clazz.color, depth = false)
                if (teammateName) drawText("§${mate.clazz.colorCode}${mate.name} §e[${mate.clazz.name[0]}]", p.renderPos.add(0.0, 2.75, 0.0), 2f, false)
            }
        }
    }
}