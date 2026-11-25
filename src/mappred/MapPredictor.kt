package com.engineerclient.mappred

import kotlin.math.roundToInt

/** A room's kind, from its colour on the map. */
enum class Kind { NORMAL, ENTRANCE, PUZZLE, TRAP, YELLOW, BLOOD, FAIRY, RARE }

/** Room shapes; the five big ones first (their order is the name pools'). */
enum class Shape(val size: Int) { TWO(2), THREE(3), FOUR(4), BOX(4), L(3), ONE(1) }

/** What a tile can turn out to be. */
object Cls {
    const val BIG = 0      // part of a big normal room
    const val NORMAL = 1   // a normal 1x1
    const val PUZZLE = 2
    const val TRAP = 3
    const val YELLOW = 4
    const val BLOOD = 5
    const val FAIRY = 6
    const val RARE = 7
    const val COUNT = 8
}

/** What the map shows between two side-by-side tiles. */
object Edge {
    const val UNKNOWN: Byte = 0  // neither side discovered
    const val NONE: Byte = 1     // a wall
    const val DOOR: Byte = 2
    const val WITHER: Byte = 3
    const val BLOOD: Byte = 4
    const val SAME: Byte = 5     // the same room
    const val FAIRY: Byte = 6    // a door touching the fairy: a wither door on the path, or a plain one
    fun isDoor(k: Byte) = k == DOOR || k == WITHER || k == BLOOD || k == FAIRY
}

/**
 * The discovered part of a [w] x [h] dungeon map: the rooms the map shows (tile = z * w + x), the
 * edges between tiles, and the tab list's puzzles. Only what the map shows goes in. [rules] are the
 * floor's; tiles outside its grid are no part of the dungeon.
 */
class MapView(val w: Int, val h: Int, val puzzles: Int, val rules: FloorRules = FloorRules.F7) {
    val roomAt = IntArray(w * h) { -1 }
    val kinds = ArrayList<Kind>()
    val shapes = ArrayList<Shape>()
    val roomTiles = ArrayList<IntArray>()
    /** Each discovered room's name when someone has been in it (null when nobody has). */
    val names = ArrayList<String?>()
    /** The dungeon's secret totals the tab list allows ([SecretNames.totals]); empty when unknown. */
    var secretTotals = IntArray(0)
    /** The tab list's puzzle lines in its order: each puzzle's name, null while it's "???" (nobody has been in it); empty when unread. */
    var puzzleSlots: List<String?> = emptyList()
    private val edges = ByteArray(w * h * 2)

    /** Adds a discovered room; null when its tiles aren't one of the room shapes. */
    fun addRoom(kind: Kind, tiles: IntArray, name: String? = null): Int? {
        val shape = shapeOf(tiles, w) ?: return null
        if (tiles.any { it !in 0 until w * h || roomAt[it] >= 0 }) return null
        val id = kinds.size
        kinds += kind; shapes += shape; roomTiles += tiles.copyOf(); names += name
        for (t in tiles) roomAt[t] = id
        return id
    }

    private fun edgeId(a: Int, b: Int): Int = when (b - a) {
        1 -> a * 2
        w -> a * 2 + 1
        -1 -> b * 2
        else -> b * 2 + 1
    }

    fun edge(a: Int, b: Int): Byte = edges[edgeId(a, b)]
    fun setEdge(a: Int, b: Int, k: Byte) { edges[edgeId(a, b)] = k }

    /** Every edge with a discovered tile on one side: the same room, a wall, or the door [doorAt] gives (or null). */
    fun closeEdges(doorAt: (Int, Int) -> Byte?) {
        for (t in 0 until w * h) for (n in intArrayOf(if (t % w + 1 < w) t + 1 else -1, if (t / w + 1 < h) t + w else -1)) {
            if (n < 0) continue
            val a = roomAt[t]; val b = roomAt[n]
            setEdge(t, n, when {
                a < 0 && b < 0 -> Edge.UNKNOWN
                a == b -> Edge.SAME
                else -> doorAt(t, n) ?: Edge.NONE
            })
        }
    }

