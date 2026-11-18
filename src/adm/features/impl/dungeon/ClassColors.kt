package com.adm.features.impl.dungeon

import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.skyblock.dungeon.DungeonClass

object ClassColors : Module(
    name = "Class Colors",
    description = "The colour of each dungeon class, used everywhere classes are coloured.",
    key = null,
    toggled = true,
) {
    private val archer by ColorSetting("Archer", Colors.MINECRAFT_RED, desc = "Archer's colour.")
    private val berserk by ColorSetting("Berserk", Colors.MINECRAFT_GOLD, desc = "Berserk's colour.")
    private val healer by ColorSetting("Healer", Colors.MINECRAFT_LIGHT_PURPLE, desc = "Healer's colour.")
    private val mage by ColorSetting("Mage", Colors.MINECRAFT_AQUA, desc = "Mage's colour.")
    private val tank by ColorSetting("Tank", Colors.MINECRAFT_DARK_GREEN, desc = "Tank's colour.")

    private const val CODES = "0123456789abcdef"
    private val RGB = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    )

    fun color(clazz: DungeonClass): Color = when (clazz) {
        DungeonClass.ARCHER -> archer
        DungeonClass.BERSERK -> berserk
        DungeonClass.HEALER -> healer
        DungeonClass.MAGE -> mage
        DungeonClass.TANK -> tank
        DungeonClass.EMPTY -> Colors.WHITE
    }

    /** The chat colour code nearest the class's colour. */
    fun code(clazz: DungeonClass): Char {
        val c = color(clazz)
        var best = 'f'
        var bestDist = Int.MAX_VALUE
        for (i in CODES.indices) {
            val rgb = RGB[i]
            val dr = (rgb shr 16 and 255) - c.red
            val dg = (rgb shr 8 and 255) - c.green
            val db = (rgb and 255) - c.blue
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; best = CODES[i] }
        }
        return best
    }
}
