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
import com.adm.features.impl.dungeon.map.tile.RoomRotation
import com.adm.features.impl.dungeon.map.tile.RoomShape
import com.adm.utils.Color
import com.adm.utils.render.drawFilledBox
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.dungeon.DungeonListener
import com.adm.utils.skyblock.dungeon.DungeonUtils
import java.util.IdentityHashMap

object Mimic : Module(
    name = "Mimic",
    description = "Detects and announces Mimic, Prince and Bat kills in dungeons."
) {
    private val mimicMessageToggle by BooleanSetting("Send Mimic Message", true, desc = "Toggles the mimic killed message.")
    private val princeMessageToggle by BooleanSetting("Send Prince Message", true, desc = "Toggles the prince killed message.")
    private val batMessageToggle by BooleanSetting("Send Bat Message", true, desc = "Toggles the bat killed message.")
    private val chestHighlight by BooleanSetting("Mimic Chest Highlight", true, desc = "Colors a trapped chest standing where a secret chest belongs.")
    val chestHighlightOn get() = enabled && chestHighlight
    private val chestColor by ColorSetting("Mimic Chest Color", Color(255, 0, 0, 0.75f), true, desc = "Color of the mimic chest.").withDependency { chestHighlight }

    private const val BAT_LINE = "A Bat has been slain. +1 Bonus Score"

    /** Trapped chests on secret chest spots, found as the rooms come in, with the box drawn on each. */
    private val chests = HashMap<BlockPos, AABB>()
    /** Boxed the moment their block entity arrived, before their room's chest spots could say whether they are secret chests. */
    private val unconfirmed = HashSet<BlockPos>()
    private class RoomSpots(val tiles: Int, val rotation: RoomRotation?, val clay: BlockPos?, val spots: List<BlockPos>)
    private val roomSpots: MutableMap<DungeonRoom, RoomSpots> = IdentityHashMap()
    /** Chest spots already looked at while loaded; a trapped chest arriving there later comes through the block entity hook. */
    private val checked = HashSet<BlockPos>()
    /** Every room's chest spots have been looked at, so only hook arrivals are left to confirm. */
    private var settled = false
    private var ticks = 0

    init {
        on<LevelEvent.Load> {
            chests.clear()
            unconfirmed.clear()
            unsettle()
        }

        on<TickEvent.End> {
            if (!chestHighlight) { if (settled || roomSpots.isNotEmpty()) unsettle(); return@on }
            if (!DungeonUtils.inClear || DungeonUtils.mimicKilled || ++ticks % 10 != 0) return@on
            findChests()
        }

        on<RenderExtractEvent> {
            if (chests.isEmpty() || !chestHighlight || !DungeonUtils.inClear || DungeonUtils.mimicKilled) return@on
            for (box in chests.values) drawFilledBox(box, chestColor, depth = true)
        }

        on<MessageEvent.Chat> {
            if (!DungeonUtils.inClear) return@on
            if (message == BAT_LINE) {
                if (batMessageToggle && !DungeonListener.dungeonStats.batKilled) sendCommand("pc Bat Killed!")
                mc.player?.name?.string?.let { DungeonListener.dungeonStats.batKillers.add(it) }
                DungeonListener.dungeonStats.batKilled = true
                DungeonUtils.updateScore()
            }
        }
    }

    fun announcePrince() {
        if (enabled && princeMessageToggle) sendCommand("pc Prince Killed!")
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

    override fun onEnable() {
        super.onEnable()
        unsettle()
    }

    private fun unsettle() {
        settled = false
        roomSpots.clear()
        checked.clear()
    }

    private fun findChests() {
        val level = mc.level ?: return
        if (chests.isNotEmpty()) {
            val it = chests.keys.iterator()
            while (it.hasNext()) if (!level.getBlockState(it.next()).`is`(Blocks.TRAPPED_CHEST)) it.remove()
            unconfirmed.retainAll(chests.keys)
        }
        if (!settled) scanSpots(level)
        if (unconfirmed.isEmpty()) return
        val it = unconfirmed.iterator()
        while (it.hasNext()) {
            val pos = it.next()
            val room = roomAt(pos) ?: continue
            if (room.data == null) continue
            val spots = spotsOf(room)
            if (spots.isEmpty()) continue
            it.remove()
            if (pos !in spots) chests.remove(pos)
        }
    }

    private fun scanSpots(level: Level) {
        var done = true
        var packKnown = false
        for (room in DungeonScan.rooms) {
            val spots = spotsOf(room)
            if (spots.isNotEmpty()) packKnown = true
            for (pos in spots) {
                unconfirmed.remove(pos)
                if (pos in checked) continue
                if (!level.isLoaded(pos)) { done = false; continue }
                checked += pos
                if (pos !in chests && level.getBlockState(pos).`is`(Blocks.TRAPPED_CHEST)) chests[pos] = boxOf(pos)
            }
            if (spots.isEmpty() && !identified(room)) done = false
        }
        if (done && packKnown && DungeonScan.rooms.isNotEmpty()) {
            settled = true
            roomSpots.clear()
            checked.clear()
        }
    }

    // An empty spot list only means "no secret chests" once the room and its rotation are known.
    private fun identified(room: DungeonRoom): Boolean =
        room.data != null && room.rotation != null && (room.shape != RoomShape.OneByOne || room.clayPos != null || room.data?.name == "Fairy")

    private fun spotsOf(room: DungeonRoom): List<BlockPos> {
        val cached = roomSpots[room]
        if (cached != null && cached.spots.isNotEmpty() && cached.tiles == room.tiles.size && cached.rotation == room.rotation && cached.clay == room.clayPos) return cached.spots
        val spots = SecretWaypoints.chestSpots(room)
        if (!settled) roomSpots[room] = RoomSpots(room.tiles.size, room.rotation, room.clayPos, spots)
        return spots
    }

    private fun roomAt(pos: BlockPos): DungeonRoom? {
        val col = Math.floorDiv(pos.x + 200, 32)
        val row = Math.floorDiv(pos.z + 200, 32)
        if (col !in 0..5 || row !in 0..5) return null
        return DungeonScan.tiles.getOrNull(row * 6 + col)?.room
    }

    private fun boxOf(pos: BlockPos) = AABB(pos.x + 0.06, pos.y.toDouble(), pos.z + 0.06, pos.x + 0.94, pos.y + 0.88, pos.z + 0.94)
}
