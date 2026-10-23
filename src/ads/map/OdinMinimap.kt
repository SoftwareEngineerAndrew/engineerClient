package com.engineerclient.ads.map

import com.engineerclient.lcm.LcmMapInfo
import com.engineerclient.lcm.LcmStyle
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

/**
 * What the minimap takes from Odin when it's installed (only called then: [com.engineerclient.EngineerClient.odin]):
 * LCM Colors, whether a Prince is dead, and LCM Map Info's score, which comes from Odin's tab list stats.
 */
object OdinMinimap {
    fun lcmColor(type: RoomType): Int? = when (type) {
        RoomType.BLOOD -> LcmStyle.BLOOD
        RoomType.NORMAL -> LcmStyle.NORMAL
        RoomType.PUZZLE -> LcmStyle.PUZZLE
        RoomType.CHAMPION -> LcmStyle.CHAMPION
        RoomType.TRAP -> LcmStyle.TRAP
        RoomType.FAIRY -> LcmStyle.FAIRY
        RoomType.RARE -> LcmStyle.RARE
        RoomType.UNDISCOVERED, RoomType.UNKNOWN -> LcmStyle.UNOPENED
        else -> null
    }

    fun princeKilled(): Boolean = DungeonUtils.princeKilled

    /** LCM Map Info's score lines (dtMap's), or none if it can't work them out. */
    fun scoreLines(): List<String> = LcmMapInfo.scoreLines()
}
