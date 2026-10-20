package com.engineerclient.mappred

import kotlin.math.abs
import kotlin.random.Random

/** A room's kind, from its colour on the map. */
enum class Kind { NORMAL, ENTRANCE, PUZZLE, TRAP, YELLOW, BLOOD, FAIRY, RARE }

/** Room shapes; the five big ones in the order of [MapStats.COMBOS]'s counts. */
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
    fun isDoor(k: Byte) = k == DOOR || k == WITHER || k == BLOOD
}

/**
 * The discovered part of a [w] x [h] dungeon map: the rooms the map shows (tile = z * w + x), the
 * edges between tiles, and the tab list's puzzle count. Only what the map shows goes in.
 */
class MapView(val w: Int, val h: Int, val puzzles: Int) {
    val roomAt = IntArray(w * h) { -1 }
    val kinds = ArrayList<Kind>()
    val shapes = ArrayList<Shape>()
    val roomTiles = ArrayList<IntArray>()
    /** Each discovered room's name when someone has been in it (null when nobody has). */
    val names = ArrayList<String?>()
    /** The dungeon's secret totals the tab list allows ([SecretNames.totals]); empty when unknown. */
    var secretTotals = IntArray(0)
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

    /** Changes whenever anything the predictor reads changes. */
    fun key(): String = buildString {
        append(w).append('x').append(h).append(':').append(puzzles).append(':')
        for (i in kinds.indices) append(kinds[i].ordinal).append(roomTiles[i].joinToString(",")).append(names[i] ?: "").append(';')
        append(secretTotals.joinToString(",")).append(':')
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

/** One door from the discovered rooms into the unknown, and what its side of the map (its subtree) holds. */
class Split(val tile: Int, val door: Byte, val rooms: Float, val tiles: Float, val expect: FloatArray)

/** A big room the samples put in the same place, and how often (its exact tiles). */
class BigRoom(val tiles: IntArray, val chance: Float)

/**
 * A door the samples put between unknown tiles [a] and [b], and how often ([chance]); of that, how often it is a
 * wither door (on the way to blood), the blood door, or a door of the fairy room.
 */
class PredictedDoor(val a: Int, val b: Int, val chance: Float, val wither: Float, val blood: Float, val fairy: Float)

/** An unknown room the samples give these tiles and this name (from the dungeon's secret total), and how often (from 0.1%). */
class NamedRoom(val tiles: IntArray, val name: RoomName, val chance: Float)

/**
 * The prediction: for each unknown tile the chance of each [Cls] (null for discovered tiles), for
 * each door into the unknown what is behind it, and the big rooms seen in at least 5% of samples
 * (likeliest first). [level] 0 keeps every rule; higher levels are looser fallbacks for maps the
 * rules don't fit (a misread map, an unusual dungeon).
 */
class Prediction(val w: Int, val h: Int, val probs: Array<FloatArray?>, val splits: List<Split>, val samples: Int, val level: Int,
                 val bigRooms: List<BigRoom> = emptyList(), val namedRooms: List<NamedRoom> = emptyList(),
                 val doors: List<PredictedDoor> = emptyList())

/**
 * Room predictions for a partly discovered map: many complete maps are drawn that fit what the map
 * shows and the rules every recorded F7/M7 map follows ([MapStats]), and the prediction is what they
 * agree on. [run] can be called again to add samples (each call refines the same prediction).
 */
class MapPredictor(private val view: MapView) {
    private val w = view.w
    private val h = view.h
    private val n = w * h
    private val unknown: IntArray
    private val isUnk = BooleanArray(n)
    private val tileIdx = IntArray(n) { -1 }
    private val entryDoor = ByteArray(n)
    private val entries: IntArray
    private val entryIndex = IntArray(n) { -1 }
    private var bad = false

    private val found = IntArray(Cls.COUNT)
    private val knownBig = IntArray(5)
    private var entTile = -1
    private var head = -1
    private var headDist = -1
    private var fairyDist = -1
    private val remP: Int
    private val needDead: Int
    private val combos: List<IntArray>
    private val frame = IntArray(n) { it }

    private val counts: Array<DoubleArray>
    private val splitRooms: DoubleArray
    private val splitTiles: DoubleArray
    private val splitCls: Array<DoubleArray>
    /** Weight of each big room placement (its tiles as a bit mask), over the samples. */
    private val bigCounts = HashMap<Long, Double>()
    /** Names from the secret total: the solver, the pools of discovered rooms nobody has named, and each (tiles, name)'s weight. */
    private var secrets: SecretNames? = null
    private var secretsOn = false
    private val unnamedKnown = ArrayList<Int>()
    private val nameCounts = HashMap<Long, Double>()
    /** Each edge between unknown tiles (lower tile * n + higher): weight as a door, as a wither door, the blood door, a fairy door. */
    private val doorCounts = HashMap<Int, DoubleArray>()
    private var weight = 0.0
    var accepted = 0; private set
    var level = 0; private set
    private var levelTries = 0L

    init {
        unknown = (0 until n).filter { view.roomAt[it] < 0 }.toIntArray()
        unknown.forEachIndexed { i, t -> isUnk[t] = true; tileIdx[t] = i }
        val ents = ArrayList<Int>()
        for (t in unknown) for (nb in neighbours(t)) {
            if (isUnk[nb]) continue
            val k = view.edge(t, nb)
            if (!Edge.isDoor(k)) continue
            if (entryDoor[t] != 0.toByte()) bad = true // two doors into one unknown tile: no tree does that
            entryDoor[t] = k
            if (t !in ents) ents += t
        }
        entries = ents.toIntArray()
        entries.forEachIndexed { i, t -> entryIndex[t] = i }

        var entId = -1
        for (i in view.kinds.indices) {
            val c = clsOf(view.kinds[i], view.shapes[i])
            found[c]++
            if (view.shapes[i] != Shape.ONE) knownBig[view.shapes[i].ordinal]++
            if (view.kinds[i] == Kind.ENTRANCE) { entId = i; entTile = view.roomTiles[i][0] }
        }
        if (entId >= 0) walkKnownPath(entId)
        remP = maxOf(0, view.puzzles - found[Cls.PUZZLE])
        needDead = remP + (if (found[Cls.TRAP] > 0) 0 else 1) + (if (found[Cls.YELLOW] > 0) 0 else 1) + (if (found[Cls.BLOOD] > 0) 0 else 1)
        combos = MapStats.COMBOS.filter { c -> (0 until 5).all { c[it] >= knownBig[it] } }
        setUpSecrets()
        if (w == 6 && h == 6 && entTile >= 0) {
            val ex = entTile % w; val ez = entTile / w
            val f = SYM.firstOrNull { s -> val (x, z) = s(ex, ez); z == 0 && x <= 2 } ?: SYM[0]
            for (t in 0 until n) { val (x, z) = f(t % w, t / w); frame[t] = z * w + x }
        }
        counts = Array(unknown.size) { DoubleArray(Cls.COUNT) }
        splitRooms = DoubleArray(entries.size); splitTiles = DoubleArray(entries.size)
        splitCls = Array(entries.size) { DoubleArray(Cls.COUNT) }
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
            val name = view.names[i] ?: fixedName(view.kinds[i])
            if (name != null) {
                val rn = byName[name] ?: return
                if (!used.add(name)) return
                known += rn.secrets
            } else unnamedKnown += poolOfKnown(i)
        }
        val targets = view.secretTotals.map { it - known }.filter { it >= 0 }.toIntArray()
        if (targets.isEmpty()) return
        secrets = SecretNames(used, targets); secretsOn = true
    }

    private fun fixedName(k: Kind): String? = when (k) { Kind.ENTRANCE -> "Entrance"; Kind.BLOOD -> "Blood"; Kind.FAIRY -> "Fairy"; else -> null }

    /** A discovered room's name pool from its colour and shape; a brown 1x1 dead end is a rare room (a normal 1x1 never is one). */
    private fun poolOfKnown(i: Int): Int {
        val shape = view.shapes[i]
        return when (view.kinds[i]) {
            Kind.NORMAL, Kind.RARE -> if (shape != Shape.ONE) shape.ordinal else {
                val t = view.roomTiles[i][0]
                if (view.kinds[i] == Kind.RARE || neighbours(t).count { Edge.isDoor(view.edge(t, it)) } == 1) MapStats.POOL_RARE else MapStats.POOL_1X1
            }
            Kind.PUZZLE -> MapStats.POOL_PUZZLE
            Kind.TRAP -> MapStats.POOL_TRAP
            Kind.YELLOW -> MapStats.POOL_YELLOW
            Kind.BLOOD -> MapStats.POOL_BLOOD
            Kind.FAIRY -> MapStats.POOL_FAIRY
            Kind.ENTRANCE -> MapStats.POOL_ENTRANCE
        }
    }

    private fun poolOfCls(c: Int, shape: Shape): Int = when (c) {
        Cls.BIG -> shape.ordinal
        Cls.NORMAL -> MapStats.POOL_1X1
        Cls.PUZZLE -> MapStats.POOL_PUZZLE
        Cls.TRAP -> MapStats.POOL_TRAP
        Cls.YELLOW -> MapStats.POOL_YELLOW
        Cls.BLOOD -> MapStats.POOL_BLOOD
        Cls.FAIRY -> MapStats.POOL_FAIRY
        else -> MapStats.POOL_RARE
    }

    private fun neighbours(t: Int): IntArray {
        val x = t % w; val z = t / w
        val out = IntArray(4); var k = 0
        if (x + 1 < w) out[k++] = t + 1
        if (x > 0) out[k++] = t - 1
        if (z + 1 < h) out[k++] = t + w
        if (z > 0) out[k++] = t - w
        return out.copyOf(k)
    }

    /** Fewest steps from tile [s] to tile [t] inside a room made of [tiles]. */
    private fun inRoom(tiles: IntArray, s: Int, t: Int): Int {
        if (s == t) return 0
        val dist = IntArray(tiles.size) { -1 }
        val si = tiles.indexOf(s); if (si < 0) return 99
        dist[si] = 0
        val q = IntArray(tiles.size); var qh = 0; var qt = 0; q[qt++] = si
        while (qh < qt) {
            val u = q[qh++]
            for (j in tiles.indices) if (dist[j] < 0 && isAdjacent(tiles[u], tiles[j])) {
                dist[j] = dist[u] + 1
                if (tiles[j] == t) return dist[j]
                q[qt++] = j
            }
        }
        return 99
    }

    private fun isAdjacent(a: Int, b: Int): Boolean {
        val d = abs(a - b)
        return (d == 1 && a / w == b / w) || d == w
    }

    /** The known part of the wither path as a tile walk from the entrance: where it goes on, and the fairy's step if it's on it. */
    private fun walkKnownPath(entId: Int) {
        var cur = entId; var at = entTile; var dist = 0
        val onPath = hashSetOf(entId)
        for (step in 0 until 40) {
            if (view.kinds[cur] == Kind.FAIRY) fairyDist = dist
            var next: IntArray? = null // to room, my tile, their tile
            loop@ for (q in view.roomTiles[cur]) for (nb in neighbours(q)) {
                val o = view.roomAt[nb]
                if (o == cur) continue
                val k = view.edge(q, nb)
                if (!Edge.isDoor(k)) continue
                if (step > 0 && k != Edge.WITHER && k != Edge.BLOOD) continue
                if (o >= 0 && o in onPath) continue
                next = intArrayOf(o, q, nb); break@loop
            }
            val d = next ?: break
            val nd = dist + inRoom(view.roomTiles[cur], at, d[1]) + 1
            if (d[0] < 0) { head = d[2]; headDist = nd; break }
            onPath += d[0]; cur = d[0]; at = d[2]; dist = nd
        }
    }

    /** Draws samples until [samples] more are kept or [maxTries] have been tried; falls back a level when a level keeps nothing. */
    fun run(samples: Int, maxTries: Int, rnd: Random): Int {
        if (bad || unknown.isEmpty()) return 0
        var kept = 0; var tries = 0
        while (kept < samples && tries < maxTries && level <= 2) {
            tries++; levelTries++
            if (tryOne(rnd)) { kept++; accepted++ }
            else if (accepted == 0 && levelTries >= LEVEL_TRIES) { if (secretsOn) secretsOn = false else level++; levelTries = 0 }
        }
        return kept
    }

    private fun clsOf(k: Kind, s: Shape): Int = when (k) {
        Kind.NORMAL, Kind.ENTRANCE -> if (s == Shape.ONE) Cls.NORMAL else Cls.BIG
        Kind.PUZZLE -> Cls.PUZZLE
        Kind.TRAP -> Cls.TRAP
        Kind.YELLOW -> Cls.YELLOW
        Kind.BLOOD -> Cls.BLOOD
        Kind.FAIRY -> Cls.FAIRY
        Kind.RARE -> Cls.RARE
    }

    private fun pick(weights: DoubleArray, count: Int, rnd: Random): Int {
        var total = 0.0; for (i in 0 until count) total += weights[i]
        var s = rnd.nextDouble() * total
        for (i in 0 until count) { if (s < weights[i]) return i; s -= weights[i] }
        return count - 1
    }

    private fun tryOne(rnd: Random): Boolean {
        // 1. the big rooms still to come, placed biggest first; the rest are 1x1s
        val todo = ArrayList<Shape>()
        if (level < 2 && combos.isNotEmpty()) {
            val cw = DoubleArray(combos.size) { combos[it][5].toDouble() }
            val c = combos[pick(cw, combos.size, rnd)]
            for (i in 0 until 5) repeat(c[i] - knownBig[i]) { todo += Shape.entries[i] }
        } else {
            // loose: up to 7 big rooms, at most two of a shape
            val pool = ArrayList<Shape>(); for (i in 0 until 5) repeat(maxOf(0, 2 - knownBig[i])) { pool += Shape.entries[i] }
            pool.shuffle(rnd)
            repeat(minOf(pool.size, maxOf(0, 7 - knownBig.sum()))) { todo += pool[it] }
        }
        todo.shuffle(rnd); todo.sortByDescending { it.size }
        val owner = IntArray(n) { -1 }
        val rooms = ArrayList<IntArray>(); val roomShape = ArrayList<Shape>()
        val candTiles = ArrayList<IntArray>(); var cw = DoubleArray(64)
        for (shape in todo) {
            candTiles.clear()
            for (cells in PLACEMENTS[shape.ordinal]) for (t0 in unknown) {
                val x0 = t0 % w; val z0 = t0 / w
                val tiles = IntArray(cells.size / 2); var ok = true; var ents = 0
                for (c in tiles.indices) {
                    val x = x0 + cells[c * 2]; val z = z0 + cells[c * 2 + 1]
                    if (x >= w || z >= h) { ok = false; break }
                    val t = z * w + x
                    if (!isUnk[t] || owner[t] >= 0) { ok = false; break }
                    if (entryDoor[t] != 0.toByte()) ents++
                    tiles[c] = t
                }
                if (!ok || ents > 1) continue
                if (candTiles.size == cw.size) cw = cw.copyOf(cw.size * 2)
                var wt = 1.0
                if (w == 6 && h == 6) for (t in tiles) wt *= MapStats.BIG_TILE[frame[t]]
                cw[candTiles.size] = wt
                candTiles += tiles
            }
            if (candTiles.isEmpty()) return false
            val tiles = candTiles[pick(cw, candTiles.size, rnd)]
            for (t in tiles) owner[t] = rooms.size
            rooms += tiles; roomShape += shape
        }
        for (t in unknown) if (owner[t] < 0) { owner[t] = rooms.size; rooms += intArrayOf(t); roomShape += Shape.ONE }
        val r = rooms.size

        // 2. which rooms touch, and where a door between them could go (packed as myTile * n + theirTile)
        val nbs = Array(r) { ArrayList<Int>() }
        val slots = Array(r) { ArrayList<ArrayList<Int>>() }
        for (t in unknown) for (nt in intArrayOf(if (t % w + 1 < w) t + 1 else -1, if (t / w + 1 < h) t + w else -1)) {
            if (nt < 0 || !isUnk[nt] || owner[nt] == owner[t]) continue
            val a = owner[t]; val b = owner[nt]
            var ia = nbs[a].indexOf(b)
            if (ia < 0) { nbs[a] += b; slots[a].add(ArrayList()); nbs[b] += a; slots[b].add(ArrayList()); ia = nbs[a].size - 1 }
            slots[a][ia] += t * n + nt
            slots[b][nbs[b].indexOf(a)] += nt * n + t
        }

        // 3. the door tree, grown from the entry rooms
        val parent = IntArray(r) { -2 }; val inTile = IntArray(r); val outTile = IntArray(r); val root = IntArray(r) { -1 }
        val order = ArrayList<Int>(r)
        for (t in entries) { val o = owner[t]; parent[o] = -1; inTile[o] = t; root[o] = entryIndex[t]; order += o }
        while (order.size < r) {
            var u = -1; var v = -1
            if (rnd.nextDouble() < GROW) {
                val last = order[order.size - 1]
                val fr = nbs[last].filter { parent[it] == -2 }
                if (fr.isNotEmpty()) { u = last; v = fr[rnd.nextInt(fr.size)] }
            }
            if (u < 0) {
                var cnt = 0
                for (a in order) for (b in nbs[a]) if (parent[b] == -2) { cnt++; if (rnd.nextDouble() * cnt < 1) { u = a; v = b } }
            }
            if (u < 0) return false // a pocket no door reaches
            val sl = slots[u][nbs[u].indexOf(v)]
            val p = sl[rnd.nextInt(sl.size)]
            parent[v] = u; outTile[v] = p / n; inTile[v] = p % n; root[v] = root[u]; order += v
        }
        val deg = IntArray(r)
        for (i in 0 until r) { deg[i]++; if (parent[i] >= 0) deg[parent[i]]++ }
        val dead = (0 until r).filter { roomShape[it] == Shape.ONE && deg[it] == 1 }.toMutableList()
        if (level < 2 && dead.size != needDead) {
            val extra = dead.size - needDead
            if (!(view.puzzles == 5 && extra in 1..2 && rnd.nextDouble() < MapStats.RARE_RATE)) return false
        }

        // 4. blood: a 1x1 dead end behind the wither door, its walk from the entrance 9-10 steps, with a 1x1
        //    fairy exactly 4 steps before it
        val type = IntArray(r) { -1 }
        var wgt = 1.0
        if (found[Cls.BLOOD] == 0) {
            val hb = if (head >= 0) entryIndex[head] else -1
            val hr = if (head >= 0) owner[head] else -1
            val dist = IntArray(r) { -1 }
            if (hb >= 0) for (o in order) {
                if (root[o] != hb) continue
                dist[o] = if (o == hr) headDist else dist[parent[o]] + inRoom(rooms[parent[o]], inTile[parent[o]], outTile[o]) + 1
            }
            val cand = IntArray(dead.size); val fair = IntArray(dead.size); val ws = DoubleArray(dead.size); var nc = 0
            val bloodDoor = head >= 0 && entryDoor[head] == Edge.BLOOD
            for (b in dead) {
                if (level == 0 && hb < 0) continue
                if (hb >= 0 && root[b] != hb) continue
                // behind the blood door is blood, at every level
                if (bloodDoor != (b == hr) && (bloodDoor || level == 0)) continue
                val d = dist[b]
                var wb = if (level == 0) MapStats.WALK.getOrElse(d) { 0f }.toDouble() else 1.0
                if (wb <= 0) continue
                var f = -1
                if (found[Cls.FAIRY] > 0) { if (level == 0 && d != fairyDist + MapStats.FAIRY_BEFORE_BLOOD) continue }
                else {
                    var u = parent[b]
                    while (u >= 0) {
                        if (roomShape[u] == Shape.ONE && (if (level == 0) dist[u] == d - MapStats.FAIRY_BEFORE_BLOOD else deg[u] >= 2)) { f = u; break }
                        u = parent[u]
                    }
                    if (f < 0 && level == 0) continue
                }
                wb *= MapStats.BLOOD_FROM_ENTRANCE.getOrElse(manhattan(rooms[b][0])) { 0f }
                if (f >= 0) wb *= MapStats.FAIRY_FROM_ENTRANCE.getOrElse(manhattan(rooms[f][0])) { 0f }
                if (wb <= 0) continue
                cand[nc] = b; fair[nc] = f; ws[nc] = wb; nc++
            }
            if (nc == 0) {
                if (level < 2) return false
                if (bloodDoor) type[hr] = Cls.BLOOD
            } else {
                var total = 0.0; for (i in 0 until nc) total += ws[i]
                wgt = if (level == 0) total else 1.0
                val k = pick(ws, nc, rnd)
                type[cand[k]] = Cls.BLOOD
                if (fair[k] >= 0) type[fair[k]] = Cls.FAIRY
            }
        } else if (found[Cls.FAIRY] == 0 && level == 0) return false

        // 5. trap, yellow, puzzles, rares on the other dead ends, at random
        val rest = dead.filter { type[it] < 0 }.toMutableList(); rest.shuffle(rnd)
        var ri = 0
        if (found[Cls.TRAP] == 0 && ri < rest.size) type[rest[ri++]] = Cls.TRAP
        if (found[Cls.YELLOW] == 0 && ri < rest.size) type[rest[ri++]] = Cls.YELLOW
        repeat(remP) { if (ri < rest.size) type[rest[ri++]] = Cls.PUZZLE }
        while (ri < rest.size) type[rest[ri++]] = Cls.RARE

        // 6. the secret total: the unnamed rooms' names have to add up to what the named ones leave
        val cls = IntArray(r) { if (type[it] >= 0) type[it] else if (roomShape[it] == Shape.ONE) Cls.NORMAL else Cls.BIG }
        var names: SecretNames.Result? = null
        val sn = secrets
        if (secretsOn && sn != null && unnamedKnown.size + r <= SecretNames.MAX_ROOMS) {
            val pools = IntArray(unnamedKnown.size + r) { if (it < unnamedKnown.size) unnamedKnown[it] else poolOfCls(cls[it - unnamedKnown.size], roomShape[it - unnamedKnown.size]) }
            names = sn.solve(pools)
            if (names != null) {
                if (names.likelihood <= 0.0) return false
                wgt *= names.likelihood
            }
        }

        weight += wgt
        // the doors between unknown rooms: the wither path runs from the head room down to blood
        val blood = (0 until r).firstOrNull { type[it] == Cls.BLOOD } ?: -1
        val onPath = BooleanArray(r)
        if (blood >= 0) { var u = blood; while (u >= 0) { onPath[u] = true; u = parent[u] } }
        for (v in 0 until r) {
            val u = parent[v]; if (u < 0) continue
            val a = minOf(outTile[v], inTile[v]); val b = maxOf(outTile[v], inTile[v])
            val d = doorCounts.getOrPut(a * n + b) { DoubleArray(4) }
            d[0] += wgt
            if (onPath[u] && onPath[v]) { if (v == blood) d[2] += wgt else d[1] += wgt }
            if (type[u] == Cls.FAIRY || type[v] == Cls.FAIRY) d[3] += wgt
        }
        for (o in 0 until r) {
            val c = cls[o]
            if (names != null && sn != null) {
                var m = 0L; for (t in rooms[o]) m = m or (1L shl t)
                val pool = poolOfCls(c, roomShape[o])
                names.byPool[pool]?.forEachIndexed { k, share -> if (share > 0) nameCounts.merge(m * 256 + MapStats.NAMES.indexOf(sn.avail[pool][k]), wgt * share, Double::plus) }
            }
            val b = root[o]
            if (b >= 0) { splitRooms[b] += wgt; splitTiles[b] += wgt * rooms[o].size; splitCls[b][c] += wgt }
            for (t in rooms[o]) counts[tileIdx[t]][c] += wgt
            if (roomShape[o] != Shape.ONE && n <= 64) { var m = 0L; for (t in rooms[o]) m = m or (1L shl t); bigCounts.merge(m, wgt, Double::plus) }
        }
        return true
    }

    private fun manhattan(t: Int): Int = if (entTile < 0) 0 else abs(t % w - entTile % w) + abs(t / w - entTile / w)

    /** What the samples so far agree on; null before any was kept. */
    fun prediction(): Prediction? {
        if (accepted == 0 || weight <= 0) return null
        val probs = arrayOfNulls<FloatArray>(n)
        unknown.forEachIndexed { i, t ->
            val c = counts[i]; val s = c.sum()
            if (s > 0) probs[t] = FloatArray(Cls.COUNT) { (c[it] / s).toFloat() }
        }
        val splits = entries.mapIndexed { i, t ->
            Split(t, entryDoor[t], (splitRooms[i] / weight).toFloat(), (splitTiles[i] / weight).toFloat(), FloatArray(Cls.COUNT) { (splitCls[i][it] / weight).toFloat() })
        }
        val big = bigCounts.entries.filter { it.value / weight >= 0.05 }.sortedByDescending { it.value }
            .map { (m, v) -> BigRoom((0 until n).filter { (m shr it) and 1L == 1L }.toIntArray(), (v / weight).toFloat()) }
        val named = nameCounts.entries.filter { it.value / weight >= 0.001 }.sortedByDescending { it.value }.map { (key, v) ->
            NamedRoom((0 until n).filter { ((key / 256) shr it) and 1L == 1L }.toIntArray(), MapStats.NAMES[(key % 256).toInt()], (v / weight).toFloat())
        }
        val doors = doorCounts.entries.filter { it.value[0] / weight >= 0.05 }.sortedByDescending { it.value[0] }.map { (key, d) ->
            PredictedDoor(key / n, key % n, (d[0] / weight).toFloat(), (d[1] / weight).toFloat(), (d[2] / weight).toFloat(), (d[3] / weight).toFloat())
        }
        return Prediction(w, h, probs, splits, accepted, level, big, named, doors)
    }

    companion object {
        /** Chance the newest room grows on rather than a random door from the tree (the real trees are a bit deeper than Prim's). */
        private const val GROW = 0.3
        /** Tries at one level, with nothing kept, before the next looser one. */
        private const val LEVEL_TRIES = 30_000L

        /** Cells (dx, dz pairs) of each big shape's placements, in [Shape] order. */
        private val PLACEMENTS = arrayOf(
            arrayOf(intArrayOf(0, 0, 1, 0), intArrayOf(0, 0, 0, 1)),
            arrayOf(intArrayOf(0, 0, 1, 0, 2, 0), intArrayOf(0, 0, 0, 1, 0, 2)),
            arrayOf(intArrayOf(0, 0, 1, 0, 2, 0, 3, 0), intArrayOf(0, 0, 0, 1, 0, 2, 0, 3)),
            arrayOf(intArrayOf(0, 0, 1, 0, 0, 1, 1, 1)),
            arrayOf(intArrayOf(0, 0, 1, 0, 0, 1), intArrayOf(0, 0, 1, 0, 1, 1), intArrayOf(0, 0, 0, 1, 1, 1), intArrayOf(1, 0, 0, 1, 1, 1)),
        )

        /** The 8 turns and flips of a 6x6 grid. */
        private val SYM: List<(Int, Int) -> Pair<Int, Int>> = listOf(
            { x, z -> x to z }, { x, z -> (5 - z) to x }, { x, z -> (5 - x) to (5 - z) }, { x, z -> z to (5 - x) },
            { x, z -> (5 - x) to z }, { x, z -> z to x }, { x, z -> x to (5 - z) }, { x, z -> (5 - z) to (5 - x) },
        )
    }
}
