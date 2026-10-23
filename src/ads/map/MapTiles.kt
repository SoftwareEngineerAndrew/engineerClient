// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.map

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import net.minecraft.core.BlockPos
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks

/** A tile of the 6 x 6 room grid (x, z from 0), or any other pair of grid numbers (Odin's IVec2). */
data class GridPos(val x: Int, val z: Int) {
    operator fun plus(o: GridPos) = GridPos(x + o.x, z + o.z)
    operator fun times(n: Int) = GridPos(x * n, z * n)
    operator fun div(n: Int) = GridPos(x / n, z / n)
    val sortKey: Int get() = x * 1000 + z
}

enum class RoomType(val mapColor: Byte) {
    ENTRANCE(30), FAIRY(82), NORMAL(63), RARE(63), BLOOD(18), CHAMPION(74), UNKNOWN(85), PUZZLE(66), TRAP(62), UNDISCOVERED(-1);

    companion object {
        private val BY_COLOR = entries.associateBy { it.mapColor }
        fun fromMapColor(color: Byte) = BY_COLOR[color]
    }
}

enum class RoomShape(val tileAmount: Int) {
    @SerializedName("L") L(3),
    @SerializedName("1x1") OneByOne(1),
    @SerializedName("1x2") TwoByOne(2),
    @SerializedName("1x3") ThreeByOne(3),
    @SerializedName("1x4") FourByOne(4),
    @SerializedName("2x2") TwoByTwo(4),
}

enum class RoomRotation(val dx: Int, val dz: Int) {
    NORTH(15, 15), SOUTH(-15, -15), WEST(15, -15), EAST(-15, 15),
}

enum class MapCheckmark {
    NONE, WHITE, GREEN, RED, QUESTION_MARK, UNDISCOVERED;

    companion object {
        fun fromMapColor(color: Byte): MapCheckmark? = when (color.toInt()) {
            34 -> WHITE
            30 -> GREEN
            18 -> RED
            119 -> QUESTION_MARK
            else -> null
        }
    }
}

enum class DoorType {
    Normal, Wither, Blood, Fairy;

    companion object {
        fun fromColor(color: Byte): DoorType = when (color) {
            119.toByte() -> Wither
            RoomType.BLOOD.mapColor -> Blood
            RoomType.FAIRY.mapColor -> Fairy
            else -> Normal
        }
    }
}

enum class DoorRotation(val offset: GridPos) { Horizontal(GridPos(1, 0)), Vertical(GridPos(0, 1)) }

class DungeonTile(val position: GridPos, var room: DungeonRoom? = null) {
    operator fun component1() = position
    operator fun component2() = room
}

/** A door between tile [position] and the tile right of it or below it ([rotation]). */
class DungeonDoor(val position: GridPos, val rotation: DoorRotation, var type: DoorType) {
    val originTileIndex = position.x + position.z * 6
    val destinationTileIndex = position.x + rotation.offset.x + (position.z + rotation.offset.z) * 6
}

/** One room as Odin's room data has it (its name, secrets and shape), found from the hash of its core column. */
class RoomData(
    val name: String,
    val type: RoomType,
    val shape: RoomShape,
    val cores: List<Int>?,
    val crypts: Int,
    val maxSecrets: Int,
) {
    companion object {
        /** Odin's rooms.json (the copy in ADS's assets), by core hash. */
        private val byCore: Map<Int, RoomData> by lazy {
            try {
                val stream = RoomData::class.java.getResourceAsStream("/assets/engineerclient/ads/map/rooms.json") ?: return@lazy emptyMap()
                val list: List<RoomData> = stream.reader().use { Gson().fromJson(it, object : TypeToken<List<RoomData>>() {}.type) }
                buildMap { for (room in list) for (core in room.cores.orEmpty()) put(core, room) }
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ads] minimap: couldn't read the room data, rooms will have no names", t)
                emptyMap()
            }
        }

        fun of(core: Int): RoomData? = byCore[core]
    }
}

/** One room on the map: its tiles, what the map and the world scan know of it, and where its blue clay corner is. */
class DungeonRoom(var type: RoomType, initialPosition: GridPos, var data: RoomData? = null) {
    val tiles = ArrayList<GridPos>(4)
    var topLeft: GridPos = initialPosition
    var rotation: RoomRotation? = null
        set(value) {
            field = value
            recalculateCenter()
        }

    var shape: RoomShape = RoomShape.OneByOne
    var walkedInto = false
    var clayPos: BlockPos? = null
    var highestBlock: Int? = null
    var checkmark = MapCheckmark.UNDISCOVERED
    var isKnown1x1 = false
    var mapSeen = false

    /** Where the room's name goes, in map units (20 a tile). */
    var center: GridPos? = null
        private set

    var foundSecrets: Int? = null

    val isViewable: Boolean get() = walkedInto || checkmark != MapCheckmark.UNDISCOVERED
    val name: String? get() = data?.name

