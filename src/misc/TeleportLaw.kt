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
         * Default: one box over the whole cell up to [top], exact for full blocks and bottom slabs only. A world that has the
         * block states should give [shapes]: a wall or fence as a whole cell 1.5 high ends a blink that passes beside it
         * (2026-11-12 misses: two AOTV blinks past cobblestone walls and stairs refused by the law, moved by the server).
         */
        fun boxes(x: Int, y: Int, z: Int): List<DoubleArray> {
            val t = top(x, y, z)
            return if (t > 0.0) listOf(doubleArrayOf(0.0, 0.0, 0.0, 1.0, t, 1.0)) else emptyList()
        }
    }

    private fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) = doubleArrayOf(x0, y0, z0, x1, y1, z1)
    private val FULL = listOf(box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
    private val HALF = mapOf(
        "north" to box(0.0, 0.0, 0.0, 1.0, 1.0, 0.5), "south" to box(0.0, 0.0, 0.5, 1.0, 1.0, 1.0),
        "west" to box(0.0, 0.0, 0.0, 0.5, 1.0, 1.0), "east" to box(0.5, 0.0, 0.0, 1.0, 1.0, 1.0),
    )
    private val LEFT = mapOf("north" to "west", "west" to "south", "south" to "east", "east" to "north")
    private val OPP = mapOf("north" to "south", "south" to "north", "east" to "west", "west" to "east")
    private fun inter(a: DoubleArray, b: DoubleArray) = DoubleArray(6) { if (it < 3) max(a[it], b[it]) else min(a[it], b[it]) }

    /**
     * A solid block's collision boxes on Hypixel's 1.8 server, relative to the cell (alldata query/world.mjs boxesOf, the shapes
     * the blink's line check was measured with): [id] without "minecraft:", [prop] a property of its modern state ("facing",
     * "half", "north", ...). Not-solid blocks get none: the line check only looks at solid ones.
     */
    fun shapes(id: String, prop: (String) -> String?): List<DoubleArray> {
        if (!isSolid(id)) return emptyList()
        fun on(k: String) = prop(k).let { it != null && it != "none" && it != "false" }
        return when {
            id.endsWith("_slab") -> when (prop("type")) {
                "double" -> FULL
                "top" -> listOf(box(0.0, 0.5, 0.0, 1.0, 1.0, 1.0))
                else -> listOf(box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0))
            }
            id.endsWith("_stairs") -> {
                val top = prop("half") == "top"
                val slab = if (top) box(0.0, 0.5, 0.0, 1.0, 1.0, 1.0) else box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)
                val f = prop("facing") ?: "north"
                val l = LEFT.getValue(f); val r = OPP.getValue(l)
                val h = HALF.getValue(f)
                val up = when (prop("shape")) {
                    "outer_left" -> listOf(inter(h, HALF.getValue(l)))
                    "outer_right" -> listOf(inter(h, HALF.getValue(r)))
                    "inner_left" -> listOf(h, inter(HALF.getValue(OPP.getValue(f)), HALF.getValue(l)))
                    "inner_right" -> listOf(h, inter(HALF.getValue(OPP.getValue(f)), HALF.getValue(r)))
                    else -> listOf(h)
                }
                listOf(slab) + up.map { it.copyOf().also { b -> b[1] = if (top) 0.0 else 0.5; b[4] = if (top) 0.5 else 1.0 } }
            }
            id.endsWith("_wall") -> {
                val n = on("north"); val s = on("south"); val w = on("west"); val e = on("east")
                var x0 = 0.25; var x1 = 0.75; var z0 = 0.25; var z1 = 0.75
                if (n) z0 = 0.0; if (s) z1 = 1.0; if (w) x0 = 0.0; if (e) x1 = 1.0
                if (n && s && !w && !e) { x0 = 0.3125; x1 = 0.6875 } else if (!n && !s && w && e) { z0 = 0.3125; z1 = 0.6875 }
                listOf(box(x0, 0.0, z0, x1, 1.5, z1))
            }
            id.endsWith("_fence") -> {
                val n = on("north"); val s = on("south"); val w = on("west"); val e = on("east")
                val out = mutableListOf(box(0.375, 0.0, 0.375, 0.625, 1.5, 0.625))
                if (n || s) out += box(0.375, 0.0, if (n) 0.0 else 0.375, 0.625, 1.5, if (s) 1.0 else 0.625)
                if (w || e) out += box(if (w) 0.0 else 0.375, 0.0, 0.375, if (e) 1.0 else 0.625, 1.5, 0.625)
                out
            }
            id.endsWith("_fence_gate") -> when {
                prop("open") == "true" -> emptyList()
                prop("facing") == "north" || prop("facing") == "south" -> listOf(box(0.0, 0.0, 0.375, 1.0, 1.5, 0.625))
                else -> listOf(box(0.375, 0.0, 0.0, 0.625, 1.5, 1.0))
            }
            id == "iron_bars" || id.endsWith("glass_pane") -> {
                val n = on("north"); val s = on("south"); val w = on("west"); val e = on("east"); val any = n || s || w || e
                val out = mutableListOf<DoubleArray>()
                if ((!w || !e) && any) { if (w) out += box(0.0, 0.0, 0.4375, 0.5, 1.0, 0.5625) else if (e) out += box(0.5, 0.0, 0.4375, 1.0, 1.0, 0.5625) }
                else out += box(0.0, 0.0, 0.4375, 1.0, 1.0, 0.5625)
                if ((!n || !s) && any) { if (n) out += box(0.4375, 0.0, 0.0, 0.5625, 1.0, 0.5) else if (s) out += box(0.4375, 0.0, 0.5, 0.5625, 1.0, 1.0) }
                else out += box(0.4375, 0.0, 0.0, 0.5625, 1.0, 1.0)
                out
            }
            id.endsWith("_trapdoor") -> {
                val t = 0.1875
                if (prop("open") == "true") listOf(when (prop("facing")) {
                    "north" -> box(0.0, 0.0, 1 - t, 1.0, 1.0, 1.0); "south" -> box(0.0, 0.0, 0.0, 1.0, 1.0, t)
                    "west" -> box(1 - t, 0.0, 0.0, 1.0, 1.0, 1.0); else -> box(0.0, 0.0, 0.0, t, 1.0, 1.0)
                }) else listOf(if (prop("half") == "top") box(0.0, 1 - t, 0.0, 1.0, 1.0, 1.0) else box(0.0, 0.0, 0.0, 1.0, t, 1.0))
            }
            id.endsWith("_door") -> {
                val t = 0.1875; val r = prop("hinge") == "right"
                fun side(f: String?) = when (f) {
                    "east" -> box(0.0, 0.0, 0.0, t, 1.0, 1.0); "west" -> box(1 - t, 0.0, 0.0, 1.0, 1.0, 1.0)
                    "south" -> box(0.0, 0.0, 0.0, 1.0, 1.0, t); else -> box(0.0, 0.0, 1 - t, 1.0, 1.0, 1.0)
                }
                if (prop("open") != "true") listOf(side(prop("facing"))) else listOf(when (prop("facing")) {
                    "east" -> if (r) box(0.0, 0.0, 1 - t, 1.0, 1.0, 1.0) else box(0.0, 0.0, 0.0, 1.0, 1.0, t)
                    "west" -> if (r) box(0.0, 0.0, 0.0, 1.0, 1.0, t) else box(0.0, 0.0, 1 - t, 1.0, 1.0, 1.0)
                    "south" -> if (r) box(0.0, 0.0, 0.0, t, 1.0, 1.0) else box(1 - t, 0.0, 0.0, 1.0, 1.0, 1.0)
                    else -> if (r) box(1 - t, 0.0, 0.0, 1.0, 1.0, 1.0) else box(0.0, 0.0, 0.0, t, 1.0, 1.0)
                })
            }
            CHEST.containsMatchIn(id) -> listOf(box(0.0625, 0.0, 0.0625, 0.9375, 0.875, 0.9375))
            id == "hopper" || id == "cauldron" || id == "water_cauldron" || id == "lava_cauldron" -> listOf(
                box(0.0, 0.0, 0.0, 1.0, if (id == "hopper") 0.625 else 0.3125, 1.0), box(0.0, 0.0, 0.0, 0.125, 1.0, 1.0),
                box(0.0, 0.0, 0.0, 1.0, 1.0, 0.125), box(0.875, 0.0, 0.0, 1.0, 1.0, 1.0), box(0.0, 0.0, 0.875, 1.0, 1.0, 1.0),
            )
            id.endsWith("anvil") -> listOf(if (prop("facing") == "east" || prop("facing") == "west") box(0.0, 0.0, 0.125, 1.0, 1.0, 0.875) else box(0.125, 0.0, 0.0, 0.875, 1.0, 1.0))
            id == "end_portal_frame" -> listOf(box(0.0, 0.0, 0.0, 1.0, 0.8125, 1.0)) + (if (prop("eye") == "true") listOf(box(0.3125, 0.8125, 0.3125, 0.6875, 1.0, 0.6875)) else emptyList())
            id == "enchanting_table" -> listOf(box(0.0, 0.0, 0.0, 1.0, 0.75, 1.0))
            id.endsWith("daylight_detector") -> listOf(box(0.0, 0.0, 0.0, 1.0, 0.375, 1.0))
            id == "brewing_stand" -> listOf(box(0.4375, 0.0, 0.4375, 0.5625, 0.875, 0.5625), box(0.0, 0.0, 0.0, 1.0, 0.125, 1.0))
            id == "cake" -> listOf(box(0.0625 + 0.125 * (prop("bites")?.toIntOrNull() ?: 0), 0.0, 0.0625, 0.9375, 0.5, 0.9375))
            id.endsWith("_bed") -> listOf(box(0.0, 0.0, 0.0, 1.0, 0.5625, 1.0))
            id == "soul_sand" -> listOf(box(0.0, 0.0, 0.0, 1.0, 0.875, 1.0))
            id == "dragon_egg" -> listOf(box(0.0625, 0.0, 0.0625, 0.9375, 1.0, 0.9375))
            id == "cactus" -> listOf(box(0.0625, 0.0, 0.0625, 0.9375, 0.9375, 0.9375))
            id == "cobweb" || id.endsWith("_sign") || id.endsWith("_banner") || id.endsWith("_pressure_plate") || id == "moving_piston" -> emptyList()
            else -> FULL
        }
    }

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
     * - A step whose cell is solid (1.8 material) or sits on a block taller than a cell ends the walk.
     * - A step you fit in is a landing: feet in the cell under the step's cell, or the step's cell itself when the one under
     *   is solid or tall; the feet cell and the one above it not solid, and the feet cell not on a tall block. A step you
     *   don't fit in is passed over and the walk goes on (alldata ends the walk there): four blinks through a one-high gap in
     *   an iron bar wall went through it (2026-11-12 misses), and none in that capture stopped at one.
     * - You land on the last landing at (x.5, feet, z.5). None: refused.
     * - A landing in your own column is refused when it's your spot, under 1.5 straight up, or under 0.55 straight down.
     * - [eye]: another eye height to cast from (a test's variant); null is [sneaking]'s. A click right after letting go of sneak
     *   still casts from 1.62: two such blinks did (2026-11-12 misses), against two that landed where 1.27's steps stop, so the
     *   client can't tell which pose the server has and keeps alldata's.
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
            if (solid(x, f, z) || tall(x, f, z) || solid(x, f + 1, z)) continue
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
     *   1.27 (the sneaking eye, #items.etherwarp-ray). A sneak sent the tick before the click doesn't change it: in a 120 ms
     *   log three such etherwarps landed from 1.62, but in the 2026-11-12 misses four did from 1.27 (one of them refused, as
     *   1.27's ray is), so whether the server has the sneak's pose yet is timing the client can't see.
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