    /**
     * Only the rooms the entrance reaches through doors between discovered rooms (this view itself when that's all
     * of them). A room can show on the map before the room it's reached through - in 8% of real runs, for 3 s at
     * the median and up to most of a minute - and no door tree joins two parts with one unknown room between them
     * twice, so the cut-off rooms are left out (as unknown) until the rest of the way shows.
     */
    fun connectedPart(): MapView {
        val ent = kinds.indexOf(Kind.ENTRANCE); if (ent < 0) return this
        val seen = BooleanArray(kinds.size); seen[ent] = true
        val queue = ArrayDeque(listOf(ent))
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            for (t in roomTiles[i]) for (nb in neighbours(t)) {
                val o = roomAt[nb]
                if (o < 0 || seen[o] || !Edge.isDoor(edge(t, nb))) continue
                seen[o] = true; queue.addLast(o)
            }
        }
        if (seen.all { it }) return this
        val out = MapView(w, h, puzzles, rules)
        for (i in kinds.indices) if (seen[i]) out.addRoom(kinds[i], roomTiles[i], names[i])
        out.secretTotals = secretTotals; out.puzzleSlots = puzzleSlots
        out.closeEdges { a, b -> edge(a, b).takeIf { Edge.isDoor(it) } }
        return out
    }

    internal fun neighbours(t: Int): IntArray {
        val x = t % w; val z = t / w; val o = ArrayList<Int>(4)
        if (x + 1 < w) o += t + 1; if (x > 0) o += t - 1; if (z + 1 < h) o += t + w; if (z > 0) o += t - w
        return o.toIntArray()
    }

    /** Changes whenever anything the predictor reads changes. */
    fun key(): String = buildString {
        append(w).append('x').append(h).append(':').append(rules.floor).append(':').append(puzzles).append(':')
        for (i in kinds.indices) append(kinds[i].ordinal).append(roomTiles[i].joinToString(",")).append(names[i] ?: "").append(';')
        append(secretTotals.joinToString(",")).append(':')
        append(puzzleSlots.joinToString(",") { it ?: "?" }).append(':')
        append(edges.joinToString(""))
    }

    companion object {
        fun shapeOf(tiles: IntArray, w: Int): Shape? {
            if (tiles.isEmpty()) return null
            val xs = tiles.map { it % w }; val zs = tiles.map { it / w }
            val bw = xs.max() - xs.min() + 1; val bh = zs.max() - zs.min() + 1
            if (tiles.toSet().size != tiles.size) return null
            return when (tiles.size) {
                1 -> Shape.ONE
                2 -> if (bw * bh == 2) Shape.TWO else null
                3 -> if (bw == 2 && bh == 2) Shape.L else if (bw * bh == 3) Shape.THREE else null
                4 -> if (bw == 2 && bh == 2) Shape.BOX else if (bw * bh == 4) Shape.FOUR else null
                else -> null
            }
        }
    }
}

/**
 * One door from the discovered rooms into the unknown, and what its side of the map (its subtree) holds: rooms, tiles,
 * rooms of each [Cls], and [secrets], its rooms' average secrets by their pools ([MapStats.meanSecrets]; [splitSecrets]
 * fits them to the tab's secret total).
 */
class Split(val tile: Int, val door: Byte, val rooms: Float, val tiles: Float, val expect: FloatArray, val secrets: Float = 0f)

/** A big room on these exact tiles, and how likely. */
class BigRoom(val tiles: IntArray, val chance: Float)

/**
 * A door between unknown tiles [a] and [b], and how likely ([chance]); of that, how likely it is a
 * wither door (on the way to blood), the blood door, or a door of the fairy room.
 */
class PredictedDoor(val a: Int, val b: Int, val chance: Float, val wither: Float, val blood: Float, val fairy: Float)

/** An unknown room on these tiles with this name (from the dungeon's secret total), and how likely (from 0.1%). */
class NamedRoom(val tiles: IntArray, val name: RoomName, val chance: Float)

/**
 * The prediction: for each unknown tile the chance of each [Cls] (null for discovered tiles and tiles no
 * room can be on; on a puzzle column tile they add up to the chance there's a room at all), for each door
 * into the unknown what is behind it, and the big rooms from 5% sure (likeliest first).
 */
