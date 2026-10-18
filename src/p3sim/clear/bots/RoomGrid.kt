package com.engineerclient.p3sim.clear.bots

import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The floor of one placed room, as a bot can walk it: for every block column of the room, whether somebody can stand there (a block to stand on, and two
 * free blocks above it) and so walk through it. World columns, row-major from ([x0], [z0]). Pure; built from the room's block volume ([RoomGrids]) or by
 * hand in tests. It answers where a walker may go ([walkable], [lineClear]), where to go next ([randomPoint]) and the way round what is in between ([path]).
 *
 * A walker is 0.6 wide: a straight line is clear when the cell it is in, and the cells 0.3 blocks to every side of it, are all walkable.
 */
class RoomGrid(val x0: Int, val z0: Int, val w: Int, val d: Int, private val open: BooleanArray) {
    init { require(open.size == w * d) { "a ${w}x$d grid needs ${w * d} cells, not ${open.size}" } }

    private fun idx(x: Int, z: Int) = (z - z0) * w + (x - x0)

    fun walkable(x: Int, z: Int): Boolean = x >= x0 && z >= z0 && x < x0 + w && z < z0 + d && open[idx(x, z)]

    fun walkable(p: Pt): Boolean = walkable(floor(p.x).toInt(), floor(p.z).toInt())

    /** How many columns can be walked. */
    val openCount: Int by lazy { open.count { it } }

    private val cells: IntArray by lazy { IntArray(openCount).also { out -> var k = 0; for (i in open.indices) if (open[i]) out[k++] = i } }

