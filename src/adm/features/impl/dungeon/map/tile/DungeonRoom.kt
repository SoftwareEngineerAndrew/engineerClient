package com.adm.features.impl.dungeon.map.tile

import com.adm.AdmMod.mc
import com.adm.features.impl.dungeon.map.RoomLabel
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints
import com.adm.utils.IVec2
import com.adm.utils.modMessage
import com.adm.utils.rotateAroundNorth
import com.adm.utils.rotateToNorth
import com.adm.utils.sortKey
import net.minecraft.core.BlockPos
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

class DungeonRoom(var type: RoomType, initialPosition: IVec2, var data: RoomData? = null) {
    val tiles: ArrayList<IVec2> = ArrayList(4)
    var topLeft: IVec2 = initialPosition
    var rotation: RoomRotation? = null
        set(value) {
            field = value
            placeCenter()
        }

    var shape: RoomShape = RoomShape.OneByOne

    var walkedInto: Boolean = false
    var clayPos: BlockPos? = null
    var highestBlock: Int? = null
    @Transient var waypoints: MutableSet<DungeonWaypoints.DungeonWaypoint> = mutableSetOf()

    var checkmark: MapCheckmark = MapCheckmark.UNDISCOVERED

    var isKnown1x1: Boolean = false
    var mapSeen: Boolean = false

    var center: IVec2? = null
        private set

    var foundSecrets: Int? = null
    var playerWalkedInto = false

    @Transient internal var label: RoomLabel? = null

    val isViewable: Boolean get() = walkedInto || checkmark != MapCheckmark.UNDISCOVERED
    val shouldShowName get() = walkedInto || playerWalkedInto
    val name: String? get() = data?.name

    fun addSegment(segment: DungeonTile) {
        val pos = segment.position
        for (i in tiles.indices) if (tiles[i] == pos) return
        tiles.add(pos)
        var minX = pos.x
        var minZ = pos.z
        for (t in tiles) {
            if (t.x < minX) minX = t.x
            if (t.z < minZ) minZ = t.z
        }
        topLeft = IVec2(minX, minZ)
        val height = highestBlock
        if (height != null && tiles.size == data?.shape?.tileAmount) inferLayout(height)
        placeCenter()
    }

    fun inferLayoutFromMap() {
        if (tiles.isEmpty()) return
        var minX = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (t in tiles) {
            if (t.x < minX) minX = t.x
            if (t.x > maxX) maxX = t.x
            if (t.z < minZ) minZ = t.z
            if (t.z > maxZ) maxZ = t.z
        }
        val square = maxX - minX == 1 && maxZ - minZ == 1
        shape = when (tiles.size) {
            2 -> RoomShape.TwoByOne
            3 -> if (square) RoomShape.L else RoomShape.ThreeByOne
            4 -> if (square) RoomShape.TwoByTwo else RoomShape.FourByOne
            else -> RoomShape.OneByOne
        }
        orientation()?.let { rotation = it }
    }

    fun inferLayout(highestBlock: Int) {
        val known = data ?: return
        if (fairyLayout(highestBlock)) return
        shape = known.shape
        if (shape == RoomShape.OneByOne) {
            get1x1Rotation()
            return
        }
        val rot = orientation()
        val clay = rot?.let { claySpot(it, highestBlock) }
        if (rot == null || clay == null) {
            modMessage("Failed to resolve geometry for ${data?.name ?: type} at $topLeft with tiles $tiles")
            return
        }
        rotation = rot
        clayPos = clay
    }

    fun get1x1Rotation(): Boolean {
        if (shape != RoomShape.OneByOne) return false
        val y = highestBlock ?: return false
        if (fairyLayout(y)) return true
        val level = mc.level ?: return false
        val baseX = topLeft.x * 32 - 185
        val baseZ = topLeft.z * 32 - 185
        val probe = BlockPos.MutableBlockPos()
        for (rot in RoomRotation.entries) {
            probe.set(baseX + rot.dx, y, baseZ + rot.dz)
            if (level.getBlockState(probe).block === blueClay) {
                rotation = rot
                clayPos = probe.immutable()
                return true
            }
        }
        return false
    }

    fun getRelativeCoords(pos: BlockPos): BlockPos {
        val clay = clayPos ?: return BlockPos.ZERO
        val rot = rotation ?: return BlockPos.ZERO
        return pos.subtract(clay.atY(0)).rotateToNorth(rot)
    }

