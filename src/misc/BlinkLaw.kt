package com.engineerclient.misc

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Where an AOTE / AOTV / Hyperion blink lands: alldata's blink law (content/11-items/01-teleports.md, #items.blink-ray and
 * #items.blink-refusal; analyses/items-teleports-br.mjs, LAW = tall + body). 92.4% exactly right over 12,312 BR blinks.
 *
 * - From the eye (feet + 1.62 standing, 1.27 sneaking) along the look, whole-block steps p_i = eye + i·look, i = 1..D.
 * - A step is good when its cell isn't solid (1.8 material) and doesn't sit on a block taller than a cell, and you fit:
 *   feet in the cell under the step's cell, or the step's cell itself when the one under is solid or tall; the feet cell
 *   and the one above it not solid, and the feet cell not on a tall block.
 * - The first bad step ends the walk; you land on the last good one at (x.5, feet, z.5). None good: refused.
 * - A landing in your own column is refused when it's your spot, under 1.5 straight up, or under 0.55 straight down.
 *
 * No Minecraft here: [solid] is the cell's 1.8 material, [top] the cell's highest collision top (0 when none).
 */
object BlinkLaw {
    const val STAND_EYE = 1.62
    const val SNEAK_EYE = 1.27

    /** The look vector for a yaw and pitch in degrees (Minecraft's). */
    fun look(yaw: Float, pitch: Float): DoubleArray {
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        return doubleArrayOf(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /** The landing (feet) as x, y, z, or null when the blink is refused. */
    fun predict(
        fx: Double, fy: Double, fz: Double, yaw: Float, pitch: Float, sneaking: Boolean, distance: Int,
        solid: (Int, Int, Int) -> Boolean, top: (Int, Int, Int) -> Double,
    ): DoubleArray? {
        val d = look(yaw, pitch)
        val ex = fx; val ey = fy + if (sneaking) SNEAK_EYE else STAND_EYE; val ez = fz
        fun tall(x: Int, y: Int, z: Int) = top(x, y - 1, z) > 1.0
        fun feet(x: Int, y: Int, z: Int) = if (solid(x, y - 1, z) || tall(x, y - 1, z)) y else y - 1
        var land: DoubleArray? = null
        for (i in 1..distance) {
            val x = floor(ex + d[0] * i).toInt(); val y = floor(ey + d[1] * i).toInt(); val z = floor(ez + d[2] * i).toInt()
            if (solid(x, y, z) || tall(x, y, z)) break
            val f = feet(x, y, z)
            if (solid(x, f, z) || tall(x, f, z) || solid(x, f + 1, z)) break
            land = doubleArrayOf(x + 0.5, f.toDouble(), z + 0.5)
        }
        val to = land ?: return null
        if (to[0] == floor(fx) + 0.5 && to[2] == floor(fz) + 0.5) {
            val dy = to[1] - fy
            if (if (dy >= 0) dy < 1.5 else -dy < 0.55) return null
        }
        return to
    }

    /**
     * Hypixel 1.8 materials that aren't solid (alldata query/world.mjs NOT_SOLID), by block id without "minecraft:".
     * Anything else counts as solid.
     */
    private val NOT_SOLID = listOf(
        Regex("^(air|cave_air|void_air|water|lava|fire|soul_fire|nether_portal|end_portal|end_gateway)$"),
        Regex("(^|_)(torch|wall_torch)$"), Regex("^lever$"), Regex("_button$"), Regex("(^|_)rail$"), Regex("^redstone_wire$"),
        Regex("^tripwire(_hook)?$"), Regex("^ladder$"),
        Regex("(_head|_skull|_wall_head|_wall_skull)$"), Regex("^(flower_pot|potted_.*)$"), Regex("^(repeater|comparator)$"),
        Regex("_carpet$"), Regex("^snow$"), Regex("^vine$"),
        Regex("^(short_grass|tall_grass|grass|fern|large_fern|dead_bush|dandelion|poppy|blue_orchid|allium|azure_bluet|.*_tulip|oxeye_daisy|sunflower|lilac|rose_bush|peony|brown_mushroom|red_mushroom|wheat|carrots|potatoes|sugar_cane|lily_pad|.*_sapling|nether_wart|melon_stem|pumpkin_stem|attached_.*_stem|cocoa)$"),
    )

    fun isSolid(blockId: String): Boolean = NOT_SOLID.none { it.containsMatchIn(blockId) }
}
