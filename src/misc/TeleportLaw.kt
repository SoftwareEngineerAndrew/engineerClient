package com.engineerclient.misc

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Where a Hypixel teleport lands, from alldata (content/11-items/01-teleports.md; analyses/items-teleports-br.mjs). Both
 * take the look as a yaw and pitch, so a click can be worked out again later from another start (Zpew's re-prediction).
 * No Minecraft here: the world is read through [Cells].
 */
object TeleportLaw {
    const val STAND_EYE = 1.62
    const val SNEAK_EYE = 1.27

    /** One cell of the world: its block id (no "minecraft:") and the top of its collision shape (0 when it has none). */
    interface Cells {
        fun id(x: Int, y: Int, z: Int): String
        fun top(x: Int, y: Int, z: Int): Double

        /**
         * The cell's collision boxes, each (minX, minY, minZ, maxX, maxY, maxZ) relative to the cell (the blink's line check).
         * Default: one box over the whole cell up to [top], exact for full blocks and bottom slabs; a world that has the real
         * shapes (stairs, top slabs, fences) should give them.
         */
        fun boxes(x: Int, y: Int, z: Int): List<DoubleArray> {
            val t = top(x, y, z)
            return if (t > 0.0) listOf(doubleArrayOf(0.0, 0.0, 0.0, 1.0, t, 1.0)) else emptyList()
        }
    }

    /**
     * The eye the server casts a click from: its pose's, which takes a sneak change a tick after the input packet. [changed]: the
     * sneak changed in the click's own server tick, so the eye is the one before it ([sneaking] is the click's own sneak).
     */
    fun eye(sneaking: Boolean, changed: Boolean): Double = if (sneaking != changed) SNEAK_EYE else STAND_EYE

    /** The look vector for a yaw and pitch in degrees (Minecraft's). */
    fun look(yaw: Float, pitch: Float): DoubleArray {
        val y = Math.toRadians(yaw.toDouble()); val p = Math.toRadians(pitch.toDouble())
        return doubleArrayOf(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /**
     * The blink (AOTE, AOTV, Wither Impact): #items.blink-ray and #items.blink-refusal, LAW = tall + body + exact. 92.7%
     * exactly right over 13,443 BR casts (items-teleports-br's "line between steps checked exactly" row: 31 more blinks and
     * 14 more refusals right than without the line check).
     *
     * - From the eye (feet + 1.62 standing, 1.27 sneaking) along the look, whole-block steps p_i = eye + i·look, i = 1..D.
     * - The line from the step before (p_0 the eye) to the step must not enter a solid block's collision box: a corner the
     *   ray grazes between two steps ends the walk.
     * - A step is good when its cell isn't solid (1.8 material) and doesn't sit on a block taller than a cell, and you fit:
     *   feet in the cell under the step's cell, or the step's cell itself when the one under is solid or tall; the feet
     *   cell and the one above it not solid, and the feet cell not on a tall block.
     * - The first bad step ends the walk; you land on the last good one at (x.5, feet, z.5). None good: refused.
     * - A landing in your own column is refused when it's your spot, under 1.5 straight up, or under 0.55 straight down.
     * - [eye]: the eye height the server casts from when it isn't [sneaking]'s. The server reads its own pose, which takes a sneak
     *   change a tick later: a click sent in the same tick as letting go of sneak is cast from 1.27 (101279 and 165424, 2026-11-12:
     *   both blinks landed where 1.27's steps stop, 1 and 2 blocks short of 1.62's).
     */
    fun blink(fx: Double, fy: Double, fz: Double, yaw: Float, pitch: Float, sneaking: Boolean, distance: Int, w: Cells, eye: Double? = null): DoubleArray? {
        val d = look(yaw, pitch)
        val ey = fy + (eye ?: if (sneaking) SNEAK_EYE else STAND_EYE)
        fun solid(x: Int, y: Int, z: Int) = isSolid(w.id(x, y, z))
        fun tall(x: Int, y: Int, z: Int) = w.top(x, y - 1, z) > 1.0
        fun feet(x: Int, y: Int, z: Int) = if (solid(x, y - 1, z) || tall(x, y - 1, z)) y else y - 1
        fun at(i: Int) = doubleArrayOf(fx + d[0] * i, ey + d[1] * i, fz + d[2] * i)
        var land: DoubleArray? = null
        for (i in 1..distance) {
            if (lineHits(at(i - 1), at(i), w)) break
            val x = floor(fx + d[0] * i).toInt(); val y = floor(ey + d[1] * i).toInt(); val z = floor(fz + d[2] * i).toInt()
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

    /** Whether the segment [a] -> [b] enters the collision box of a solid block (items-teleports-br's lineHits). */
    private fun lineHits(a: DoubleArray, b: DoubleArray, w: Cells): Boolean {
        for (x in floor(min(a[0], b[0])).toInt()..floor(max(a[0], b[0])).toInt())
            for (y in floor(min(a[1], b[1])).toInt()..floor(max(a[1], b[1])).toInt())
                for (z in floor(min(a[2], b[2])).toInt()..floor(max(a[2], b[2])).toInt()) {
                    if (!isSolid(w.id(x, y, z))) continue
                    val cell = intArrayOf(x, y, z)
                    for (k in w.boxes(x, y, z)) {
                        var t0 = 0.0; var t1 = 1.0; var ok = true
                        for (n in 0..2) {
                            val lo = cell[n] + k[n]; val hi = cell[n] + k[n + 3]
                            val s = b[n] - a[n]
                            if (abs(s) < 1e-9) { ok = a[n] >= lo && a[n] <= hi; if (!ok) break; continue }
                            val u = (lo - a[n]) / s; val v = (hi - a[n]) / s
                            t0 = max(t0, min(u, v)); t1 = min(t1, max(u, v))
                            ok = t0 < t1
                            if (!ok) break
                        }
                        if (ok) return true
                    }
                }
        return false
    }

    /**
     * Etherwarp: #items.etherwarp-ray and #items.etherwarp-landing (the law: stop = 'page', free = 'noColl'). 98.1% exact
     * over 3,451 BR warps.
     *
     * - From the eye along the look, every cell the line passes through in order, up to [distance]. The eye is feet + [eye]:
     *   1.27 (the sneaking eye, #items.etherwarp-ray), but 1.62 ([STAND_EYE]) when the sneak reaches the server in the same
     *   tick as the click (its eye isn't sneaking yet): in a 120 ms log (2026-11-12) the three etherwarps cast that way (two
     *   sneaks sent with the click, one sent 8 ticks earlier but handled with it after a 3 s server freeze) all landed from
     *   1.62, while 41 casts with the sneak a tick or more earlier landed where only 1.27's ray goes. One more cast (sneak
     *   held 3 s) needed 1.54 or more and stays unexplained.
     * - The first cell that stops the ray is the target: any block with a collision box (carpets and lily pads too) and
     *   signs, banners, tripwire hooks and pressure plates; never heads, ladders, flower pots, buttons or levers.
     * - A sign, wall banner or tripwire hook is no floor: refused. Else you land on top (x.5, y + 1.05, z.5), a cell
     *   higher on a block taller than a cell, when the two cells there have no collision (a chest counts as free).
     */
    fun etherwarp(fx: Double, fy: Double, fz: Double, yaw: Float, pitch: Float, distance: Double, w: Cells, eye: Double = SNEAK_EYE): DoubleArray? {
        val d = look(yaw, pitch)
        val e = doubleArrayOf(fx, fy + eye, fz)
        val c = IntArray(3) { floor(e[it]).toInt() }
        val s = IntArray(3) { sign(d[it]).toInt() }
        val inv = DoubleArray(3) { if (d[it] != 0.0) abs(1 / d[it]) else Double.POSITIVE_INFINITY }
        val tn = DoubleArray(3) { k -> if (s[k] > 0) (c[k] + 1 - e[k]) * inv[k] else if (s[k] < 0) (e[k] - c[k]) * inv[k] else Double.POSITIVE_INFINITY }
        var hit: IntArray? = null
        while (true) {
            val k = if (tn[0] < tn[1]) (if (tn[0] < tn[2]) 0 else 2) else (if (tn[1] < tn[2]) 1 else 2)
            if (tn[k] > distance) break
            c[k] += s[k]; tn[k] += inv[k]
            if (stops(w.id(c[0], c[1], c[2]), w.top(c[0], c[1], c[2]))) { hit = c.copyOf(); break }
        }
        val (x, y, z) = hit ?: return null
        if (NO_FLOOR.containsMatchIn(w.id(x, y, z))) return null
        val f = if (w.top(x, y, z) > 1.0) y + 2 else y + 1
        for (k in 0..1) if (!free(w.id(x, f + k, z), w.top(x, f + k, z))) return null
        return doubleArrayOf(x + 0.5, f + 0.05, z + 0.5)
    }

    private val STOPS_ANYWAY = Regex("_sign$|_banner$|^tripwire_hook$|_pressure_plate$")
    private val NEVER_STOPS = Regex("(_head|_skull)$|^ladder$|^(flower_pot|potted_)|_button$|^lever$")
    private val NO_FLOOR = Regex("_sign$|_wall_banner$|^tripwire_hook$")
    private val CHEST = Regex("^(chest|trapped_chest|ender_chest)$")

    private fun stops(id: String, top: Double) = (STOPS_ANYWAY.containsMatchIn(id) || top > 0.0) && !NEVER_STOPS.containsMatchIn(id)
    private fun free(id: String, top: Double) = top <= 0.0 || CHEST.containsMatchIn(id)

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
