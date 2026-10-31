package com.engineerclient.mappred

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
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

    /**
     * What the game-process sampler draws its samples from, and nothing else: the rooms' tiles (and the entrance), the
     * doors and walls. Two views with the same key draw from the same law at the same scale, whatever else differs
     * (a room's kind or name, the secret totals, the tab's puzzles): their samples can be pooled as they are.
     */
    fun structureKey(): String = buildString {
        append(w).append('x').append(h).append(':').append(rules.floor).append(':')
        for (i in kinds.indices.sortedBy { roomTiles[it].min() }) {
            append(if (kinds[i] == Kind.ENTRANCE) 'E' else 'r').append(roomTiles[i].sorted().joinToString(",")).append(';')
        }
        append(':').append(edges.joinToString(""))
    }

    /**
     * This view says everything [older] said: every map this view allows, [older] allowed too, so samples drawn for
     * [older] that fit this view are samples of this view's maps (reweighted, [GameSampler.reuse]). The same dungeon
     * grid and floor, every older room here with the same tiles and kind (and its name, once it had one), every door,
     * wall and same-room edge it showed shown the same, the tab's puzzles it named named the same. More may have
     * been added (rooms, edges, names); a secret total the older view allowed and this one doesn't is no loss of
     * what it said ([GameSampler.reuse] looks at those). A misread corrected, a room that went, a door that changed
     * kind, a different dungeon: not a refinement, and the samples can't be carried over. One change of kind is none to
     * the game process: a 1x1 with one door read as normal one time and rare the other ([sameKind]).
     */
    fun refines(older: MapView): Boolean {
        if (w != older.w || h != older.h || puzzles != older.puzzles || rules.floor != older.rules.floor) return false
        for (i in older.kinds.indices) {
            val tiles = older.roomTiles[i]
            val j = roomAt[tiles[0]]
            if (j < 0 || roomTiles[j].size != tiles.size || !sameKind(j, older.kinds[i])) return false
            for (t in tiles) if (roomAt[t] != j) return false
            val name = older.names[i]
            if (name != null && names[j] != name) return false
        }
        for (k in edges.indices) { val o = older.edges[k]; if (o != Edge.UNKNOWN && edges[k] != o) return false }
        // the tab's puzzle lines: once it listed them, it still does, the names it gave the same
        if (older.puzzleSlots.isNotEmpty()) {
            if (puzzleSlots.size != older.puzzleSlots.size) return false
            for (i in older.puzzleSlots.indices) if (older.puzzleSlots[i] != null && puzzleSlots[i] != older.puzzleSlots[i]) return false
        }
        return true
    }

    /**
     * Room [j] is of the kind [old] said, to the game process: the same kind, or a brown 1x1 with one door (a dead end)
     * that was normal and is rare or the other way round, which [GameSampler] (its specials shown) and [MapPredictor]
     * (the names' pools) both read as a rare room. Odin has a room it has seen only on the map as rare until it has
     * scanned its core, so a room can be read normal, then rare (OdinMapPrediction reads Odin's unscanned rare as normal).
     */
    private fun sameKind(j: Int, old: Kind): Boolean {
        val k = kinds[j]
        if (k == old) return true
        if (k != Kind.NORMAL && k != Kind.RARE || old != Kind.NORMAL && old != Kind.RARE) return false
        val ts = roomTiles[j]
        return ts.size == 1 && neighbours(ts[0]).count { Edge.isDoor(edge(ts[0], it)) } == 1
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
 * The prediction: for each unknown tile the chance of each [Cls] (null for discovered tiles and tiles no
 * room can be on; on a puzzle column tile they add up to the chance there's a room at all), for each door
 * into the unknown what is behind it, and the big rooms seen in at least 5% of samples (likeliest first).
 * [level] 0 keeps every rule; higher levels are looser fallbacks for maps the rules don't fit (a misread
 * map, an unusual dungeon).
 */
class Prediction(val w: Int, val h: Int, val probs: Array<FloatArray?>, val splits: List<Split>, val samples: Int, val level: Int,
                 val bigRooms: List<BigRoom> = emptyList(), val namedRooms: List<NamedRoom> = emptyList(),
                 val doors: List<PredictedDoor> = emptyList(),
                 /**
                  * The chances as the samples give them, before [FloorRules.calibration] or [GameSampler.CALIBRATION]
                  * (what [probs] draws from).
                  */
                 val raw: Array<FloatArray?> = probs)

/**
 * The samples' weights added up, whichever sampler drew them: per tile and class (over [cells]), per door into the
 * unknown ([Split]s), per big room placement (its tiles as a bit mask), per room and name from the secret total, and
 * per door between unknown tiles (lower tile * n + higher: as a door, a wither door, the blood door, a fairy door).
 */
internal class Tally(cells: Int, entries: Int) {
    val counts = Array(cells) { DoubleArray(Cls.COUNT) }
    val splitRooms = DoubleArray(entries)
    val splitTiles = DoubleArray(entries)
    val splitCls = Array(entries) { DoubleArray(Cls.COUNT) }
    val splitSecrets = DoubleArray(entries)
    val bigCounts = HashMap<Long, Double>()
    val nameCounts = HashMap<Long, Double>()
    val doorCounts = HashMap<Int, DoubleArray>()
    var weight = 0.0

    /** Adds [o]'s sums times [k]. */
    fun addScaled(o: Tally, k: Double) {
        for (i in counts.indices) { val a = counts[i]; val b = o.counts[i]; for (c in a.indices) a[c] += k * b[c] }
        for (i in splitRooms.indices) {
            splitRooms[i] += k * o.splitRooms[i]; splitTiles[i] += k * o.splitTiles[i]; splitSecrets[i] += k * o.splitSecrets[i]
            val a = splitCls[i]; val b = o.splitCls[i]; for (c in a.indices) a[c] += k * b[c]
        }
        for ((key, v) in o.bigCounts) bigCounts.merge(key, k * v, Double::plus)
        for ((key, v) in o.nameCounts) nameCounts.merge(key, k * v, Double::plus)
        for ((key, v) in o.doorCounts) { val a = doorCounts.getOrPut(key) { DoubleArray(4) }; for (c in 0 until 4) a[c] += k * v[c] }
        weight += k * o.weight
    }

    /** What the samples agree on, each tile's chances through [calibrate] when there is one; null before any was kept. */
    fun prediction(w: Int, h: Int, cells: IntArray, entries: IntArray, entryDoor: ByteArray, samples: Int, level: Int,
                   calibrate: ((FloatArray) -> FloatArray)?): Prediction? {
        if (samples == 0 || weight <= 0) return null
        val n = w * h
        // over every sample: a puzzle column tile's chances add up to the chance of a room there
        val raw = arrayOfNulls<FloatArray>(n)
        cells.forEachIndexed { i, t ->
            val c = counts[i]
            if (c.sum() > 0) raw[t] = FloatArray(Cls.COUNT) { (c[it] / weight).toFloat() }
        }
        val probs = if (calibrate == null) raw else Array(n) { raw[it]?.let(calibrate) }
        val splits = entries.mapIndexed { i, t ->
            Split(t, entryDoor[t], (splitRooms[i] / weight).toFloat(), (splitTiles[i] / weight).toFloat(), FloatArray(Cls.COUNT) { (splitCls[i][it] / weight).toFloat() },
                (splitSecrets[i] / weight).toFloat())
        }
        val big = bigCounts.entries.filter { it.value / weight >= 0.05 }.sortedByDescending { it.value }
            .map { (m, v) -> BigRoom((0 until n).filter { (m shr it) and 1L == 1L }.toIntArray(), (v / weight).toFloat()) }
        val named = nameCounts.entries.filter { it.value / weight >= 0.001 }.sortedByDescending { it.value }.map { (key, v) ->
            NamedRoom((0 until n).filter { ((key / 256) shr it) and 1L == 1L }.toIntArray(), MapStats.NAMES[(key % 256).toInt()], (v / weight).toFloat())
        }
        val doors = doorCounts.entries.filter { it.value[0] / weight >= 0.05 }.sortedByDescending { it.value[0] }.map { (key, d) ->
            PredictedDoor(key / n, key % n, (d[0] / weight).toFloat(), (d[1] / weight).toFloat(), (d[2] / weight).toFloat(), (d[3] / weight).toFloat())
        }
        return Prediction(w, h, probs, splits, samples, level, big, named, doors, raw)
    }
}

/**
 * Room predictions for a partly discovered map: many complete maps are drawn that fit what the map
 * shows and the rules every recorded map of its floor follows ([FloorRules]), and the prediction is what
 * they agree on. [run] can be called again to add samples (each call refines the same prediction).
 *
 * Two samplers: on every floor the game's own steps ([GameSampler]: the big rooms one random spot each, the door tree
 * grown from the entrance, the puzzle column after it, no fitted tables, on F7 a calibration of the chances; 5-puzzle
 * maps and their rare rooms too), unless [gameProcess] is false; else, and for a map the game's steps can't fit (a
 * misread one) or hardly fit (too few trees come out right in the time it gets: [GameSampler.failed]), the classic
 * one below (big rooms biggest first with held cells, a tree grown from the doors into the unknown, fitted tables
 * and calibration).
 */
class MapPredictor(private val view: MapView, gameProcess: Boolean = true) {
    private val w = view.w
    private val h = view.h
    private val n = w * h
    private val rules = view.rules
    /** Unknown tiles of the main grid. */
    private val unknown: IntArray
    /** Puzzle column tiles behind a door the map shows (a room for sure), and those nobody has seen the west side of (a room or not). */
    private val column: IntArray
    private val open: IntArray
    /** Every tile a room can still be on: [unknown], [column], [open]. */
    private val cells: IntArray
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
    /** The puzzle column can top the dead ends up: the tab list's puzzles, trap and yellow don't go past its target. */
    private val topUp: Boolean
    private val combos: List<IntArray>
    /**
     * The tab's named puzzles found on the map ([FloorRules.puzzleOrder]): each one's place in the tab's order
     * ([orderKey]), sorted, and how many of the puzzles still to come sit before it. Empty when none is pinned.
     */
    private val pinKeys: IntArray
    private val pinBefore: IntArray
    private val frame = IntArray(n) { it }

    /** The classic sampler's samples added up. */
    private val tally: Tally
    /** Names from the secret total: the solver, and the pools of discovered rooms nobody has named. */
    private var secrets: SecretNames? = null
    private var secretsOn = false
    private val unnamedKnown = ArrayList<Int>()
    /** Each name pool's average secrets, but the names the map already has or the floor never has: what a split's rooms hold. */
    private val poolSecrets = MapStats.meanSecrets(view.names.filterNotNull().toSet() + rules.missingNames)
    private var classicAccepted = 0
    private var classicLevel = 0
    private var levelTries = 0L
    /** The game-process sampler while it draws this map's samples; null for the classic one (asked for, or taken over). */
    private var gameSampler: GameSampler? = null

    /** True while the samples come from the game's own steps ([GameSampler]), false from the classic sampler. */
    val usesGameProcess: Boolean get() = gameSampler != null
    /** The game-process sampler while it has the map (tests look inside; null once the classic one has taken over). */
    internal val game: GameSampler? get() = gameSampler
    /** What stopped the game-process sampler, if it threw: it is dropped and the classic sampler takes over (see [run]). */
    internal var gameError: Exception? = null
        private set

    private fun dropGame(e: Exception) { gameError = e; gameSampler = null }

    /** Another game-process sampler for this view, set up as [game] is (null when the game process isn't used for it): the lanes [PredictionSession] runs at once. */
    private var makeGame: (() -> GameSampler)? = null
    internal fun newGameLane(): GameSampler? = makeGame?.invoke()
    /** The secret total's names weigh this view's samples (a total is known and the named rooms are on the list). */
    internal val secretsActive: Boolean get() = secretsOn
    /** The tab's named puzzles this view's samples are pinned by ([pinPuzzles]; none when the tab doesn't fit the map). */
    internal val pinCount: Int get() = pinKeys.size

    /** Samples kept so far (the game-process sampler's once it has enough, 0 while it goes on: [GameSampler.accepted]). */
    val accepted: Int get() = gameSampler?.accepted ?: classicAccepted
    /** 0 keeps every rule; 1-2 the classic sampler's looser fallbacks; 3 nothing fits (or nothing is left to predict). */
    val level: Int get() = if (gameSampler != null) 0 else classicLevel

    init {
        // the floor's tiles: its main grid, and on F4-F6 the puzzle column east of it, whose rooms have one
        // door, to the west; a column tile with a wall there is empty
        val unk = ArrayList<Int>(); val col = ArrayList<Int>(); val opn = ArrayList<Int>(); val all = ArrayList<Int>()
        for (t in 0 until n) {
            val x = t % w; val z = t / w
            if (view.roomAt[t] >= 0 || z >= rules.h) continue
            if (x < rules.w) { unk += t; all += t }
            else if (rules.puzzleColumn && x == rules.w) when {
                view.roomAt[t - 1] < 0 -> { opn += t; all += t }
                Edge.isDoor(view.edge(t - 1, t)) -> { col += t; all += t }
            }
        }
        unknown = unk.toIntArray(); column = col.toIntArray(); open = opn.toIntArray(); cells = all.toIntArray()
        for (t in unknown) isUnk[t] = true
        cells.forEachIndexed { i, t -> tileIdx[t] = i }
        val ents = ArrayList<Int>()
        for (t in unknown + column) for (nb in neighbours(t)) {
            if (view.roomAt[nb] < 0) continue
            val k = view.edge(t, nb)
            if (!Edge.isDoor(k)) continue
            if (entryDoor[t] != 0.toByte()) bad = true // two doors into one unknown tile: no tree does that
            entryDoor[t] = k
            if (t !in ents) ents += t
        }
        entries = ents.toIntArray()
        entries.forEachIndexed { i, t -> entryIndex[t] = i }
        // Unknown tiles but no door known into any of them (a run just started): no tree to grow from, so nothing to
        // predict until the map shows one (tryOne's door tree starts from these and read order[-1] without any).
        if (entries.isEmpty() && unknown.isNotEmpty()) bad = true

        var entId = -1
        for (i in view.kinds.indices) {
            val c = clsOf(view.kinds[i], view.shapes[i])
            found[c]++
            if (view.shapes[i] != Shape.ONE) knownBig[view.shapes[i].ordinal]++
            if (view.kinds[i] == Kind.ENTRANCE) { entId = i; entTile = view.roomTiles[i][0] }
        }
        if (entId >= 0) walkKnownPath(entId)
        remP = maxOf(0, view.puzzles - found[Cls.PUZZLE])
        needDead = remP + (if (!rules.trap || found[Cls.TRAP] > 0) 0 else 1) + (if (found[Cls.YELLOW] > 0) 0 else 1) + (if (found[Cls.BLOOD] > 0) 0 else 1)
        topUp = rules.puzzleColumn && view.puzzles + (if (rules.trap) 1 else 0) + 1 <= rules.deadEndTarget
        combos = rules.combos.filter { c -> (0 until 5).all { c[it] >= knownBig[it] } }
        val pins = pinPuzzles()
        pinKeys = IntArray(pins.size) { pins[it][0] }; pinBefore = IntArray(pins.size) { pins[it][1] }
        setUpSecrets()
        if (rules.bigTile != null && w == 6 && h == 6 && entTile >= 0) {
            val ex = entTile % w; val ez = entTile / w
            val f = SYM.firstOrNull { s -> val (x, z) = s(ex, ez); z == 0 && x <= 2 } ?: SYM[0]
            for (t in 0 until n) { val (x, z) = f(t % w, t / w); frame[t] = z * w + x }
        }
        tally = Tally(cells.size, entries.size)
        // the game's own steps (every floor: held-out lower-floor maps score better by them than by the classic sampler
        // on each floor and at every stage, [GameSampler]); if setting it up throws, the classic sampler has the map from
        // the start (as when it throws later: see [run]). 5-puzzle maps too: their rare rooms (the dead ends past the puzzles) are in the game's steps ([GameSampler]), and run as the
        // worker runs it the game process is ahead of the classic sampler there as on the rest (147 held-out 5-puzzle
        // maps, 735 snapshots: -1.0350 against -1.0539, +0.0189, s.e. 0.0037 by map; +0.0131, s.e. 0.0041, on the 117
        // of them without a rare room). The -0.032 (s.e. 0.013) that once kept them on the classic sampler was 45
        // snapshots of 9 maps without a rare room (rerun with the calibration: -0.012, s.e. 0.014)
        if (gameProcess && !bad && cells.isNotEmpty()) {
            try {
                val sn = secrets; val on = secretsOn; val unnamed = unnamedKnown.toIntArray()
                val make = { GameSampler(view, cells, tileIdx, entries, entryIndex, entryDoor, pinKeys, pinBefore, sn, on, unnamed, poolSecrets) }
                val g = make()
                if (g.possible) { gameSampler = g; makeGame = make }
            } catch (e: Exception) { gameError = e }
        }
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
        secrets = SecretNames(used, targets, rules.missingNames); secretsOn = true
    }

    /**
     * The tab lists the puzzles by their place on the map, column by column (x, then z): a named one's line
     * says how many puzzles come before it. Each named puzzle the map shows, as (its [orderKey], how many
     * puzzles still to come are before it); none when the tab doesn't fit the map (a read mid-update).
     */
    private fun pinPuzzles(): List<IntArray> {
        val slots = view.puzzleSlots
        if (!rules.puzzleOrder || slots.size != view.puzzles) return emptyList()
        val puzzles = view.kinds.indices.filter { view.kinds[it] == Kind.PUZZLE }
        val keys = puzzles.map { orderKey(view.roomTiles[it][0]) }
        val pins = ArrayList<IntArray>()
        slots.forEachIndexed { rank, name ->
            if (name == null) return@forEachIndexed
            val i = puzzles.indexOfFirst { isTabPuzzle(view.names[it], name) }
            if (i < 0) return@forEachIndexed
            pins += intArrayOf(keys[i], rank - keys.count { it < keys[i] })
        }
        pins.sortBy { it[0] }
        // further along the order, never fewer before it, and never more than are still to come
        for (j in pins.indices) if (pins[j][1] !in 0..remP || j > 0 && pins[j][1] < pins[j - 1][1]) return emptyList()
        return pins
    }

    /**
     * Every unnamed room's names from the secret total, however many there are, once the map is known for sure: every
     * tile discovered, or [p] (this view's prediction) sure of every unknown room's size, place and type. Each room
     * (its tiles) with each name that fits and its chance (from 0.1%), as [Prediction.namedRooms] has the last few; null
     * while the map isn't known (or there's no total, a named room isn't on the list, or no names fit it: a misread).
     */
    fun certainNames(p: Prediction?): List<NamedRoom>? {
        val sn = secrets ?: return null
        if (bad || n > 64) return null
        val rooms = ArrayList<IntArray>(); val pools = ArrayList<Int>()
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
                if (c == Cls.BIG || q[c] < CERTAIN) return null
                rooms += intArrayOf(t); pools += poolOfCls(c, Shape.ONE)
            }
        }
        if (rooms.isEmpty()) return null
        val res = sn.solve(pools.toIntArray()) ?: return null
        if (!(res.likelihood > 0)) return null
        val out = ArrayList<NamedRoom>()
        rooms.forEachIndexed { r, tiles ->
            res.byPool[pools[r]]?.forEachIndexed { k, share -> if (share >= 0.001) out += NamedRoom(tiles, sn.avail[pools[r]][k], share.toFloat()) }
        }
        return out
    }

    /** A tile's place in the tab's puzzle order: column by column, west to east, each north to south. */
    private fun orderKey(t: Int): Int = (t % w) * h + t / w

    private fun fixedName(k: Kind): String? = when (k) { Kind.ENTRANCE -> "Entrance"; Kind.BLOOD -> "Blood"; Kind.FAIRY -> "Fairy"; else -> null }

    private fun poolOfKnown(i: Int): Int = poolOfKnown(view, i)

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

    /**
     * Draws samples until [samples] more are kept or [maxTries] have been tried; falls back a level when a level keeps
     * nothing. The game-process sampler works in batches, a few a call ([GameSampler.run]), and returns 0 once it has
     * enough; when it can't fit the map, or ends with too few samples to go on ([GameSampler.failed]), or throws, it is
     * dropped (what it kept with it: 0 is returned) and the classic sampler takes over from the next call. Nothing to
     * predict, or no way to: 0, at [level] 3.
     */
    fun run(samples: Int, maxTries: Int, rnd: Random): Int {
        if (bad || cells.isEmpty()) { classicLevel = 3; return 0 }
        gameSampler?.let { g ->
            val kept = try { g.run(samples, maxTries, rnd) } catch (e: Exception) { dropGame(e); return 0 }
            if (g.failed) { gameSampler = null; return 0 }
            return kept
        }
        var kept = 0; var tries = 0
        while (kept < samples && tries < maxTries && classicLevel <= 2) {
            tries++; levelTries++
            if (tryOne(rnd)) { kept++; classicAccepted++ }
            else if (classicAccepted == 0 && levelTries >= LEVEL_TRIES) { if (secretsOn) secretsOn = false else classicLevel++; levelTries = 0 }
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
            // loose: up to the floor's most big rooms, at most two of a shape
            val pool = ArrayList<Shape>(); for (i in 0 until 5) repeat(maxOf(0, 2 - knownBig[i])) { pool += Shape.entries[i] }
            pool.shuffle(rnd)
            repeat(minOf(pool.size, maxOf(0, rules.maxBig - knownBig.sum()))) { todo += pool[it] }
        }
        todo.shuffle(rnd); todo.sortByDescending { it.size }
        val owner = IntArray(n) { -1 }
        // a few random unknown cells kept free of big rooms while they're placed ([FloorRules.reserve]), as
        // the real maps' layouts look (HELD while placing, 1x1s after)
        if (rules.reserve > 0) {
            val k = minOf(unknown.size, Math.round(rules.reserve * unknown.size / (rules.w * rules.h - 1.0)).toInt())
            val cs = unknown.copyOf()
            for (i in 0 until k) { val j = i + rnd.nextInt(cs.size - i); val tmp = cs[i]; cs[i] = cs[j]; cs[j] = tmp; owner[cs[i]] = HELD }
        }
        val rooms = ArrayList<IntArray>(); val roomShape = ArrayList<Shape>()
        val candTiles = ArrayList<IntArray>(); var cw = DoubleArray(64)
        for (shape in todo) {
            candTiles.clear()
            val anchorW = if (w == 6 && h == 6) rules.anchorWeights?.get(shape.ordinal) else null
            for (oi in PLACEMENTS[shape.ordinal].indices) for (t0 in unknown) {
                val offs = PLACEMENTS[shape.ordinal][oi]
                val x0 = t0 % w; val z0 = t0 / w
                val tiles = IntArray(offs.size / 2); var ok = true; var ents = 0
                for (c in tiles.indices) {
                    val x = x0 + offs[c * 2]; val z = z0 + offs[c * 2 + 1]
                    if (x >= w || z >= h) { ok = false; break }
                    val t = z * w + x
                    if (!isUnk[t] || owner[t] != -1) { ok = false; break }
                    if (entryDoor[t] != 0.toByte()) ents++
                    tiles[c] = t
                }
                if (!ok || ents > 1) continue
                if (candTiles.size == cw.size) cw = cw.copyOf(cw.size * 2)
                var wt = 1.0
                val bigTile = rules.bigTile
                if (bigTile != null && w == 6 && h == 6) for (t in tiles) wt *= bigTile[frame[t]]
                if (anchorW != null) wt *= anchorW[oi][t0]
                cw[candTiles.size] = wt
                candTiles += tiles
            }
            if (candTiles.isEmpty()) return false
            val tiles = candTiles[pick(cw, candTiles.size, rnd)]
            for (t in tiles) owner[t] = rooms.size
            rooms += tiles; roomShape += shape
        }
        for (t in unknown) if (owner[t] < 0) { owner[t] = rooms.size; rooms += intArrayOf(t); roomShape += Shape.ONE }
        // the 1x1 rooms bunch up more than placing the big rooms at random gives ([FloorRules.bunch]): each 2x2
        // block of 1x1s, discovered or not, makes the map likelier
        var layoutW = 1.0
        if (rules.bunch > 0.0) {
            var blocks = 0
            for (z in 0 until rules.h - 1) for (x in 0 until rules.w - 1) {
                val t = z * w + x
                if (isOne(t, owner, roomShape) && isOne(t + 1, owner, roomShape) && isOne(t + w, owner, roomShape) && isOne(t + w + 1, owner, roomShape)) blocks++
            }
            layoutW = exp(rules.bunch * blocks)
        }
        // the puzzle column's rooms behind a door the map shows
        val firstColumn = rooms.size
        for (t in column) { owner[t] = rooms.size; rooms += intArrayOf(t); roomShape += Shape.ONE }
        val r = rooms.size

        // 2. which rooms of the main grid touch, and where a door between them could go (packed as myTile * n + theirTile)
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

        // 3. the door tree, grown from the entry rooms (with room for the puzzle column rooms 5 adds)
        val rm = r + open.size
        val parent = IntArray(rm) { -2 }; val inTile = IntArray(rm); val outTile = IntArray(rm); val root = IntArray(rm) { -1 }
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
        val deg = IntArray(rm)
        for (i in 0 until r) { deg[i]++; if (parent[i] >= 0) deg[parent[i]]++ }
        val dead = (0 until r).filter { roomShape[it] == Shape.ONE && deg[it] == 1 }.toMutableList()
        // dead ends past the specials are rare rooms (5-puzzle maps); the ones short of them, the puzzle
        // column makes up (where it tops up at all; at a looser level wherever the floor has one)
        val canTopUp = topUp || level > 0 && rules.puzzleColumn
        var short = needDead - dead.size
        if (level < 2) {
            if (short < 0) {
                if (!(view.puzzles == 5 && short >= -2 && rnd.nextDouble() < MapStats.RARE_RATE)) return false
                short = 0
            }
            if (!canTopUp && (short > 0 || column.isNotEmpty())) return false
        } else short = if (canTopUp) maxOf(0, short) else 0

        // 4. blood: a 1x1 dead end of the main grid behind the wither door, its walk from the entrance the
        //    floor's 9-10 steps (7-8), with a 1x1 fairy exactly the floor's 4 (3) steps before it
        val type = IntArray(rm) { -1 }
        var wgt = layoutW
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
                if (b >= firstColumn) continue
                if (level == 0 && hb < 0) continue
                if (hb >= 0 && root[b] != hb) continue
                // behind the blood door is blood, at every level
                if (bloodDoor != (b == hr) && (bloodDoor || level == 0)) continue
                val d = dist[b]
                var wb = if (level == 0) rules.walk.getOrElse(d) { 0f }.toDouble() else 1.0
                if (wb <= 0) continue
                var f = -1
                if (found[Cls.FAIRY] > 0) { if (level == 0 && d != fairyDist + rules.fairyBeforeBlood) continue }
                else {
                    var u = parent[b]
                    while (u >= 0) {
                        if (roomShape[u] == Shape.ONE && (if (level == 0) dist[u] == d - rules.fairyBeforeBlood else deg[u] >= 2)) { f = u; break }
                        u = parent[u]
                    }
                    if (f < 0 && level == 0) continue
                }
                wb *= rules.bloodFromEntrance.getOrElse(manhattan(rooms[b][0])) { 0f }
                if (f >= 0) wb *= rules.fairyFromEntrance.getOrElse(manhattan(rooms[f][0])) { 0f }
                if (wb <= 0) continue
                cand[nc] = b; fair[nc] = f; ws[nc] = wb; nc++
            }
            if (nc == 0) {
                if (level < 2) return false
                if (bloodDoor) type[hr] = Cls.BLOOD
            } else {
                var total = 0.0; for (i in 0 until nc) total += ws[i]
                wgt *= if (level == 0) total else 1.0
                val k = pick(ws, nc, rnd)
                type[cand[k]] = Cls.BLOOD
                if (fair[k] >= 0) type[fair[k]] = Cls.FAIRY
            }
        } else if (found[Cls.FAIRY] == 0 && level == 0) return false

        // 5. the puzzle column rooms still short, at random beside normal rooms (big, or a 1x1 with two doors already)
        val columnRooms = dead.filter { it >= firstColumn }.toMutableList()
        if (short > 0) {
            val hosts = open.filter { t -> val o = owner[t - 1]; roomShape[o] != Shape.ONE || deg[o] >= 2 && type[o] != Cls.FAIRY }.toMutableList()
            if (hosts.size < short && level < 2) return false
            hosts.shuffle(rnd)
            for (t in hosts.take(short)) {
                val o = owner[t - 1]; val c = rooms.size
                owner[t] = c; rooms += intArrayOf(t); roomShape += Shape.ONE
                parent[c] = o; outTile[c] = t - 1; inTile[c] = t; root[c] = root[o]; deg[c] = 1; deg[o]++
                columnRooms += c
            }
        }
        val all = rooms.size

        // 6. trap, yellow, puzzles, rares on the other dead ends, at random; the puzzle column's last
        val rest = dead.filter { type[it] < 0 && it < firstColumn }.toMutableList(); rest.shuffle(rnd)
        columnRooms.shuffle(rnd); rest += columnRooms
        // the tab's named puzzles: between two of them go exactly as many puzzles as the tab lists between
        // them, at random within that stretch of the order; the sample weighs the share of puzzle placements
        // that fit (what drawing them at random over every dead end and dropping the rest would keep)
        var placed = false
        if (pinKeys.isNotEmpty() && level < 2 && columnRooms.isEmpty() && rest.size >= remP) {
            val stretch = Array(pinKeys.size + 1) { ArrayList<Int>() }
            for (o in rest) { val k = orderKey(rooms[o][0]); var g = 0; while (g < pinKeys.size && pinKeys[g] < k) g++; stretch[g] += o }
            var before = 0; var ways = 1.0
            for (g in stretch.indices) {
                val c = (if (g < pinKeys.size) pinBefore[g] else remP) - before
                if (c > stretch[g].size) return false
                ways *= choose(stretch[g].size, c)
                for (o in stretch[g].take(c)) { type[o] = Cls.PUZZLE; rest.remove(o) }
                before += c
            }
            wgt *= ways / choose(rest.size + remP, remP)
            placed = true
        }
        var ri = 0
        if (rules.trap && found[Cls.TRAP] == 0 && ri < rest.size) type[rest[ri++]] = Cls.TRAP
        if (found[Cls.YELLOW] == 0 && ri < rest.size) type[rest[ri++]] = Cls.YELLOW
        if (!placed) repeat(remP) { if (ri < rest.size) type[rest[ri++]] = Cls.PUZZLE }
        while (ri < rest.size) type[rest[ri++]] = Cls.RARE

        // 7. the secret total: the unnamed rooms' names have to add up to what the named ones leave
        val cls = IntArray(all) { if (type[it] >= 0) type[it] else if (roomShape[it] == Shape.ONE) Cls.NORMAL else Cls.BIG }
        var names: SecretNames.Result? = null
        val sn = secrets
        if (secretsOn && sn != null && unnamedKnown.size + all <= SecretNames.MAX_ROOMS) {
            val pools = IntArray(unnamedKnown.size + all) { if (it < unnamedKnown.size) unnamedKnown[it] else poolOfCls(cls[it - unnamedKnown.size], roomShape[it - unnamedKnown.size]) }
            names = sn.solve(pools)
            if (names != null) {
                if (names.likelihood <= 0.0) return false
                wgt *= names.likelihood
            }
        }

        tally.weight += wgt
        // the doors between unknown rooms: the wither path runs from the head room down to blood
        val blood = (0 until all).firstOrNull { type[it] == Cls.BLOOD } ?: -1
        val onPath = BooleanArray(all)
        if (blood >= 0) { var u = blood; while (u >= 0) { onPath[u] = true; u = parent[u] } }
        for (v in 0 until all) {
            val u = parent[v]; if (u < 0) continue
            val a = minOf(outTile[v], inTile[v]); val b = maxOf(outTile[v], inTile[v])
            val d = tally.doorCounts.getOrPut(a * n + b) { DoubleArray(4) }
            d[0] += wgt
            if (onPath[u] && onPath[v]) { if (v == blood) d[2] += wgt else d[1] += wgt }
            if (type[u] == Cls.FAIRY || type[v] == Cls.FAIRY) d[3] += wgt
        }
        for (o in 0 until all) {
            val c = cls[o]
            if (names != null && sn != null) {
                var m = 0L; for (t in rooms[o]) m = m or (1L shl t)
                val pool = poolOfCls(c, roomShape[o])
                names.byPool[pool]?.forEachIndexed { k, share -> if (share > 0) tally.nameCounts.merge(m * 256 + MapStats.NAMES.indexOf(sn.avail[pool][k]), wgt * share, Double::plus) }
            }
            val b = root[o]
            if (b >= 0) {
                tally.splitRooms[b] += wgt; tally.splitTiles[b] += wgt * rooms[o].size; tally.splitCls[b][c] += wgt
                tally.splitSecrets[b] += wgt * poolSecrets[poolOfCls(c, roomShape[o])]
            }
            for (t in rooms[o]) tally.counts[tileIdx[t]][c] += wgt
            if (roomShape[o] != Shape.ONE && n <= 64) { var m = 0L; for (t in rooms[o]) m = m or (1L shl t); tally.bigCounts.merge(m, wgt, Double::plus) }
        }
        return true
    }

    /**
     * The tile's chances through [FloorRules.calibration] (tools/mappred calfit.mjs): q ∝ (p + 0.001)^a * e^(b + d *
     * (rooms known - 6) / 3) per class, what no sample had kept at 0. The samples are a little too sure of
     * themselves; this is fitted on training maps and better on held-out ones.
     */
    private fun calibrated(p: FloatArray): FloatArray {
        val cal = rules.calibration ?: return p
        return calibrate(p, cal, view.kinds.size)
    }

    /** Tile [t] is a 1x1 room: discovered, or in this sample. */
    private fun isOne(t: Int, owner: IntArray, roomShape: List<Shape>): Boolean {
        val k = view.roomAt[t]
        return if (k >= 0) view.shapes[k] == Shape.ONE else owner[t] >= 0 && roomShape[owner[t]] == Shape.ONE
    }

    private fun manhattan(t: Int): Int = if (entTile < 0) 0 else abs(t % w - entTile % w) + abs(t / w - entTile / w)

    /**
     * What the samples so far agree on; null before any was kept. The classic sampler's chances go through its
     * calibration ([FloorRules.calibration]), the game-process sampler's through [GameSampler.CALIBRATION].
     */
    fun prediction(): Prediction? {
        gameSampler?.let { g -> try { return g.prediction() } catch (e: Exception) { dropGame(e) } }
        return tally.prediction(w, h, cells, entries, entryDoor, classicAccepted, classicLevel, ::calibrated)
    }

    companion object {
        /** Sure, for [certainNames]: every sample agrees (allowing for float sums). */
        const val CERTAIN = 0.999f

        /**
         * A tile's chances [p] through a calibration [cal] (per [Cls] the sharpness a, offset b and change d per 3 rooms
         * known, as [MapStats.CALIBRATION]) with [roomsKnown] rooms on the map: q ∝ (p + 0.001)^a * e^(b + d * (rooms
         * known - 6) / 3) per class, what no sample had kept at 0, the tile's total kept (a puzzle column tile's chances
         * add up to the chance of a room there).
         */
        internal fun calibrate(p: FloatArray, cal: Array<DoubleArray>, roomsKnown: Int): FloatArray {
            val k = (roomsKnown - 6) / 3.0
            val s = DoubleArray(p.size); var z = 0.0
            for (c in p.indices) if (p[c] > 0f) { s[c] = exp(cal[0][c] * ln(p[c] + 0.001) + cal[1][c] + cal[2][c] * k); z += s[c] }
            if (z <= 0) return p
            val total = p.sum()
            return FloatArray(p.size) { (s[it] / z * total).toFloat() }
        }

        /** A puzzle room's name (Odin's map) is the tab's puzzle [tab]: the same, but both blaze rooms are "Higher Or Lower". */
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
         * as the splits' averages ([Split.secrets]) share it. Only a guess at the count, read after the prediction: the
         * rooms predicted are the samples' whatever this says. The averages as they are while the total is unknown, a
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

        internal fun choose(m: Int, k: Int): Double {
            if (k < 0 || k > m) return 0.0
            var c = 1.0; for (i in 0 until k) c = c * (m - i) / (i + 1)
            return c
        }

        /** A cell kept free of big rooms while they're placed. */
        private const val HELD = -3

        /** Chance the newest room grows on rather than a random door from the tree (the real trees are a bit deeper than Prim's). */
        private const val GROW = 0.3
        /** Tries at one level, with nothing kept, before the next looser one. */
        private const val LEVEL_TRIES = 30_000L

        /** Cells (dx, dz pairs) of each big shape's placements, in [Shape] order; an L never has its north-west corner missing. */
        internal val PLACEMENTS = arrayOf(
            arrayOf(intArrayOf(0, 0, 1, 0), intArrayOf(0, 0, 0, 1)),
            arrayOf(intArrayOf(0, 0, 1, 0, 2, 0), intArrayOf(0, 0, 0, 1, 0, 2)),
            arrayOf(intArrayOf(0, 0, 1, 0, 2, 0, 3, 0), intArrayOf(0, 0, 0, 1, 0, 2, 0, 3)),
            arrayOf(intArrayOf(0, 0, 1, 0, 0, 1, 1, 1)),
            arrayOf(intArrayOf(0, 0, 1, 0, 0, 1), intArrayOf(0, 0, 1, 0, 1, 1), intArrayOf(0, 0, 0, 1, 1, 1)),
        )

        /** The 8 turns and flips of a 6x6 grid. */
        private val SYM: List<(Int, Int) -> Pair<Int, Int>> = listOf(
            { x, z -> x to z }, { x, z -> (5 - z) to x }, { x, z -> (5 - x) to (5 - z) }, { x, z -> z to (5 - x) },
            { x, z -> (5 - x) to z }, { x, z -> z to x }, { x, z -> x to (5 - z) }, { x, z -> (5 - z) to (5 - x) },
        )
    }
}