    fun getRealCoords(pos: BlockPos): BlockPos {
        val clay = clayPos ?: return BlockPos.ZERO
        val rot = rotation ?: return BlockPos.ZERO
        return pos.rotateAroundNorth(rot).offset(clay.x, 0, clay.z)
    }

    fun getRelativeAABB(aabb: AABB): AABB {
        val rot = rotation ?: return aabb
        return AABB(
            aabb.minPosition.add(-0.5, -0.5, -0.5).rotateToNorth(rot).add(0.5, 0.5, 0.5),
            aabb.maxPosition.add(-0.5, -0.5, -0.5).rotateToNorth(rot).add(0.5, 0.5, 0.5)
        )
    }

    fun getRealAABB(aabb: AABB): AABB {
        val rot = rotation ?: return aabb
        return AABB(
            aabb.maxPosition.add(-0.5, -0.5, -0.5).rotateAroundNorth(rot).add(0.5, 0.5, 0.5),
            aabb.minPosition.add(-0.5, -0.5, -0.5).rotateAroundNorth(rot).add(0.5, 0.5, 0.5)
        )
    }

    private fun placeCenter() {
        val rot = rotation
        if (rot == null) {
            val first = cornerTile(lowest = true) ?: return
            center = IVec2(first.x * 20 + 8, first.z * 20 + 8)
            return
        }
        var dx = 0
        var dz = 0
        when (shape) {
            RoomShape.OneByOne -> {}
            RoomShape.TwoByTwo -> { dx = 10; dz = 10 }
            RoomShape.L -> { dx = 10; dz = if (rot == RoomRotation.NORTH || rot == RoomRotation.WEST) 0 else 20 }
            else -> if (rot == RoomRotation.SOUTH) dx = (shape.tileAmount - 1) * 10 else dz = (shape.tileAmount - 1) * 10
        }
        center = IVec2(topLeft.x * 20 + 8 + dx, topLeft.z * 20 + 8 + dz)
    }

    private fun cornerTile(lowest: Boolean): IVec2? {
        if (tiles.isEmpty()) return null
        var best = tiles[0]
        for (i in 1 until tiles.size) {
            val t = tiles[i]
            if (if (lowest) t.sortKey < best.sortKey else t.sortKey > best.sortKey) best = t
        }
        return best
    }

    private fun lMiddle(far: IVec2): IVec2? {
        for (t in tiles) if (t != topLeft && t != far) return t
        return null
    }

    private fun orientation(): RoomRotation? {
        val far = cornerTile(lowest = false) ?: return null
        if (shape != RoomShape.L) return if (topLeft.x == far.x) RoomRotation.WEST else RoomRotation.SOUTH
        val middle = lMiddle(far) ?: return null
        return when {
            topLeft.x == far.x -> RoomRotation.EAST
            topLeft.z == far.z -> RoomRotation.WEST
            middle.x == topLeft.x -> RoomRotation.SOUTH
            else -> RoomRotation.NORTH
        }
    }

    private fun claySpot(rot: RoomRotation, y: Int): BlockPos? {
        val far = cornerTile(lowest = false) ?: return null
        val left = topLeft.x * 32 - 185
        val top = topLeft.z * 32 - 185
        if (shape != RoomShape.L) {
            return if (rot == RoomRotation.WEST) BlockPos(left + 15, y, top - 15) else BlockPos(left - 15, y, top - 15)
        }
        val middle = lMiddle(far) ?: return null
        val right = far.x * 32 - 185
        val bottom = far.z * 32 - 185
        return when (rot) {
            RoomRotation.EAST -> BlockPos(middle.x * 32 - 185 - 15, y, top + 15)
            RoomRotation.WEST -> BlockPos(right + 15, y, bottom - 15)
            RoomRotation.SOUTH -> BlockPos(left - 15, y, top - 15)
            RoomRotation.NORTH -> BlockPos(right + 15, y, bottom + 15)
        }
    }

    private fun fairyLayout(y: Int): Boolean {
        if (data?.name != "Fairy") return false
        clayPos = BlockPos(topLeft.x * 32 - 185 + RoomRotation.SOUTH.dx, y, topLeft.z * 32 - 185 + RoomRotation.SOUTH.dz)
        rotation = RoomRotation.SOUTH
        return true
    }

    private companion object {
        val blueClay: Block by lazy { Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE) }
    }
}