class Prediction(val w: Int, val h: Int, val probs: Array<FloatArray?>, val splits: List<Split>,
                 val bigRooms: List<BigRoom> = emptyList(), val namedRooms: List<NamedRoom> = emptyList(),
                 val doors: List<PredictedDoor> = emptyList(), val raw: Array<FloatArray?> = probs)

/** What a [Prediction] says once read against its map: the names the secret total leaves ([certainNames]) and the like. */
class MapPredictor(private val view: MapView, private val nameFactor: (RoomName) -> Double = { 1.0 }) {
    private val w = view.w
    private val h = view.h
    private val n = w * h
    private val rules = view.rules
    /** Every tile a room can still be on: the main grid's unknown tiles and the puzzle column's that can hold one. */
    private val cells: IntArray
    private var bad = false
    /** Names from the secret total. */
    private var secrets: SecretNames? = null

    init {
        // on F4-F6 the puzzle column east of the main grid: its rooms have one door, to the west, so a tile with a wall there is empty
        val all = ArrayList<Int>(); val entered = ArrayList<Int>(); var unknown = 0
        for (t in 0 until n) {
            val x = t % w; val z = t / w
            if (view.roomAt[t] >= 0 || z >= rules.h) continue
            if (x < rules.w) { all += t; entered += t; unknown++ }
            else if (rules.puzzleColumn && x == rules.w) when {
                view.roomAt[t - 1] < 0 -> all += t
                Edge.isDoor(view.edge(t - 1, t)) -> { all += t; entered += t }
            }
        }
        cells = all.toIntArray()
        var entries = 0
        for (t in entered) {
            val doors = view.neighbours(t).count { view.roomAt[it] >= 0 && Edge.isDoor(view.edge(t, it)) }
            // two doors into one unknown tile: no door tree does that
            if (doors > 1) bad = true
            if (doors > 0) entries++
        }
        if (entries == 0 && unknown > 0) bad = true
        setUpSecrets()
    }

    /**
     * The secret total's rule: the named rooms' secrets are known, what they leave is what the unnamed ones
     * (discovered or not) hold. Off when the total isn't known yet or a named room isn't on the list.
     */
    private fun setUpSecrets() {
        if (view.secretTotals.isEmpty() || n > 64) return
        val byName = MapStats.NAMES.associateBy { it.name }
        val used = HashSet<String>(); var known = 0
        for (i in view.kinds.indices) {
            val name = view.names[i] ?: fixedName(view.kinds[i]) ?: continue
            val rn = byName[name] ?: return
            if (!used.add(name)) return
            known += rn.secrets
        }
        val targets = view.secretTotals.map { it - known }.filter { it >= 0 }.toIntArray()
        if (targets.isEmpty()) return
        secrets = SecretNames(used, targets, rules.missingNames, nameFactor)
    }

