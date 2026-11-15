package com.odtheking.odin.features.impl.dungeon

import com.engineerclient.dungeon.SecretWaypoints
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawFilledBox
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.world.entity.monster.zombie.Zombie

object Mimic : Module(
    name = "Mimic",
    description = "Detects and announces Mimic, Prince and Bat kills in dungeons."
) {
    private val mimicMessageToggle by BooleanSetting("Send Mimic Message", true, desc = "Toggles the mimic killed message.")
    private val princeMessageToggle by BooleanSetting("Send Prince Message", true, desc = "Toggles the prince killed message.")
    private val batMessageToggle by BooleanSetting("Send Bat Message", true, desc = "Toggles the bat killed message.")
    private val chestHighlight by BooleanSetting("Mimic Chest Highlight", true, desc = "Colors a trapped chest standing where a secret chest belongs.")
    private val chestColor by ColorSetting("Mimic Chest Color", Color(255, 0, 0, 0.75f), true, desc = "Color of the mimic chest.").withDependency { chestHighlight }

    /** Trapped chests on secret chest spots, found as the rooms come in. */
    private val chests = HashSet<BlockPos>()
    private var ticks = 0

    private val princeRegex = Regex("^A Prince falls\\. \\+1 Bonus Score$")
    private val batRegex = Regex("^A Bat has been slain\\. \\+1 Bonus Score$")

    init {
        on<LevelEvent.Load> { chests.clear() }

        on<TickEvent.End> {
            if (!chestHighlight || !DungeonUtils.inClear || DungeonUtils.mimicKilled || ++ticks % 10 != 0) return@on
            val level = mc.level ?: return@on
            chests.removeIf { !level.getBlockState(it).`is`(Blocks.TRAPPED_CHEST) }
            for (room in DungeonScan.rooms.distinct()) for (pos in SecretWaypoints.chestSpots(room))
                if (level.isLoaded(pos) && level.getBlockState(pos).`is`(Blocks.TRAPPED_CHEST)) chests += pos
        }

        on<RenderExtractEvent> {
            if (!chestHighlight || !DungeonUtils.inClear || DungeonUtils.mimicKilled) return@on
            for (p in chests) drawFilledBox(AABB(p.x + 0.06, p.y.toDouble(), p.z + 0.06, p.x + 0.94, p.y + 0.88, p.z + 0.94), chestColor, depth = true)
        }

        on<EntityEvent.Event> {
            if (DungeonUtils.mimicKilled || !DungeonUtils.isFloor(6, 7) || !DungeonUtils.inClear) return@on
            if (id == 3.toByte() && (entity as? Zombie)?.isBaby == true) {
                if (mimicMessageToggle) sendCommand("pc Mimic Killed!")
                DungeonListener.dungeonStats.mimicKilled = true
            }
        }

        on<MessageEvent.Chat> {
            if (DungeonUtils.inClear) when {
                !DungeonUtils.princeKilled && message.matches(princeRegex) -> {
                    if (princeMessageToggle) sendCommand("pc Prince Killed!")
                    DungeonListener.dungeonStats.princeKilled = true
                }

                message.matches(batRegex) -> {
                    if (batMessageToggle) sendCommand("pc Bat Killed!")
                    mc.player?.name?.string?.let { DungeonListener.dungeonStats.batKillers.add(it) }
                    DungeonListener.dungeonStats.batKilled = true
                    DungeonUtils.updateScore()
                }
            }
        }
    }
}