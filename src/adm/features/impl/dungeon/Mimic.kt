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
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.dungeon.DungeonListener
import com.adm.utils.skyblock.dungeon.DungeonUtils
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
    /** Boxed the moment their block entity arrived, before their room's chest spots could say whether they are secret chests. */
    private val unconfirmed = HashSet<BlockPos>()
    private val roomsSeen: MutableSet<DungeonRoom> = Collections.newSetFromMap(IdentityHashMap())
    private var ticks = 0

    init {
        on<LevelEvent.Load> {
            chests.clear()
            unconfirmed.clear()
        }

        on<TickEvent.End> {
            if (!chestHighlight || !DungeonUtils.inClear || DungeonUtils.mimicKilled || ++ticks % 10 != 0) return@on
            findChests()
        }

        on<RenderExtractEvent> {
            if (chests.isEmpty() || !chestHighlight || !DungeonUtils.inClear || DungeonUtils.mimicKilled) return@on
            for (box in chests.values) drawFilledBox(box, chestColor, depth = true)
        }

        on<MessageEvent.Chat> {
            if (!DungeonUtils.inClear) return@on
            if (message == PRINCE_LINE && !DungeonUtils.princeKilled) {
                if (princeMessageToggle) sendCommand("pc Prince Killed!")
                DungeonListener.dungeonStats.princeKilled = true
            } else if (message == BAT_LINE) {
                if (batMessageToggle && !DungeonListener.dungeonStats.batKilled) sendCommand("pc Bat Killed!")
                mc.player?.name?.string?.let { DungeonListener.dungeonStats.batKillers.add(it) }
                DungeonListener.dungeonStats.batKilled = true
                DungeonUtils.updateScore()
            }
        }
    }

    fun announceMimic() {
        if (enabled && mimicMessageToggle) sendCommand("pc Mimic Killed!")
    }

    @JvmStatic
    fun trappedChestLoaded(pos: BlockPos) {
        if (!enabled || !chestHighlight || !DungeonUtils.inClear || DungeonUtils.mimicKilled || pos in chests) return
        val p = pos.immutable()
        chests[p] = boxOf(p)
        unconfirmed += p
    }

    private fun findChests() {
        val level = mc.level ?: return
        chests.keys.removeIf { !level.getBlockState(it).`is`(Blocks.TRAPPED_CHEST) }
        unconfirmed.retainAll(chests.keys)
        roomsSeen.clear()
        for (room in DungeonScan.rooms) {
            if (!roomsSeen.add(room)) continue
            for (pos in SecretWaypoints.chestSpots(room)) {
                unconfirmed.remove(pos)
                if (pos in chests || !level.isLoaded(pos) || !level.getBlockState(pos).`is`(Blocks.TRAPPED_CHEST)) continue
                chests[pos] = boxOf(pos)
            }
        }
        if (unconfirmed.isEmpty()) return
        val it = unconfirmed.iterator()
        while (it.hasNext()) {
            val pos = it.next()
            val room = roomAt(pos) ?: continue
            if (room.data == null || SecretWaypoints.chestSpots(room).isEmpty()) continue
            it.remove()
            chests.remove(pos)
        }
    }

    private fun roomAt(pos: BlockPos): DungeonRoom? {
        val col = Math.floorDiv(pos.x + 200, 32)
        val row = Math.floorDiv(pos.z + 200, 32)
        if (col !in 0..5 || row !in 0..5) return null
        return DungeonScan.tiles.getOrNull(row * 6 + col)?.room
    }

    private fun boxOf(pos: BlockPos) = AABB(pos.x + 0.06, pos.y.toDouble(), pos.z + 0.06, pos.x + 0.94, pos.y + 0.88, pos.z + 0.94)
}
