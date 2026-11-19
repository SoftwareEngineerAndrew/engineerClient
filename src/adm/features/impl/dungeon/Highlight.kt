package com.adm.features.impl.dungeon

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.NameMemo
import com.adm.utils.render.BoxBatch
import com.adm.utils.render.drawBoxes
import com.adm.utils.render.drawText
import com.adm.utils.renderBoundingBox
import com.adm.utils.renderPos
import com.adm.utils.skyblock.dungeon.DungeonClass
import com.adm.utils.skyblock.dungeon.DungeonPlayer
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import java.util.IdentityHashMap

object Highlight : Module(
    name = "Highlights",
    description = "ADM's mob highlight: starred mobs, tanks, minibosses, Fels, Shadow Assassins, bats and withers, plus teammates."
) {
    private val hideNonNames by BooleanSetting("Hide non-starred names", true, desc = "Hides names of entities that are not starred.")

    private val teammateBox by BooleanSetting("Teammate Highlight", false, desc = "Outlines dungeon teammates in their class colour, through walls.")
    private val teammateName by BooleanSetting("Teammate Class Name", false, desc = "Shows each teammate's name and class letter above them, through walls.")

    // Minibosses stay: an unstarred one is still a fight worth seeing. Blood mob names share none of these words.
    private val clearMobNames = arrayOf(
        "Lurker", "Dreadlord", "Souleater", "Zombie", "Skeleton", "Skeletor", "Sniper", "Super Archer", "Spider", "Fels",
        "Withermancer",
    )
    private val starredTag = Regex("^.*✯ .*\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?.?❤$")
    private val plainMobTag = NameMemo { name ->
        val s = name.string
        clearMobNames.any { it in s } && !starredTag.matches(s)
    }

    /** Only not drawn: removing the tag would also lose a ✯ it gets later, and the mob highlight finds mobs by their tags. */
    @JvmStatic
    fun hidesTag(entity: Entity): Boolean {
        if (!enabled || !hideNonNames || entity !is ArmorStand || !entity.isInvisible || !DungeonUtils.inClear) return false
        val name = entity.customName ?: return false
        return plainMobTag[entity, name]
    }

    /** The vanilla tag goes while the class name is drawn above them. */
    @JvmStatic
    fun hidesTeammateName(entity: Entity): Boolean {
        if (!enabled || !teammateName || entity !is Player || !DungeonUtils.inDungeons || entity === mc.player) return false
        val name = entity.gameProfile.name
        val mates = DungeonUtils.dungeonTeammates
        for (i in mates.indices) {
            val mate = mates[i]
            if (mate.clazz != DungeonClass.EMPTY && mate.name == name) return true
        }
        return false
    }

    private fun entityOf(mate: DungeonPlayer, level: ClientLevel): Player? {
        mate.entity?.let { if (!it.isRemoved) return it }
        val players = level.players()
        for (i in players.indices) if (players[i].gameProfile.name == mate.name) return players[i]
        return null
    }

    private class Label(val clazz: DungeonClass, val text: String)
    private val labels = IdentityHashMap<DungeonPlayer, Label>()

    private fun labelOf(mate: DungeonPlayer): String {
        val clazz = mate.clazz
        labels[mate]?.let { if (it.clazz == clazz) return it.text }
        if (labels.size > 32) labels.clear()
        val text = "§${clazz.colorCode}${mate.name} §e[${clazz.name[0]}]"
        labels[mate] = Label(clazz, text)
        return text
    }

    private val outlines = BoxBatch()

    init {
        on<RenderExtractEvent> {
            if ((!teammateBox && !teammateName) || !DungeonUtils.inDungeons) return@on
            val level = mc.level ?: return@on
            val self = mc.player?.gameProfile?.name ?: return@on
            val mates = DungeonUtils.dungeonTeammates
            outlines.clear()
            for (i in mates.indices) {
                val mate = mates[i]
                if (mate.clazz == DungeonClass.EMPTY || mate.name == self) continue
                val p = entityOf(mate, level) ?: continue
                if (teammateBox) outlines.outline(p.renderBoundingBox, mate.clazz.color, depth = false)
                if (teammateName) drawText(labelOf(mate), p.renderPos.add(0.0, 2.75, 0.0), 2f, false)
            }
            drawBoxes(outlines)
        }
        on<LevelEvent.Load> { labels.clear() }
    }
}
