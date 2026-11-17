package com.adm.features.impl.dungeon

import com.engineerclient.dungeon.SecretWaypoints
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.events.LevelEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.TickEvent
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.utils.Color
import com.adm.utils.render.drawFilledBox
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import com.adm.events.EntityEvent
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.dungeon.DungeonListener
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.monster.zombie.Zombie
import java.util.Collections
import java.util.IdentityHashMap

object Mimic : Module(
    name = "Mimic",
    description = "Detects and announces Mimic, Prince and Bat kills in dungeons."
) {
    private val mimicMessageToggle by BooleanSetting("Send Mimic Message", true, desc = "Toggles the mimic killed message.")
    private val princeMessageToggle by BooleanSetting("Send Prince Message", true, desc = "Toggles the prince killed message.")
    private val batMessageToggle by BooleanSetting("Send Bat Message", true, desc = "Toggles the bat killed message.")
    private val chestHighlight by BooleanSetting("Mimic Chest Highlight", true, desc = "Colors a trapped chest standing where a secret chest belongs.")
    private val chestColor by ColorSetting("Mimic Chest Color", Color(255, 0, 0, 0.75f), true, desc = "Color of the mimic chest.").withDependency { chestHighlight }

    private const val PRINCE_LINE = "A Prince falls. +1 Bonus Score"
    private const val BAT_LINE = "A Bat has been slain. +1 Bonus Score"

    /** Trapped chests on secret chest spots, found as the rooms come in, with the box drawn on each. */
    private val chests = HashMap<BlockPos, AABB>()
    private val roomsSeen: MutableSet<DungeonRoom> = Collections.newSetFromMap(IdentityHashMap())
    private var ticks = 0

    init {
        on<LevelEvent.Load> { chests.clear() }

        on<TickEvent.End> {
            if (!chestHighlight || !DungeonUtils.inClear || DungeonUtils.mimicKilled || ++ticks % 10 != 0) return@on
            findChests()
        }

        on<RenderExtractEvent> {
            if (chests.isEmpty() || !chestHighlight || !DungeonUtils.inClear || DungeonUtils.mimicKilled) return@on
            for (box in chests.values) drawFilledBox(box, chestColor, depth = true)
        }

        on<EntityEvent.Event> {
            if (id != 3.toByte() || DungeonUtils.mimicKilled || !DungeonUtils.isFloor(6, 7) || !DungeonUtils.inClear) return@on
            if ((entity as? Zombie)?.isBaby == true) {
                if (mimicMessageToggle) sendCommand("pc Mimic Killed!")
                DungeonListener.dungeonStats.mimicKilled = true
            }
        }

        on<MessageEvent.Chat> {
            if (!DungeonUtils.inClear) return@on
            if (message == PRINCE_LINE && !DungeonUtils.princeKilled) {
                if (princeMessageToggle) sendCommand("pc Prince Killed!")
                DungeonListener.dungeonStats.princeKilled = true
            } else if (message == BAT_LINE) {
                if (batMessageToggle) sendCommand("pc Bat Killed!")
                mc.player?.name?.string?.let { DungeonListener.dungeonStats.batKillers.add(it) }
                DungeonListener.dungeonStats.batKilled = true
                DungeonUtils.updateScore()
            }
        }
    }

    private fun findChests() {
        val level = mc.level ?: return
        chests.keys.removeIf { !level.getBlockState(it).`is`(Blocks.TRAPPED_CHEST) }
        roomsSeen.clear()
        for (room in DungeonScan.rooms) {
            if (!roomsSeen.add(room)) continue
            for (pos in SecretWaypoints.chestSpots(room)) {
                if (pos in chests || !level.isLoaded(pos) || !level.getBlockState(pos).`is`(Blocks.TRAPPED_CHEST)) continue
                chests[pos] = AABB(pos.x + 0.06, pos.y.toDouble(), pos.z + 0.06, pos.x + 0.94, pos.y + 0.88, pos.z + 0.94)
            }
        }
    }
}
