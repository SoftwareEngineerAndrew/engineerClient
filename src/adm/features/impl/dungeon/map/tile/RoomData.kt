package com.adm.features.impl.dungeon.map.tile

import com.adm.utils.IVec2
import com.adm.utils.JsonResourceLoader
import com.google.gson.annotations.SerializedName
import net.minecraft.resources.Identifier

@ConsistentCopyVisibility
data class RoomData private constructor(
    val name: String,
    val type: RoomType,
    val shape: RoomShape,
    val cores: List<Int>,
    val crypts: Int,
    val maxSecrets: Int,
    val trappedChests: Int,
) {
    companion object {
        private val byCore = HashMap<Int, RoomData>(1024).also { index ->
            val all = JsonResourceLoader.loadJson<ArrayList<RoomData>>(Identifier.fromNamespaceAndPath("engineerclient", "rooms.json"))
            all?.forEach { room -> for (core in room.cores) index[core] = room }
        }

        fun getRoomData(core: Int): RoomData? = byCore[core]
    }
}

enum class RoomType(val mapColor: Byte) {
    ENTRANCE(30),
    FAIRY(82),
    NORMAL(63),
    RARE(63),
    BLOOD(18),
    CHAMPION(74),
    UNKNOWN(85),
    PUZZLE(66),
    TRAP(62),
    UNDISCOVERED(-1);

    companion object {
        // a later entry wins a shared colour, so 63 reads as RARE until the room's data says otherwise
        private val byColor = arrayOfNulls<RoomType>(256).also { table -> for (t in entries) table[t.mapColor.toInt() and 0xFF] = t }

        fun fromMapColor(color: Byte): RoomType? = byColor[color.toInt() and 0xFF]
    }
}

enum class RoomShape(val tileAmount: Int) {
    @SerializedName("L")
    L(3),
    @SerializedName("1x1")
    OneByOne(1),
    @SerializedName("1x2")
    TwoByOne(2),
    @SerializedName("1x3")
    ThreeByOne(3),
    @SerializedName("1x4")
    FourByOne(4),
    @SerializedName("2x2")
    TwoByTwo(4);
}

enum class RoomRotation(val dx: Int, val dz: Int) {
    NORTH(15, 15),
    SOUTH(-15, -15),
    WEST(15, -15),
    EAST(-15, 15),
}

data class DungeonTile(
    val position: IVec2,
    var room: DungeonRoom? = null
)

enum class MapCheckmark {
    NONE, WHITE, GREEN, RED, QUESTION_MARK, UNDISCOVERED;

    companion object {
        fun fromMapColor(color: Byte): MapCheckmark? = when (color.toInt()) {
            30 -> GREEN
            34 -> WHITE
            18 -> RED
            119 -> QUESTION_MARK
            else -> null
        }
    }
}