    /**
     * Every unnamed room's names from the secret total, however many there are, once the map is known for sure: every
     * tile discovered, or [p] (this view's prediction) sure of every unknown room's size and place, and of its type
     * except which dead end is which (puzzle, trap, yellow, rare: that moves no other room). Those dead ends' types are
     * tried every way that keeps the floor's count of each, by their chances and how well the names then fit the total.
     * Each room (its tiles) with each name that fits and its chance (from 0.1%), as [Prediction.namedRooms] has the last
     * few; null while the map isn't known (or there's no total, a named room isn't on the list, or no names fit it: a
     * misread).
     */
    fun certainNames(p: Prediction?): List<NamedRoom>? {
        val sn = secrets ?: return null
        if (bad || n > 64) return null
        val rooms = ArrayList<IntArray>(); val pools = ArrayList<Int>()
        // dead ends sure to be one but not which kind: their place in rooms (pools gets a stand-in) and each kind's chance
        val open = ArrayList<Int>(); val openChances = ArrayList<FloatArray>()
        for (i in view.kinds.indices) if (view.names[i] == null && fixedName(view.kinds[i]) == null) { rooms += view.roomTiles[i]; pools += poolOfKnown(i) }
        if (cells.isNotEmpty()) {
            val raw = (p ?: return null).raw
            val big = HashMap<Int, IntArray>()
            for (b in p.bigRooms) if (b.chance >= CERTAIN) for (t in b.tiles) big[t] = b.tiles
            val taken = HashSet<Int>()
            for (t in cells) {
                val q = raw[t] ?: continue
                val room = q.sum()
                if (room <= 1 - CERTAIN) continue // a puzzle column tile with no room
                if (room < CERTAIN) return null
                if (q[Cls.BIG] >= CERTAIN) {
                    val b = big[t] ?: return null
                    if (taken.add(b.min())) { rooms += b; pools += (MapView.shapeOf(b, w) ?: return null).ordinal }
                    continue
                }
                val c = (0 until Cls.COUNT).maxBy { q[it] }
                if (c != Cls.BIG && q[c] >= CERTAIN) { rooms += intArrayOf(t); pools += poolOfCls(c, Shape.ONE); continue }
                if (DEAD_ENDS.sumOf { q[it].toDouble() } < CERTAIN) return null
                open += rooms.size; openChances += FloatArray(Cls.COUNT) { if (it in DEAD_ENDS) q[it] / room else 0f }
                rooms += intArrayOf(t); pools += -1
            }
        }
        if (rooms.isEmpty()) return null
        // each way to type the open dead ends: as many of each kind as their chances add up to (a floor's count of
        // each is fixed), weighted by those chances
        val want = IntArray(Cls.COUNT) { c -> openChances.sumOf { it[c].toDouble() }.roundToInt() }
        val options = openChances.map { ch -> DEAD_ENDS.filter { ch[it] >= 0.001f } }
        if (options.fold(1.0) { a, o -> a * o.size } > MAX_TYPINGS) return null
        val chance = HashMap<Long, Double>()  // room * 256 + name index -> weight
        var total = 0.0
        val pick = IntArray(open.size)
        val counts = IntArray(Cls.COUNT)
        fun typing(j: Int, prior: Double) {
            if (j == open.size) {
                if ((0 until Cls.COUNT).any { counts[it] != want[it] }) return
                val ps = pools.toIntArray()
                for ((k, r) in open.withIndex()) ps[r] = poolOfCls(pick[k], Shape.ONE)
                val res = sn.solve(ps) ?: return
                val wgt = prior * res.likelihood
                if (!(wgt > 0)) return
                total += wgt
                for (r in rooms.indices) res.byPool[ps[r]]?.forEachIndexed { k, share ->
                    if (share > 0) chance.merge(r * 256L + MapStats.NAMES.indexOf(sn.avail[ps[r]][k]), wgt * share, Double::plus)
                }
                return
            }
            for (c in options[j]) {
                pick[j] = c; counts[c]++
                typing(j + 1, prior * openChances[j][c])
                counts[c]--
            }
        }
        typing(0, 1.0)
        if (!(total > 0)) return null
        val out = ArrayList<NamedRoom>()
        for ((key, wgt) in chance) {
            val share = wgt / total
            if (share >= 0.001) out += NamedRoom(rooms[(key / 256).toInt()], MapStats.NAMES[(key % 256).toInt()], share.toFloat())
        }
        return out
    }

    private fun fixedName(k: Kind): String? = when (k) { Kind.ENTRANCE -> "Entrance"; Kind.BLOOD -> "Blood"; Kind.FAIRY -> "Fairy"; else -> null }

    private fun poolOfKnown(i: Int): Int = poolOfKnown(view, i)

