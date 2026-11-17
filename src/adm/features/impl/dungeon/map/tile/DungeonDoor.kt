package com.adm.features.impl.dungeon.map.tile

import com.adm.utils.Color
import com.adm.utils.IVec2

class DungeonDoor(
    val position: IVec2,
    val rotation: DoorRotation,
    var type: DoorType,
    var color: Color,
) {
    val originTileIndex: Int
    val destinationTileIndex: Int
    val worldX: Int
    val worldZ: Int

    init {
        val step = rotation.offset
        originTileIndex = position.z * 6 + position.x
        destinationTileIndex = originTileIndex + step.z * 6 + step.x
        worldX = position.x * 32 - 185 + step.x * 16
        worldZ = position.z * 32 - 185 + step.z * 16
    }
}

enum class DoorType {
    Normal,
    Wither,
    Blood,
    Fairy;

    companion object {
        fun fromColor(color: Byte): DoorType = when (color) {
            WITHER_COLOR -> Wither
            RoomType.BLOOD.mapColor -> Blood
            RoomType.FAIRY.mapColor -> Fairy
            else -> Normal
        }

        private const val WITHER_COLOR: Byte = 119
    }
}

enum class DoorRotation(val offset: IVec2) {
    Horizontal(IVec2(1, 0)),
    Vertical(IVec2(0, 1)),
}