    fun addSegment(segment: DungeonTile) {
        if (tiles.contains(segment.position)) return
        tiles.add(segment.position)
        topLeft = GridPos(tiles.minOf { it.x }, tiles.minOf { it.z })
        highestBlock?.let { if (tiles.size == data?.shape?.tileAmount) inferLayout(it) }
        recalculateCenter()
    }

    private fun recalculateCenter() {
        val rot = rotation
        if (rot == null) {
            val anchor = tiles.minByOrNull { it.sortKey } ?: return
            center = GridPos(anchor.x * 20 + 8, anchor.z * 20 + 8)
            return
        }
        val (offX, offZ) = when (shape) {
            RoomShape.OneByOne -> 0 to 0
            RoomShape.TwoByTwo -> 10 to 10
            RoomShape.L -> if (rot == RoomRotation.NORTH || rot == RoomRotation.WEST) 10 to 0 else 10 to 20
            else -> {
                val span = (shape.tileAmount - 1) * 10
                if (rot == RoomRotation.SOUTH) span to 0 else 0 to span
            }
        }
        center = GridPos(topLeft.x * 20 + 8 + offX, topLeft.z * 20 + 8 + offZ)
    }

    fun inferLayoutFromMap() {
        val minX = tiles.minOf { it.x }; val minZ = tiles.minOf { it.z }
        val maxX = tiles.maxOf { it.x }; val maxZ = tiles.maxOf { it.z }
        shape = when (tiles.size) {
            1 -> RoomShape.OneByOne
            2 -> RoomShape.TwoByOne
            3 -> if (maxX - minX == 1 && maxZ - minZ == 1) RoomShape.L else RoomShape.ThreeByOne
            4 -> if (maxX - minX == 1 && maxZ - minZ == 1) RoomShape.TwoByTwo else RoomShape.FourByOne
            else -> RoomShape.OneByOne
        }
        resolveGeometryRotation()?.let { rotation = it }
    }

    fun inferLayout(highestBlock: Int) {
        val roomData = data ?: return
        if (applyFairyFallback(highestBlock)) return
        shape = roomData.shape
        if (shape == RoomShape.OneByOne) {
            get1x1Rotation()
            return
        }
        val rot = resolveGeometryRotation() ?: return
        val pos = resolveClayPos(rot, highestBlock) ?: return
        rotation = rot
        clayPos = pos
    }

    private fun resolveGeometryRotation(): RoomRotation? {
        val bottomRight = tiles.maxByOrNull { it.sortKey } ?: return null
        return if (shape == RoomShape.L) {
            val other = tiles.find { it != topLeft && it != bottomRight } ?: return null
            when {
                topLeft.x == bottomRight.x -> RoomRotation.EAST
                topLeft.z == bottomRight.z -> RoomRotation.WEST
                other.x == topLeft.x -> RoomRotation.SOUTH
                else -> RoomRotation.NORTH
            }
        } else if (topLeft.x == bottomRight.x) RoomRotation.WEST else RoomRotation.SOUTH
    }

    private fun resolveClayPos(rotation: RoomRotation, height: Int): BlockPos? {
        val bottomRight = tiles.maxByOrNull { it.sortKey } ?: return null
        val (tlX, tlZ) = realPosition(topLeft.x, topLeft.z)
        val (brX, brZ) = realPosition(bottomRight.x, bottomRight.z)
        return if (shape == RoomShape.L) {
            val other = tiles.find { it != topLeft && it != bottomRight } ?: return null
            val (otX, _) = realPosition(other.x, other.z)
            when (rotation) {
                RoomRotation.EAST -> BlockPos(otX - 15, height, tlZ + 15)
                RoomRotation.WEST -> BlockPos(brX + 15, height, brZ - 15)
                RoomRotation.SOUTH -> BlockPos(tlX - 15, height, tlZ - 15)
                RoomRotation.NORTH -> BlockPos(brX + 15, height, brZ + 15)
            }
        } else if (rotation == RoomRotation.WEST) BlockPos(tlX + 15, height, tlZ - 15)
        else BlockPos(tlX - 15, height, tlZ - 15)
    }

    /** A 1x1's turn: which corner its blue clay is in. */
    fun get1x1Rotation(): Boolean {
        if (shape != RoomShape.OneByOne) return false
        val y = highestBlock ?: return false
        if (applyFairyFallback(y)) return true
        val level = mc.level ?: return false
        for (rot in RoomRotation.entries) {
            val pos = clayProbePos(rot, y)
            if (level.getBlockState(pos).block == Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE)) {
                rotation = rot
                clayPos = pos
                return true
            }
        }
        return false
    }

    private fun applyFairyFallback(highestBlock: Int): Boolean {
        if (data?.name != "Fairy") return false
        clayPos = clayProbePos(RoomRotation.SOUTH, highestBlock)
        rotation = RoomRotation.SOUTH
        return true
    }

    private fun clayProbePos(rotation: RoomRotation, y: Int): BlockPos {
        val (x, z) = realPosition(topLeft.x, topLeft.z)
        return BlockPos(x + rotation.dx, y, z + rotation.dz)
    }

    /** The middle of tile (x, z) in the world. */
    private fun realPosition(x: Int, z: Int) = x * 32 - 185 to z * 32 - 185
}