    /** Connected piece of the floor (8-way, no cutting corners) each column belongs to, 0 for a column that cannot be walked. */
    private val label: IntArray by lazy {
        val lab = IntArray(open.size)
        var next = 0
        val stack = ArrayDeque<Int>()
        for (start in open.indices) {
            if (!open[start] || lab[start] != 0) continue
            next++
            lab[start] = next
            stack.add(start)
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                for (n in neighbours(c)) if (lab[n.first] == 0) { lab[n.first] = next; stack.add(n.first) }
            }
        }
        lab
    }

    /** How many columns the biggest connected piece of floor has. */
    val mainSize: Int by lazy { label.filter { it > 0 }.groupingBy { it }.eachCount().values.maxOrNull() ?: 0 }

    /** The label of the biggest connected piece of floor (0 when there is no floor). */
    private val mainLabel: Int by lazy { label.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0 }

    /** Whether the column [p] is on the biggest connected piece of floor. */
    fun onMain(p: Pt): Boolean = walkable(p) && label[idx(floor(p.x).toInt(), floor(p.z).toInt())] == mainLabel

    /** The open neighbours of cell [c] with the cost of the step; a diagonal needs both columns beside it open. */
    private fun neighbours(c: Int): List<Pair<Int, Double>> {
        val cx = c % w; val cz = c / w
        val out = ArrayList<Pair<Int, Double>>(8)
        for (dz in -1..1) for (dx in -1..1) {
            if (dx == 0 && dz == 0) continue
            val nx = cx + dx; val nz = cz + dz
            if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue
            val n = nz * w + nx
            if (!open[n]) continue
            if (dx != 0 && dz != 0 && (!open[cz * w + nx] || !open[nz * w + cx])) continue
            out += n to (if (dx != 0 && dz != 0) SQRT2 else 1.0)
        }
        return out
    }

    /**
     * The middle of the walkable column [p] stands in, or of the nearest walkable column within [radius] blocks; null when there is none. A column on the
     * biggest piece of floor is preferred to one on a small island (a pillar's ring) when one is within the radius, so a walker never starts shut in.
     */
    fun nearest(p: Pt, radius: Int = 10): Pt? {
        val bx = floor(p.x).toInt(); val bz = floor(p.z).toInt()
        var best: Pt? = null; var bestD = Double.MAX_VALUE
        var bestMain: Pt? = null; var bestMainD = Double.MAX_VALUE
        for (dz in -radius..radius) for (dx in -radius..radius) {
            if (!walkable(bx + dx, bz + dz)) continue
            val c = Pt(bx + dx + 0.5, bz + dz + 0.5)
            val dist = c.dist(p)
            if (dist < bestD) { bestD = dist; best = c }
            if (dist < bestMainD && label[idx(bx + dx, bz + dz)] == mainLabel) { bestMainD = dist; bestMain = c }
        }
        // On a walkable column of a small island with the big floor in reach: go to the big floor.
        return if (bestMain != null && (best == null || label[idx(floor(best.x).toInt(), floor(best.z).toInt())] != mainLabel) && bestMainD <= radius) bestMain else best
    }

    /** The middle of the column of piece [lab] of the floor that is nearest to [p], or null when the piece has none. */
    private fun nearestIn(p: Pt, lab: Int): Pt? {
        var best = -1; var bestD = Double.MAX_VALUE
        for (c in cells) {
            if (label[c] != lab) continue
            val dx = x0 + c % w + 0.5 - p.x; val dz = z0 + c / w + 0.5 - p.z
            val dist = dx * dx + dz * dz
            if (dist < bestD) { bestD = dist; best = c }
        }
        return if (best < 0) null else Pt(x0 + best % w + 0.5, z0 + best / w + 0.5)
    }

    /** Whether a walker can go straight from [a] to [b]: every point of the line, and 0.3 blocks to each side of it, stands on walkable columns. */
    fun lineClear(a: Pt, b: Pt): Boolean {
        val len = a.dist(b)
        val steps = max(1, Math.ceil(len / 0.25).toInt())
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            val x = a.x + (b.x - a.x) * f; val z = a.z + (b.z - a.z) * f
            if (!walkable(floor(x - CLEAR).toInt(), floor(z - CLEAR).toInt()) || !walkable(floor(x + CLEAR).toInt(), floor(z - CLEAR).toInt()) ||
                !walkable(floor(x - CLEAR).toInt(), floor(z + CLEAR).toInt()) || !walkable(floor(x + CLEAR).toInt(), floor(z + CLEAR).toInt())) return false
        }
        return true
    }

    /** A random walkable place that [from] can get to, between [minDist] and [maxDist] blocks from it when one is found in a few tries, else any. Null when [from] has none. */
    fun randomPoint(from: Pt, random: Random, minDist: Double = 0.0, maxDist: Double = Double.MAX_VALUE): Pt? {
        val start = nearest(from) ?: return null
        val lab = label[idx(floor(start.x).toInt(), floor(start.z).toInt())]
        var any: Pt? = null
        repeat(24) {
            val c = cells[random.nextInt(cells.size)]
            if (label[c] != lab) return@repeat
            val p = Pt(x0 + c % w + 0.5, z0 + c / w + 0.5)
            any = p
            val dist = p.dist(from)
            if (dist in minDist..maxDist) return p
        }
        return any
    }

    /**
     * The way from [a] to [b] round whatever is in between: the points to pass after [a], the last one being [b] (or, when [b] cannot be stood on or is on
     * a piece of floor that [a] cannot walk to, the nearest place [a] can get to: a closet behind a wall is approached as far as the wall). A straight line
     * when it is clear; else the shortest path over the columns, pulled taut. Null only when [a] is off the floor altogether.
     */
    fun path(a: Pt, b: Pt): List<Pt>? {
        val from = nearest(a) ?: return null
        val lab = label[idx(floor(from.x).toInt(), floor(from.z).toInt())]
        val to = (if (walkable(b) && label[idx(floor(b.x).toInt(), floor(b.z).toInt())] == lab) b else nearestIn(b, lab)) ?: return null
        if (lineClear(from, to)) return listOf(to)
        val s = idx(floor(from.x).toInt(), floor(from.z).toInt()); val g = idx(floor(to.x).toInt(), floor(to.z).toInt())
        val cost = DoubleArray(open.size) { Double.MAX_VALUE }
        val prev = IntArray(open.size) { -1 }
        val gx = g % w; val gz = g / w
        fun h(c: Int): Double { val dx = abs(c % w - gx); val dz = abs(c / w - gz); return (dx + dz) + (SQRT2 - 2) * min(dx, dz) }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        cost[s] = 0.0
        queue.add(h(s) to s)
        while (queue.isNotEmpty()) {
            val (f, c) = queue.poll()
            if (c == g) break
            if (f > cost[c] + h(c) + 1e-9) continue
            for ((n, step) in neighbours(c)) {
                val nc = cost[c] + step
                if (nc < cost[n]) { cost[n] = nc; prev[n] = c; queue.add(nc + h(n) to n) }
            }
        }
        if (prev[g] < 0 && s != g) return null
        val cellsOnPath = ArrayList<Pt>()
        var c = g
        while (c != s && c >= 0) { cellsOnPath += Pt(x0 + c % w + 0.5, z0 + c / w + 0.5); c = prev[c] }
        cellsOnPath += from
        cellsOnPath.reverse()
        // Pull it taut: from each point, go straight to the farthest later point that is in clear sight.
        val out = ArrayList<Pt>()
        var i = 0
        while (i < cellsOnPath.size - 1) {
            var j = cellsOnPath.size - 1
            while (j > i + 1 && !lineClear(cellsOnPath[i], cellsOnPath[j])) j--
            out += cellsOnPath[j]
            i = j
        }
        if (out.isEmpty() || out.last().dist(to) > 1e-6) out += to
        else out[out.size - 1] = to
        return out
    }

    companion object {
        private val SQRT2 = sqrt(2.0)

        /** Half the width of a walker. */
        const val CLEAR = 0.3
    }
}

/**
 * Whether a block state string can be stood on or walked through, for [RoomGrids]. The pack's states are plain strings
 * (`minecraft:stone_stairs[facing=east,half=bottom]`). Liquids and fire cannot be stood on or in; plants, carpets, plates, buttons, torches, signs and the like
 * are walked through; a slab or a stair is a step; everything else is in the way.
 */
