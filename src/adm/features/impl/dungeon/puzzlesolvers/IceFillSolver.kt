package com.adm.features.impl.dungeon.puzzlesolvers

import com.adm.AdmMod.mc
import com.adm.events.BlockUpdateEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.RoomEnterEvent
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.render.Etherwarp
import com.adm.utils.Color
import com.adm.utils.JsonResourceLoader
import com.adm.utils.isEtherwarpItem
import com.adm.utils.itemId
import com.adm.utils.modMessage
import com.adm.utils.render.drawLine
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

object IceFillSolver {

    private val iceFillFloors = JsonResourceLoader.loadJson(Identifier.fromNamespaceAndPath("engineerclient", "puzzles/ice-fill-floors.json")) ?: IceFillData(emptyList(), emptyList(), emptyList())
    private val currentPatterns: ArrayList<Vec3> = ArrayList()

    private val floorCells: List<Set<BlockPos>> = listOf(Triple(14, 7, 3), Triple(13, 12, 5), Triple(12, 19, 7)).mapIndexed { i, (x0, z0, w) ->
        buildSet { for (x in x0 until x0 + w) for (z in z0 until z0 + w) add(BlockPos(x, 69 + i, z)); add(BlockPos(15, 69 + i, z0 + w)) }
    }
    private val heads = arrayOfNulls<BlockPos>(3)
    private var lastAlert = 0L

    fun onRenderWorld(event: RenderExtractEvent, color: Color) {
        if (currentPatterns.isNotEmpty() && DungeonUtils.currentRoomName == "Ice Fill")
            event.drawLine(currentPatterns, color, true)
    }

    fun onRoomEnter(event: RoomEnterEvent, optimizePatterns: Boolean) = with (event.room) {
        if (this?.data?.name != "Ice Fill" || currentPatterns.isNotEmpty()) return@with
        val patterns = if (optimizePatterns) iceFillFloors.hard else iceFillFloors.easy

        repeat(3) { index ->
            val floorIdentifiers = iceFillFloors.identifier[index]

            for (patternIndex in floorIdentifiers.indices) {
                if (isRealAir(floorIdentifiers[patternIndex][0]) && !isRealAir(floorIdentifiers[patternIndex][1])) {
                    currentPatterns.addAll(patterns[index][patternIndex].map { Vec3(getRealCoords(it)).add(0.5, 0.1, 0.5) })
                    return@repeat
                }
            }
            modMessage("§cFailed to scan floor $index")
        }
    }

    fun onBlockChange(event: BlockUpdateEvent) {
        val room = iceFillRoom() ?: return
        val floor = floorOf(room, event.pos) ?: return
        when {
            event.old.block == Blocks.ICE && event.updated.block == Blocks.PACKED_ICE -> heads[floor] = event.pos
            event.updated.isAir -> heads[floor] = null
        }
    }

    /** Right click: true holds back an etherwarp onto packed ice of an unfinished floor, or one the floor's check would read as a bad step. */
    @JvmStatic
    fun blocksEtherwarp(): Boolean {
        if (!PuzzleSolvers.enabled || !(PuzzleSolvers.iceFillBlockPacked || PuzzleSolvers.iceFillBlockBreak) || !DungeonUtils.inClear) return false
        val room = iceFillRoom() ?: return false
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val data = player.mainHandItem.isEtherwarpItem() ?: return false
        if (!player.isShiftKeyDown && data.itemId != "ETHERWARP_CONDUIT") return false

        val ether = Etherwarp.getEtherPos(player.position(), Etherwarp.getEtherwarpDistance(data), etherWarp = true)
        val target = ether.pos?.takeIf { ether.succeeded } ?: return false
        val floor = floorOf(room, target) ?: return false

        val reason = when (level.getBlockState(target).block) {
            Blocks.PACKED_ICE -> "packed ice".takeIf { PuzzleSolvers.iceFillBlockPacked && !isDone(room, floor) }
            Blocks.ICE -> "it would break the floor".takeIf { PuzzleSolvers.iceFillBlockBreak && breaksFloor(room, floor, target) }
            else -> null
        } ?: return false

        PuzzleSolvers.blocked()
        if (System.currentTimeMillis() - lastAlert > 1000) {
            lastAlert = System.currentTimeMillis()
            modMessage("§cBlocked etherwarp: $reason")
        }
        return true
    }

    // A warp from off the centre of the block you stand on reads as a skip or diagonal.
    private fun breaksFloor(room: DungeonRoom, floor: Int, target: BlockPos): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val stand = BlockPos.containing(player.x, player.y - 1, player.z)
        val onFloor = floorOf(room, stand) == floor
        val head = heads[floor] ?: stand.takeIf { onFloor && level.getBlockState(it).block == Blocks.PACKED_ICE } ?: return false
        if (onFloor && (abs(player.x - stand.x - 0.5) > 0.05 || abs(player.z - stand.z - 0.5) > 0.05)) return true
        return target.distManhattan(if (onFloor) stand else head) != 1
    }

    private fun isDone(room: DungeonRoom, floor: Int): Boolean {
        val level = mc.level ?: return true
        return floorCells[floor].none { cell ->
            val pos = room.getRealCoords(cell)
            level.getBlockState(pos).block == Blocks.ICE && level.getBlockState(pos.above()).isAir
        }
    }

    private fun floorOf(room: DungeonRoom, pos: BlockPos): Int? {
        val rel = room.getRelativeCoords(pos)
        return (rel.y - 69).takeIf { it in 0..2 && rel in floorCells[it] }
    }

    private fun iceFillRoom(): DungeonRoom? = DungeonUtils.currentRoom?.takeIf { it.data?.name == "Ice Fill" }

    private fun DungeonRoom.isRealAir(pos: BlockPos): Boolean =
        mc.level?.getBlockState(getRealCoords(pos))?.isAir == true

    fun reset() {
        currentPatterns.clear()
        heads.fill(null)
    }

    private data class IceFillData(
        val identifier: List<List<List<BlockPos>>>,
        val easy: List<List<List<BlockPos>>>,
        val hard: List<List<List<BlockPos>>>
    )
}