    companion object {
        /** Sure, for [certainNames] (allowing for float sums). */
        const val CERTAIN = 0.999f

        /** The kinds a 1x1 dead end can be that place no other room: [certainNames] tries each way they can be typed. */
        private val DEAD_ENDS = intArrayOf(Cls.PUZZLE, Cls.TRAP, Cls.YELLOW, Cls.RARE)

        /** At most this many ways to type the open dead ends are tried (more: the map isn't known well enough yet). */
        private const val MAX_TYPINGS = 4096.0

        /**
         * The unknown rooms [p] is sure of the size and place of, whatever their type or name: each big room put on the
         * same tiles at least [at] sure, and each tile a 1x1 room at least [at] sure, by the chances shown
         * ([Prediction.probs]) or as they came ([Prediction.raw]). Its tiles, each.
         */
        fun certainShapes(p: Prediction, at: Float = CERTAIN): List<IntArray> {
            fun sureBig(t: Int) = maxOf(p.probs.getOrNull(t)?.get(Cls.BIG) ?: 0f, p.raw.getOrNull(t)?.get(Cls.BIG) ?: 0f) >= at
            fun sure1x1(q: FloatArray?) = q != null && q.sum() >= at && q[Cls.BIG] <= 1 - at
            val out = ArrayList<IntArray>()
            val inBig = HashSet<Int>()
            for (b in p.bigRooms) {
                if (b.chance < at || b.tiles.any { it in inBig || !sureBig(it) }) continue
                inBig += b.tiles.toList()
                out += b.tiles
            }
            for (t in p.raw.indices) {
                if (t in inBig) continue
                if (sure1x1(p.probs.getOrNull(t)) || sure1x1(p.raw[t])) out += intArrayOf(t)
            }
            return out
        }

        /** A puzzle room's name (ADM's map) is the tab's puzzle [tab]: the same, but both blaze rooms are "Higher Or Lower". */
        fun isTabPuzzle(room: String?, tab: String): Boolean = room != null && (room == tab || tab == "Higher Or Lower" && room.endsWith(" Blaze"))

        /** A discovered room's name pool from its colour and shape; a brown 1x1 dead end is a rare room (a normal 1x1 never is one). */
        internal fun poolOfKnown(view: MapView, i: Int): Int {
            val shape = view.shapes[i]
            return when (view.kinds[i]) {
                Kind.NORMAL, Kind.RARE -> if (shape != Shape.ONE) shape.ordinal else {
                    val t = view.roomTiles[i][0]
                    if (view.kinds[i] == Kind.RARE || view.neighbours(t).count { Edge.isDoor(view.edge(t, it)) } == 1) MapStats.POOL_RARE else MapStats.POOL_1X1
                }
                Kind.PUZZLE -> MapStats.POOL_PUZZLE
                Kind.TRAP -> MapStats.POOL_TRAP
                Kind.YELLOW -> MapStats.POOL_YELLOW
                Kind.BLOOD -> MapStats.POOL_BLOOD
                Kind.FAIRY -> MapStats.POOL_FAIRY
                Kind.ENTRANCE -> MapStats.POOL_ENTRANCE
            }
        }

        /**
         * Each split's secrets, fitted to the tab's secret total: what the total leaves for the unknown rooms (its middle
         * when the tab allows several, less the named rooms' secrets and the unnamed discovered ones' averages) shared out
         * as the splits' averages ([Split.secrets]) share it. Only a guess at the count, read after the prediction: it
         * never changes the rooms predicted. The averages as they are while the total is unknown, a
         * named room isn't on the list, or the total leaves nothing for them.
         */
        fun splitSecrets(view: MapView, splits: List<Split>): FloatArray {
            val avg = FloatArray(splits.size) { splits[it].secrets }
            val sum = avg.sum()
            if (view.secretTotals.isEmpty() || sum <= 0f) return avg
            val byName = MapStats.NAMES.associateBy { it.name }
            val mean = MapStats.meanSecrets(view.names.filterNotNull().toSet() + view.rules.missingNames)
            var known = 0.0
            for (i in view.kinds.indices) {
                val name = view.names[i]
                known += if (name != null) (byName[name] ?: return avg).secrets.toDouble() else mean[poolOfKnown(view, i)]
            }
            val left = view.secretTotals.average() - known
            if (left <= 0) return avg
            val k = (left / sum).toFloat()
            return FloatArray(avg.size) { avg[it] * k }
        }

        /** A room's name pool from its class and shape. */
        internal fun poolOfCls(c: Int, shape: Shape): Int = when (c) {
            Cls.BIG -> shape.ordinal
            Cls.NORMAL -> MapStats.POOL_1X1
            Cls.PUZZLE -> MapStats.POOL_PUZZLE
            Cls.TRAP -> MapStats.POOL_TRAP
            Cls.YELLOW -> MapStats.POOL_YELLOW
            Cls.BLOOD -> MapStats.POOL_BLOOD
            Cls.FAIRY -> MapStats.POOL_FAIRY
            else -> MapStats.POOL_RARE
        }
    }
}