object FloorRules {
    private val LIQUID = setOf("lava", "water", "fire", "soul_fire", "magma_block", "cactus", "powder_snow", "bubble_column", "sweet_berry_bush", "wither_rose")
    private val SOFT_EXACT = setOf(
        "grass", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "seagrass", "tall_seagrass", "kelp", "kelp_plant", "snow", "vine", "lever", "tripwire",
        "tripwire_hook", "string", "redstone_wire", "repeater", "comparator", "ladder", "flower_pot", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
        "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony", "red_mushroom", "brown_mushroom", "glow_lichen", "sculk_vein",
        "lantern", "soul_lantern", "candle", "moss_carpet", "pale_moss_carpet", "lily_pad", "sugar_cane", "wheat", "nether_wart", "torchflower", "pink_petals",
    )
    private val SOFT_SUFFIX = listOf(
        "_carpet", "_pressure_plate", "_button", "torch", "_sign", "_hanging_sign", "rail", "_head", "_skull", "_banner", "_sapling", "_tulip", "_roots", "_candle",
    )
    private val IN_THE_WAY_SUFFIX = listOf("_wall", "_fence", "fence_gate", "_pane", "iron_bars", "_door", "trapdoor", "chain", "barrier")

    private fun name(state: String): String = state.substringBefore('[').removePrefix("minecraft:")

    fun isAir(state: String): Boolean = name(state).let { it == "air" || it == "cave_air" || it == "void_air" }

    fun isLiquid(state: String): Boolean = name(state) in LIQUID

    private fun soft(n: String): Boolean = n in SOFT_EXACT || n.startsWith("potted_") || SOFT_SUFFIX.any { n.endsWith(it) }

    /** A block a walker passes through (or stands in): air, plants, the small things that sit on a floor, a bottom slab or a stair (a step). */
    fun passable(state: String): Boolean {
        if (isAir(state)) return true
        val n = name(state)
        if (n in LIQUID) return false
        if (IN_THE_WAY_SUFFIX.any { n.endsWith(it) }) return false
        if (n.endsWith("_slab")) return state.contains("type=bottom")
        if (n.endsWith("_stairs")) return true
        return soft(n)
    }

    /** A block that holds a walker up: solid (stone, planks, a slab, a stair), not a liquid and not one of the soft things. */
    fun supports(state: String): Boolean {
        if (isAir(state)) return false
        val n = name(state)
        if (n in LIQUID || soft(n) || n == "barrier") return false
        return true
    }
}

/** Makes the [RoomGrid] of a placed room from the room's block volume. */
object RoomGrids {
    /** The feet height of a walker on the dungeon's floor (the floor blocks are y 68: MEASURED, the same floor the rest of the module assumes). */
    const val FEET_Y = 69

    /** A floor with fewer walkable columns than this at the usual level is searched for another level (a room built up or down from the usual floor). */
    private const val USUAL_MIN = 150

    /**
     * The grid of the room [volume] stands for, placed by [placement]: a world column is walkable when it belongs to the room, the block under the feet
     * holds a walker up, and the blocks at the feet and above are passable. The feet are at [FEET_Y] (the dungeon's floor) unless that level hardly has a
     * floor in this room, when the level with the most walkable columns is used.
     */
    fun fromVolume(volume: RoomVolume, placement: Placement): RoomGrid {
        val supports = BooleanArray(volume.palette.size) { FloorRules.supports(volume.palette[it]) }
        val passable = BooleanArray(volume.palette.size) { FloorRules.passable(volume.palette[it]) }
        val usual = gridAt(volume, placement, FEET_Y, supports, passable)
        if (usual.openCount >= USUAL_MIN) return usual
        var best = usual
        for (y in (volume.y0 + 1)..(volume.y0 + volume.h - 3)) {
            if (y == FEET_Y) continue
            val g = gridAt(volume, placement, y, supports, passable)
            if (g.openCount > best.openCount) best = g
        }
        return best
    }

    private fun gridAt(volume: RoomVolume, placement: Placement, feetY: Int, supports: BooleanArray, passable: BooleanArray): RoomGrid {
        val x0 = placement.originX; val z0 = placement.originZ
        val w = placement.boxW; val d = placement.boxD
        val open = BooleanArray(w * d)
        for (wz in z0 until z0 + d) for (wx in x0 until x0 + w) {
            if (!placement.ownsColumn(wx, wz)) continue
            val (cx, cz) = placement.toCanonical(wx, wz) ?: continue
            if (!volume.contains(cx, feetY - 1, cz) || !volume.contains(cx, feetY + 1, cz)) continue
            open[(wz - z0) * w + (wx - x0)] =
                supports[volume.paletteIndexAt(cx, feetY - 1, cz)] && passable[volume.paletteIndexAt(cx, feetY, cz)] && passable[volume.paletteIndexAt(cx, feetY + 1, cz)]
        }
        return RoomGrid(x0, z0, w, d, open)
    }
}
