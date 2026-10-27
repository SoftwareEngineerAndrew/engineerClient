package com.engineerclient.mappred

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The door tree's rule for [GameSampler] (tools/mappred tree3.mjs at level room, pick node): each step a room of the
 * tree is picked by (its free sides' weight)^[alpha], then one of its free sides by its weight, exp(-[beta] x the
 * steps from where the room was entered to the side's cell, over the room's own cells) x exp(-[mu] x the new cell's
 * grid steps from the entrance). The default is treelik.mjs's best on the real maps (room/node/0/0/alpha=0.5/beta=0.5/mu=0.2).
 */
class TreeRule(val alpha: Double = 0.5, val beta: Double = 0.5, val mu: Double = 0.2) {
    companion object {
        val DEFAULT = TreeRule()
    }
}

/**
 * A layout the samples grown from one ancestor share: each tile's room (the sampler's own ids: the discovered rooms
 * first, then the big rooms drawn, then 1x1s) and each room's [Shape] ordinal.
 */
internal class Whole(val owner: ByteArray, val shape: ByteArray)

/**
 * One kept sample, compact: a whole map to reuse when the map shows more ([GameSampler.reuse]). Its door tree is the
 * parent of each tile (-1 for the entrance's); [w] is the particle's weight before [GameSampler.finish], on the scale of
 * the view it was drawn for; [src] and [batch] say which batch of which sampler it came from: samples with the same
 * pair grew from the same ancestors and are no more than one batch's worth together.
 */
internal class Stored(val whole: Whole, val parent: ByteArray, val w: Double, val src: Int, val batch: Int) {
    /** Its last finish weighed the secret total's names, with these totals ([GameSampler.reuse]: a total it didn't allow may have ruled out samples never kept). */
    var names = false
    var totals: IntArray = NO_TOTALS

    companion object { val NO_TOTALS = IntArray(0) }
}

/**
 * What a set of samples of one law adds up to: their tally, how many, the sum and sum of squares of their batches'
 * weights ([w1], [w2]) and the sum of squares of their own ([s2]; the sum is the tally's weight).
 */
internal class Part(val tally: Tally, val kept: Int, val w1: Double, val w2: Double, val s2: Double = 0.0) {
    /** Batches' worth: (sum of batch weights)^2 / sum of their squares. */
    val batches: Double get() = if (w2 > 0) w1 * w1 / w2 else 0.0
    /** Samples' worth, counting each as if it stood alone: (sum of weights)^2 / sum of their squares. */
    val samples: Double get() = if (s2 > 0) tally.weight * tally.weight / s2 else 0.0
}

/**
 * F7 completion by the game's own steps (tools/mappred sampler3.mjs with tree3.mjs; its README's "How the maps are
 * made" and "What the game's code could be doing"), each sample a whole map that fits what the map shows:
 *
 *  1. the big rooms not on the map yet: one random spot inside the map each, all again if any clashes, so every
 *     complete layout of the mix alike (drawn here a room at a time, each weighted by the share of spots it fits);
 *     the mix by how often the maps have it over how often it gets through ([GameStats]), times c!/(c-k)! for c
 *     rooms of a shape with k of them on the map (two 1x3s are twice as likely as one to put one where a 1x3 shows)
 *  2. the door tree grown room by room from the entrance (its one door, inward) by the [TreeRule], taking only the
 *     steps the map allows (doors where it shows doors, none through its walls), each sample weighted by the chance
 *     the game's step was one of them: sequential Monte Carlo, batches of [PARTICLES] resampled as their weights
 *     spread out
 *  3. kept when every door the map shows is in the tree and the 1x1 dead ends besides blood are the puzzles, trap and
 *     yellow (7 to 10 on 5-puzzle maps, the ones past the puzzles rares); blood any 1x1 dead end 9 or 10 steps out with a 1x1 four
 *     steps back (the fairy), all alike, with the wither and blood doors the map shows on its path; trap, yellow,
 *     puzzles and rares on the other dead ends at random, the puzzles in the tab list's order ([FloorRules.puzzleOrder]).
 *     Those draws (a 2x2's inside, blood among its spots, the specials' shuffle) are summed over rather than drawn,
 *     and the secret total's names ([SecretNames]) weigh the last few rooms.
 *
 * No fitted tables: what the samples agree on is the prediction, each tile's chances through [CALIBRATION] (the one fitted
 * thing: the samples are a little too sure of themselves). A batch's samples come from few ancestors, so it goes
 * on until [MIN_BATCHES] batches' worth of weight as well as [MIN_SAMPLES] samples, and shows nothing before
 * [MIN_SHOW] batches' worth. A hard map (few of the trees grown come out right) can use up [MAX_TRIES] particles
 * without getting that far: then it is [failed] and the classic sampler is used instead.
 *
 * With [store] set, every kept sample is also kept whole and compact ([Stored]), and [reuse] carries such samples to a
 * sampler of a later map (one that shows the same and more): the ones that agree with it are its samples too, finished
 * again. [PredictionSession] runs several of these side by side and keeps the pool from map to map.
 */
internal class GameSampler(
    private val view: MapView,
    /** The tiles rooms can still be on (the grid's unknown tiles), and each one's index in them. */
    private val cells: IntArray,
    private val tileIdx: IntArray,
    /** The unknown tiles behind a door the map shows, each one's index in them, and its door. */
    private val entries: IntArray,
    private val entryIndex: IntArray,
    private val entryDoor: ByteArray,
    /** The tab's named puzzles ([MapPredictor]'s pins): each one's place in the tab's order, and how many puzzles still to come are before it. */
    private val pinKeys: IntArray,
    private val pinBefore: IntArray,
    private val secrets: SecretNames?,
    secretsOn: Boolean,
    /** The name pools of the discovered rooms nobody has named. */
    private val unnamedKnown: IntArray,
    private val rule: TreeRule = TreeRule.DEFAULT,
) {
    private val w = view.w
    private val h = view.h
    private val n = w * h
    /** Each tile's neighbours, in tree3.mjs's order (east, west, south, north). */
    private val nb = Array(n) { t ->
        val x = t % w; val z = t / w; val o = ArrayList<Int>(4)
        if (x + 1 < w) o += t + 1; if (x > 0) o += t - 1; if (z + 1 < h) o += t + w; if (z > 0) o += t - w
        o.toIntArray()
    }
    private val nKnown = view.kinds.size
    private val entCell: Int
    /** The tile the entrance's one door leads to: inward, off the edge it is on. */
    private val inward: Int
    /** Unknown tiles behind a door the map shows. */
    private val doorIn = BooleanArray(n)
    /** Tiles with a door the map shows on any side. */
    private val touchesDoor = BooleanArray(n)
    /** Every door the map shows (tiles a < b) and its kind. */
    private val shownA: IntArray
    private val shownB: IntArray
    private val shownKind: ByteArray
    /** Every wall the map shows (tiles a < b): no door tree crosses one. */
    private val wallA: IntArray
    private val wallB: IntArray
    private val knownBig = IntArray(5)
    /** The specials the map shows: trap, yellow, puzzles, rares (a brown 1x1 dead end is a rare room, a normal 1x1 never is one). */
    private val knownSpecial = IntArray(4)
    private var knownBlood = -1
    private var knownFairy = -1
    private val puzzles = view.puzzles
    /** The 1x1 dead ends a map has besides the entrance, blood among them: puzzles + trap + yellow + blood, with 5 puzzles 8 to 11 (rares on the rest). */
    private val deadMin = if (puzzles < 5) puzzles + 3 else 8
    private val deadMax = if (puzzles < 5) puzzles + 3 else MAX_DEAD_5 + 1
    /** The mixes the map's big rooms allow, and the weight to draw each by. */
    private val mixes = ArrayList<IntArray>()
    private val mixW: DoubleArray
    private val mixTotal: Double
    /** Every spot of each big shape inside the map (its tiles). */
    private val spots: Array<Array<IntArray>>
    private val sideBeta = DoubleArray(5) { exp(-rule.beta * it) }
    private val sideMu: DoubleArray

    /** False when no game map can be grown here at all (no entrance, no mix fits the big rooms shown). */
    val possible: Boolean
    /** The secret total's names weigh the samples (off once nothing fits with them: a misread total). */
    var secretsOn = secretsOn; private set

    // what the samples add up to, and how far it has got
    val tally = Tally(cells.size, entries.size)
    var kept = 0; private set
    var tries = 0L; private set
    private var batchW1 = 0.0
    private var batchW2 = 0.0
    private var sampleW2 = 0.0
    private var emptyFrom = 0L
    /** Some particle got as far as the secret total's names ([finishNamed]), so turning them off could change what fits. */
    private var namesTried = false
    /** No more batches: the samples are enough, the budget is spent, or nothing fits (then [kept] is 0). */
    var done = false; private set
    /** The most particles started in all, before it is [done] with what it has ([MAX_TRIES]; a test lowers it). */
    internal var triesCap = MAX_TRIES
    /** When set, every kept sample is also stored here, whole, for [reuse] when the map shows more; [srcId] names this sampler's batches. */
    internal var store: ArrayList<Stored>? = null
    internal var srcId = 0
    /** The most samples [store] takes (checked at each batch: no more once it holds this many). */
    internal var storeLimit = Int.MAX_VALUE
    private var batchNo = 0

    init {
        val ent = view.kinds.indexOf(Kind.ENTRANCE)
        entCell = if (ent < 0) -1 else view.roomTiles[ent][0]
        inward = when {
            entCell < 0 -> -1
            entCell < w -> entCell + w
            entCell >= n - w -> entCell - w
            entCell % w == 0 -> entCell + 1
            else -> entCell - 1
        }
        val a = ArrayList<Int>(); val b = ArrayList<Int>(); val k = ArrayList<Byte>()
        val wa = ArrayList<Int>(); val wb = ArrayList<Int>()
        for (t in 0 until n) for (u in nb[t]) {
            val e = view.edge(t, u)
            if (e == Edge.NONE && u > t) { wa += t; wb += u }
            if (!Edge.isDoor(e)) continue
            touchesDoor[t] = true
            if (u > t) { a += t; b += u; k += e }
            if (view.roomAt[t] < 0 && view.roomAt[u] >= 0) doorIn[t] = true
        }
        shownA = a.toIntArray(); shownB = b.toIntArray(); shownKind = k.toByteArray()
        wallA = wa.toIntArray(); wallB = wb.toIntArray()
        for (i in 0 until nKnown) {
            val shape = view.shapes[i]
            if (shape != Shape.ONE) knownBig[shape.ordinal]++
            when (view.kinds[i]) {
                Kind.TRAP -> knownSpecial[T]++
                Kind.YELLOW -> knownSpecial[Y]++
                Kind.PUZZLE -> knownSpecial[P]++
                Kind.RARE -> knownSpecial[R]++
                Kind.BLOOD -> if (knownBlood < 0) knownBlood = i
                Kind.FAIRY -> if (knownFairy < 0) knownFairy = i
                Kind.NORMAL -> if (shape == Shape.ONE && doorsOf(view.roomTiles[i][0]) == 1) knownSpecial[R]++
                Kind.ENTRANCE -> {}
            }
        }
        val ws = ArrayList<Double>()
        for (i in GameStats.MIXES.indices) {
            val x = mixWeight(i, knownBig)
            if (x > 0) { mixes += GameStats.MIXES[i]; ws += x }
        }
        mixW = ws.toDoubleArray(); mixTotal = mixW.sum()
        spots = Array(5) { s ->
            val out = ArrayList<IntArray>()
            for (offs in MapPredictor.PLACEMENTS[s]) for (t0 in 0 until n) {
                val x0 = t0 % w; val z0 = t0 / w
                val cs = IntArray(offs.size / 2); var ok = true
                for (c in cs.indices) {
                    val x = x0 + offs[c * 2]; val z = z0 + offs[c * 2 + 1]
                    if (x >= w || z >= h) { ok = false; break }
                    cs[c] = z * w + x
                }
                if (ok) out += cs
            }
            out.toTypedArray()
        }
        sideMu = DoubleArray(n) { t -> if (entCell < 0) 1.0 else exp(-rule.mu * (abs(t % w - entCell % w) + abs(t / w - entCell / w))) }
        possible = entCell >= 0 && inward in 0 until n && mixTotal > 0 && cells.isNotEmpty()
        if (!possible) done = true
    }

    private fun doorsOf(t: Int) = nb[t].count { Edge.isDoor(view.edge(t, it)) }

    /** Batches' worth of weight so far: (sum of batch weights)^2 / sum of their squares. */
    fun effectiveBatches(): Double = if (batchW2 > 0) batchW1 * batchW1 / batchW2 else 0.0

    /**
     * The samples kept, once [done]; 0 until then. A caller that stops at the first call keeping nothing once there
     * are samples (the map prediction worker) so goes on until [MIN_BATCHES] batches' worth of weight back them: on a
     * hard map a few calls in a row can keep nothing, and a batch's samples come from few layouts. Its [prediction]
     * is there all along.
     */
    val accepted: Int get() = if (done) kept else 0

    /**
     * The sampler is [done] with too little to go on: the budget ran out before [MIN_SHOW] batches' worth of weight was
     * kept (a hard map: few of the trees grown come out right, and a handful of samples from one or two ancestors can
     * show a room as certain that isn't), or nothing fit at all. The caller takes the classic sampler instead.
     */
    val failed: Boolean get() = done && effectiveBatches() < MIN_SHOW

    /**
     * Batches until [samples] more are kept, about [maxTries] particles started (at most [CALL_TRIES] a call, so the
     * caller can show what there is), or [done]; the samples kept.
     */
    fun run(samples: Int, maxTries: Int, rnd: Random): Int {
        val k0 = kept; val t0 = tries
        val budget = minOf(maxTries.toLong(), CALL_TRIES)
        while (!done && kept - k0 < samples && tries - t0 < budget) {
            batch(rnd)
            if (kept >= MIN_SAMPLES && effectiveBatches() >= MIN_BATCHES || tries >= triesCap) done = true
            else if (kept == 0 && tries - emptyFrom >= EMPTY_TRIES) {
                // nothing fits: the secret total first (a misread one; if no particle got as far as the names, they
                // weren't the trouble), then give up ([failed]: the caller falls back)
                if (secretsOn && namesTried) { secretsOn = false; emptyFrom = tries } else done = true
            }
        }
        return kept - k0
    }

    /**
     * What the samples so far agree on; null before any was kept, and while they are too few to show ([MIN_SHOW]
     * batches' worth of weight: right after a door opens the first batch's few samples come from few ancestors and
     * would show rooms as near certain; the caller keeps the last prediction up meanwhile). The tiles' chances go
     * through [CALIBRATION] ([Prediction.raw] has them as the samples give them).
     */
    fun prediction(): Prediction? =
        if (kept == 0 || !done && effectiveBatches() < MIN_SHOW) null
        else tally.prediction(w, h, cells, entries, entryDoor, kept, 0) { MapPredictor.calibrate(it, CALIBRATION, nKnown) }

    // ---- lanes and the pool: several samplers of one view side by side, and samples carried from earlier views ([PredictionSession]) ----

    /**
     * Batches until about [maxTries] more particles have been started or [until] (asked after each batch) says stop.
     * None of [run]'s stop rule: the caller adds this sampler's [part] to the other lanes' and to the carried-over
     * samples', and decides when that is enough.
     */
    internal fun runBatches(maxTries: Int, rnd: Random, until: () -> Boolean = { false }) {
        val t0 = tries
        while (tries - t0 < maxTries && !until()) batch(rnd)
    }

    /** What this sampler's own samples add up to so far. */
    internal fun part(): Part = Part(tally, kept, batchW1, batchW2, sampleW2)

    /** The secret total's names off (nothing fit with them: a misread total). */
    internal fun dropNames() { secretsOn = false }

    /** Some particle got as far as the names ([run]'s reason to try without them). */
    internal val namesWereTried: Boolean get() = namesTried

    /** The tiles' chances through [CALIBRATION], for a [tally] of [samples] kept samples. */
    internal fun predictionOf(tally: Tally, samples: Int): Prediction? =
        tally.prediction(w, h, cells, entries, entryDoor, samples, 0) { MapPredictor.calibrate(it, CALIBRATION, nKnown) }

    /**
     * The prediction of several [parts] together, each counted by its [weights] (its batches' worth: each part is a
     * self-normalised estimate, and the mean of independent estimates weighted by how many batches' worth stand
     * behind each is the one with the least spread); null while there is no sample.
     */
    internal fun predictionOf(parts: List<Part>, weights: DoubleArray): Prediction? {
        var live = 0; var only = -1; var samples = 0
        for (i in parts.indices) if (parts[i].kept > 0 && parts[i].tally.weight > 0 && weights[i] > 0) { live++; only = i; samples += parts[i].kept }
        if (live == 0) return null
        if (live == 1) return predictionOf(parts[only].tally, samples)
        val sum = Tally(cells.size, entries.size)
        for (i in parts.indices) {
            val p = parts[i]
            if (p.kept > 0 && p.tally.weight > 0 && weights[i] > 0) sum.addScaled(p.tally, weights[i] / p.tally.weight)
        }
        return predictionOf(sum, samples)
    }

    /**
     * What [reuse] makes of a set of stored samples under this view: the samples that fit it ([survivors], batch by
     * batch as they came) and their [part] with this view's finish.
     */
    internal class Reused(val survivors: ArrayList<Stored>, val part: Part, val anyNames: Boolean, val minRooms: Int)

    /**
     * Everything but the secret total's names that a sample's finish reads of the view: its rooms, doors and walls, the
     * specials shown, the tab's puzzle order. Two views with the same signature finish every sample alike unless the
     * names weigh it ([namesUpTo]), so a part worked out for one is the other's too.
     */
    internal val finishSignature: String by lazy {
        view.structureKey() + "|" + view.kinds.indices.sortedBy { view.roomTiles[it].min() }.joinToString(",") { "${view.roomTiles[it].min()}k${view.kinds[it].ordinal}" } +
            "|" + pinKeys.contentToString() + pinBefore.contentToString() + "|" + view.puzzles
    }

    /** A sample of at most this many rooms has its finish weigh the secret total's names (-1: none does, there is no total or it was given up). */
    internal val namesUpTo: Int get() = if (secrets != null && secretsOn) SecretNames.MAX_ROOMS - unnamedKnown.size + nKnown else -1

    /**
     * The factor that takes this view's particle weights ([Tally.weight] is their sum over the kept) to the one scale all
     * views share: the game's chance of a map (restricted to what the view allows) over the chance a particle came out as
     * that map. The code leaves out two factors that are constant within a view: the mixes are drawn by their weights, whose
     * total [mixTotal] is the weight of drawing one, and each big room already on the map is one of the `1 / spots` of
     * the game's draws a room of its shape gets. So for views A and B (B showing more), `tally.weight * scale / particles
     * started` estimates the same thing for both: the chance of the map showing A or B at all, and the share of A's weight
     * that fits B ([reuse]) is B's over A's (a test checks it). The mean of the parts is taken without it ([Part]s are
     * self-normalised estimates, weighed by their batches' worth); this is for tests and checks.
     */
    internal val scale: Double
        get() { var k = mixTotal; for (s in 0 until 5) repeat(knownBig[s]) { k /= spots[s].size }; return k }

    private val cntBuf = IntArray(64)
    private val idBuf = IntArray(64)

    /**
     * Samples drawn for an earlier view this one refines ([MapView.refines]), carried over. The sample was drawn with
     * weight w = (the game's chance of its map, restricted to what the earlier view allowed) / (the chance this
     * sampler's kind of particle came out that way), so it is a draw of this view's maps too, at the same weight, if it
     * fits this view: its rooms where this view's are (the same tiles), every door of this view's in its tree, no wall of
     * this view's crossed. The rest of what this view says (the specials, blood and the fairy, the tab's order, the secret
     * total's names) is in the finish, which is worked out again here for each: the weight is w times that, as when the
     * sample was kept. What does not fit, or has no finish now, can never fit a view that says more: it is not kept.
     *
     * Samples whose names weighed ([Stored.names]) under a secret total this view doesn't allow ([namesBlocked]) leave none
     * of [from] to reuse: the samples that total (and no other) would have let through were thrown away when they were
     * kept, so those that are left would count for too much, and the ones whose names did not weigh are only the maps
     * with more rooms still to come (nobody named them), which, without the others, would pull the mean towards such maps.
     *
     * [from] has each batch's samples together (the batch's identity, for its weight in [Part.w1]/[Part.w2]).
     */
    internal fun reuse(from: List<Stored>): Reused {
        val t = Tally(cells.size, entries.size)
        val out = ArrayList<Stored>()
        if (namesBlocked(from)) return Reused(out, Part(t, 0, 0.0, 0.0), false, Int.MAX_VALUE)
        val totals = view.secretTotals
        var count = 0; var w1 = 0.0; var w2 = 0.0; var bw = 0.0; var s2 = 0.0
        var anyNames = false; var minRooms = Int.MAX_VALUE
        var cur = -1L
        for (s in from) {
            val key = (s.src.toLong() shl 32) or (s.batch.toLong() and 0xffffffffL)
            if (key != cur) { w1 += bw; w2 += bw * bw; bw = 0.0; cur = key }
            val pt = rebuild(s) ?: continue
            val fin = finish(pt) ?: continue
            val wgt = s.w * fin.f
            if (!(wgt > 0) || wgt.isInfinite()) continue
            add(t, pt, fin, wgt); count++
            bw += wgt; s2 += wgt * wgt
            val names = fin.names != null
            s.names = names; s.totals = if (names) totals else Stored.NO_TOTALS
            if (names) anyNames = true
            if (pt.rooms.size < minRooms) minRooms = pt.rooms.size
            out += s
        }
        w1 += bw; w2 += bw * bw
        return Reused(out, Part(t, count, w1, w2, s2), anyNames, minRooms)
    }

    /**
     * Some sample of [from] was finished with the secret total's names under totals that do not hold all of this view's,
     * or under none: it let through only the maps that total allowed, so what a total the view adds would let through is
     * missing, and the samples without names left are a pool of the maps with more rooms ([reuse]).
     */
    internal fun namesBlocked(from: List<Stored>): Boolean {
        val totals = view.secretTotals
        for (s in from) if (s.names && !(totals.isNotEmpty() && totals.all { it in s.totals })) return true
        return false
    }

    private val noBools = BooleanArray(0)
    private val noInts = IntArray(0)

    /** A stored sample as a particle of this view's, or null when it doesn't fit it ([reuse]). */
    private fun rebuild(s: Stored): Particle? {
        val par = s.parent; val wo = s.whole.owner; val sh = s.whole.shape
        for (e in shownA.indices) if (par[shownA[e]].toInt() != shownB[e] && par[shownB[e]].toInt() != shownA[e]) return null
        for (e in wallA.indices) if (par[wallA[e]].toInt() == wallB[e] || par[wallB[e]].toInt() == wallA[e]) return null
        val nr = sh.size
        val cnt = cntBuf; val id = idBuf
        java.util.Arrays.fill(cnt, 0, nr, 0); java.util.Arrays.fill(id, 0, nr, -1)
        for (t in 0 until n) cnt[wo[t].toInt()]++
        // the discovered rooms are the sample's rooms with the same tiles; the others keep their order after them
        for (i in 0 until nKnown) {
            val ts = view.roomTiles[i]; val r = wo[ts[0]].toInt()
            if (cnt[r] != ts.size || sh[r].toInt() != view.shapes[i].ordinal) return null
            for (tile in ts) if (wo[tile].toInt() != r) return null
            id[r] = i
        }
        var next = nKnown
        for (r in 0 until nr) if (id[r] < 0) id[r] = next++
        val owner = IntArray(n) { id[wo[it].toInt()] }
        val shape = IntArray(nr); val size = IntArray(nr)
        for (r in 0 until nr) { shape[id[r]] = sh[r].toInt(); size[id[r]] = cnt[r] }
        val rooms = Array(nr) { r -> if (r < nKnown) view.roomTiles[r] else IntArray(size[r]) }
        val at = IntArray(nr)
        for (tile in 0 until n) { val r = owner[tile]; if (r >= nKnown) rooms[r][at[r]++] = tile }
        val parent = IntArray(n) { par[it].toInt() }
        val depth = IntArray(n) { -1 }
        for (tile in 0 until n) depthOf(parent, depth, tile)
        val rentry = IntArray(nr) { -1 }
        for (tile in 0 until n) { val p = parent[tile]; if (p < 0 || owner[p] != owner[tile]) rentry[owner[tile]] = tile }
        return Particle(owner, rooms, shape, noBools, noBools, parent, depth, noBools, rentry, noInts, noInts, 0, s.w, true)
    }

    private fun depthOf(parent: IntArray, depth: IntArray, t: Int): Int {
        if (depth[t] >= 0) return depth[t]
        val p = parent[t]
        val d = if (p < 0) 0 else depthOf(parent, depth, p) + 1
        depth[t] = d
        return d
    }

    // ---- the particles: a layout and its door tree so far ----

    private class Particle(
        /** Each tile's room: the discovered ones first (the view's), then the big rooms drawn, then 1x1s. */
        val owner: IntArray,
        val rooms: Array<IntArray>,
        /** Each room's [Shape] ordinal. */
        val shape: IntArray,
        /** Each room has a door the map shows (else no shown door can close a loop through it). */
        val shown: BooleanArray,
        val inT: BooleanArray,
        val parent: IntArray,
        val depth: IntArray,
        val entered: BooleanArray,
        val rentry: IntArray,
        /** Each tile's steps from where its room was entered, over the room's own tiles. */
        val rdist: IntArray,
        /** The rooms of the tree in the order they joined it (the entrance's isn't one: nothing grows off it). */
        val nodes: IntArray,
        var nNodes: Int,
        var w: Double,
        var done: Boolean,
    ) {
        fun copy(w: Double) = Particle(owner, rooms, shape, shown, inT.copyOf(), parent.copyOf(), depth.copyOf(), entered.copyOf(),
            rentry.copyOf(), rdist.copyOf(), nodes.copyOf(), nNodes, w, done)
        fun big(r: Int) = shape[r] != Shape.ONE.ordinal
    }

    private val fitBuf = IntArray(128)

    /**
     * 1. A new particle: a mix, then the big rooms not on the map yet one by one in random order, each on a spot it
     * fits, drawn alike and weighted by spots it fits / spots inside the map (the chance one random spot fits); the
     * rest 1x1s; the entrance's door inward taken.
     */
    private fun newParticle(rnd: Random): Particle? {
        var r0 = rnd.nextDouble() * mixTotal; var mi = 0
        while (r0 > mixW[mi] && mi < mixW.size - 1) { r0 -= mixW[mi]; mi++ }
        val mix = mixes[mi]
        val todo = ArrayList<Int>(7)
        for (s in 0 until 5) repeat(mix[s] - knownBig[s]) { todo += s }
        todo.shuffle(rnd)
        val owner = view.roomAt.copyOf()
        val rooms = ArrayList<IntArray>(24); val shape = ArrayList<Int>(24)
        for (i in 0 until nKnown) { rooms += view.roomTiles[i]; shape += view.shapes[i].ordinal }
        var wt = 1.0
        for (s in todo) {
            val all = spots[s]; var nFit = 0
            for (j in all.indices) if (fits(all[j], owner)) fitBuf[nFit++] = j
            if (nFit == 0) return null
            wt *= nFit.toDouble() / all.size
            val cs = all[fitBuf[rnd.nextInt(nFit)]]
            for (t in cs) owner[t] = rooms.size
            rooms += cs; shape += s
        }
        for (t in cells) if (owner[t] < 0) { owner[t] = rooms.size; rooms += intArrayOf(t); shape += Shape.ONE.ordinal }
        val nr = rooms.size
        val roomArr = rooms.toTypedArray()
        val shown = BooleanArray(nr) { r -> roomArr[r].any { touchesDoor[it] } }
        val pt = Particle(owner, roomArr, shape.toIntArray(), shown, BooleanArray(n), IntArray(n) { -1 }, IntArray(n), BooleanArray(nr),
            IntArray(nr) { -1 }, IntArray(n), IntArray(nr), 0, wt, false)
        pt.inT[entCell] = true; pt.entered[owner[entCell]] = true; pt.rentry[owner[entCell]] = entCell
        if (!allowed(pt, entCell, inward)) return null
        addCell(pt, entCell, inward)
        return pt
    }

    /** A spot is free, and has at most one door from the discovered part (two would close a loop). */
    private fun fits(cs: IntArray, owner: IntArray): Boolean {
        var doors = 0
        for (t in cs) { if (owner[t] != -1) return false; if (doorIn[t]) doors++ }
        return doors <= 1
    }

    /** Tile [v] joins the tree off [u], and with it the rest of its room (joined inside by its steps from [v]). */
    private fun addCell(pt: Particle, u: Int, v: Int) {
        val r = pt.owner[v]
        pt.inT[v] = true; pt.parent[v] = u; pt.depth[v] = pt.depth[u] + 1
        pt.entered[r] = true; pt.rentry[r] = v
        val cs = pt.rooms[r]
        if (cs.size > 1) {
            // a path's inside is the path; a 2x2's is summed over when the tree is finished ([insides])
            pt.rdist[v] = 0
            val q = IntArray(cs.size); var qh = 0; var qt = 0; q[qt++] = v
            while (qh < qt) {
                val a = q[qh++]
                for (b in nb[a]) if (pt.owner[b] == r && !pt.inT[b]) {
                    pt.inT[b] = true; pt.parent[b] = a; pt.depth[b] = pt.depth[a] + 1; pt.rdist[b] = pt.rdist[a] + 1
                    q[qt++] = b
                }
            }
        }
        pt.nodes[pt.nNodes++] = r
    }

    /**
     * The map allows the step [u] -> [v]: not through a wall the map shows, and into a room with no other door the map
     * shows to a room already in the tree (that door would close a loop).
     */
    private fun allowed(pt: Particle, u: Int, v: Int): Boolean {
        if (view.edge(u, v) == Edge.NONE) return false
        val owner = pt.owner; val s = owner[v]
        if (s == owner[u] || !pt.shown[s]) return true
        for (c in pt.rooms[s]) for (x in nb[c]) {
            val rx = owner[x]
            if (rx == s || !pt.entered[rx] || c == v && x == u) continue
            if (Edge.isDoor(view.edge(c, x))) return false
        }
        return true
    }

    private val sideU = IntArray(4 * 36)
    private val sideV = IntArray(4 * 36)
    private val sideP = DoubleArray(4 * 36)

    private fun nodeWeight(sw: Double) = when (rule.alpha) { 0.5 -> sqrt(sw); 1.0 -> sw; 0.0 -> 1.0; else -> sw.pow(rule.alpha) }

    /**
     * 2. One step of the tree: the game's chance of each free side (a room by its sides' weight^alpha, then a side by
     * its weight); the particle's weight times the share the map allows, and one of those drawn by the game's chances.
     */
    private fun grow(pt: Particle, rnd: Random) {
        var ns = 0; var z = 0.0
        for (i in 0 until pt.nNodes) {
            val r = pt.nodes[i]; val s0 = ns; var sw = 0.0
            for (u in pt.rooms[r]) {
                if (u == entCell) continue
                for (v in nb[u]) if (!pt.inT[v]) {
                    val x = sideBeta[minOf(pt.rdist[u], 4)] * sideMu[v]
                    sideU[ns] = u; sideV[ns] = v; sideP[ns] = x; ns++; sw += x
                }
            }
            if (ns > s0) { val nw = nodeWeight(sw); for (k in s0 until ns) sideP[k] *= nw / sw; z += nw }
        }
        if (ns == 0) { pt.done = true; return }
        var c = 0.0
        for (k in 0 until ns) if (allowed(pt, sideU[k], sideV[k])) { sideP[k] /= z; c += sideP[k] } else sideP[k] = 0.0
        if (c <= 0) { pt.w = 0.0; return }
        pt.w *= c
        var r = rnd.nextDouble() * c; var pick = -1
        for (k in 0 until ns) {
            if (sideP[k] <= 0) continue
            pick = k
            if (r < sideP[k]) break
            r -= sideP[k]
        }
        addCell(pt, sideU[pick], sideV[pick])
    }

    private fun ess(ps: List<Particle>): Double {
        var a = 0.0; var b = 0.0
        for (p in ps) { a += p.w; b += p.w * p.w }
        return if (b > 0) a * a / b else 0.0
    }

    /** Systematic resampling: as many particles, each a copy of one by its weight, all at the mean weight. */
    private fun resample(ps: ArrayList<Particle>, rnd: Random): ArrayList<Particle> {
        val pos = ps.filter { it.w > 0 }
        val tot = pos.sumOf { it.w }
        if (pos.isEmpty() || tot <= 0) return ps
        val m = ps.size; val step = tot / m
        val out = ArrayList<Particle>(m)
        val u = rnd.nextDouble() * step; var c = 0.0; var i = 0
        for (k in 0 until m) {
            val target = u + k * step
            while (c + pos[i].w < target && i < pos.size - 1) { c += pos[i].w; i++ }
            out += pos[i].copy(step)
        }
        return out
    }

    /** One batch: [PARTICLES] particles grown together, resampled when their weights spread out, the finished ones kept. */
    private fun batch(rnd: Random) {
        var ps = ArrayList<Particle>(PARTICLES)
        repeat(PARTICLES) { tries++; newParticle(rnd)?.let { ps += it } }
        if (ps.isEmpty()) return
        // the batch size kept up: the fillers weigh nothing and drop at the next resample
        while (ps.size < PARTICLES) ps += ps[rnd.nextInt(ps.size)].copy(0.0)
        if (ess(ps) < PARTICLES / 2.0) ps = resample(ps, rnd)
        for (step in 0 until n - 2) {
            for (pt in ps) if (pt.w > 0 && !pt.done) grow(pt, rnd)
            if (ps.none { it.w > 0 && !it.done }) break
            if (ess(ps) < PARTICLES / 2.0) ps = resample(ps, rnd)
        }
        var bw = 0.0
        val sink = store?.takeIf { it.size < storeLimit }
        val wholes = if (sink != null) java.util.IdentityHashMap<IntArray, Whole>() else null
        for (pt in ps) {
            if (!(pt.w > 0) || !pt.inT.all { it }) continue
            val fin = finish(pt) ?: continue
            val wgt = pt.w * fin.f
            if (!(wgt > 0) || wgt.isInfinite()) continue
            add(tally, pt, fin, wgt); kept++
            bw += wgt; sampleW2 += wgt * wgt
            if (sink != null) sink += keep(pt, fin, wholes!!)
        }
        batchW1 += bw; batchW2 += bw * bw
        batchNo++
    }

    /** A kept particle as a [Stored] sample; the particles of one ancestor share their layout. */
    private fun keep(pt: Particle, fin: Finish, wholes: java.util.IdentityHashMap<IntArray, Whole>): Stored {
        val whole = wholes.getOrPut(pt.owner) { Whole(ByteArray(n) { pt.owner[it].toByte() }, ByteArray(pt.shape.size) { pt.shape[it].toByte() }) }
        val s = Stored(whole, ByteArray(n) { pt.parent[it].toByte() }, pt.w, srcId, batchNo)
        if (fin.names != null) { s.names = true; s.totals = view.secretTotals }
        return s
    }

    // ---- 3. the finished tree ----

    /** A 2x2's inside (or every room's, when there's no 2x2): each tile's parent and walk from the entrance, and its chance. */
    private class Inside(val parent: IntArray, val depth: IntArray, val p: Double)

    /**
     * The tree's tiles over every combination of its 2x2s' insides: a tree on the 4 tiles grown from where the room was
     * entered (v joined to both its neighbours, the fourth off either, 3/8 each; a path round from v, 1/8 each way).
     */
    private fun insides(pt: Particle): List<Inside> {
        var sets = listOf(IntArray(0) to 1.0)  // (tile, parent) pairs to set, and their chance
        for (r in pt.rooms.indices) {
            if (pt.shape[r] != Shape.BOX.ordinal) continue
            val v = pt.rentry[r]; val cs = pt.rooms[r]
            val ab = nb[v].filter { pt.owner[it] == r }
            val a = ab[0]; val b = ab[1]; val d = cs.first { it != v && it != a && it != b }
            val opts = listOf(
                intArrayOf(a, v, b, v, d, a) to 3.0 / 8, intArrayOf(a, v, b, v, d, b) to 3.0 / 8,
                intArrayOf(a, v, d, a, b, d) to 1.0 / 8, intArrayOf(b, v, d, b, a, d) to 1.0 / 8,
            )
            sets = sets.flatMap { (set, p) -> opts.map { (o, q) -> (set + o) to p * q } }
        }
        if (sets.size == 1) return listOf(Inside(pt.parent, pt.depth, 1.0))
        return sets.map { (set, p) ->
            val parent = pt.parent.copyOf()
            for (i in set.indices step 2) parent[set[i]] = set[i + 1]
            val depth = IntArray(n) { -1 }; depth[entCell] = 0
            fun dep(c: Int): Int { if (depth[c] < 0) depth[c] = dep(parent[c]) + 1; return depth[c] }
            for (c in 0 until n) dep(c)
            Inside(parent, depth, p)
        }
    }

    /** A finished tree's share: [f] (its weight's factor), each unknown 1x1's chance of each class, and its rooms' path chances. */
    private class Finish(
        val f: Double,
        val dist: Array<DoubleArray?>,
        /** Each room's chance of being on the way to blood (blood too), of being blood, of being the fairy. */
        val onPath: DoubleArray,
        val blood: DoubleArray,
        val fairy: DoubleArray,
        val rParent: IntArray,
        val rEntry: IntArray,
        /** (room tiles mask * 256 + name index) -> chance, with the secret total's names; null without. */
        val names: HashMap<Long, Double>?,
    )

    private fun finish(pt: Particle): Finish? {
        val owner = pt.owner; val nr = pt.rooms.size; val parent = pt.parent
        // every door the map shows is in the tree
        for (i in shownA.indices) { val a = shownA[i]; val b = shownB[i]; if (parent[a] != b && parent[b] != a) return null }
        val deg = IntArray(nr); val rParent = IntArray(nr) { -1 }; val rEntry = IntArray(nr) { -1 }
        for (v in 0 until n) {
            val u = parent[v]
            if (u < 0) { rEntry[owner[v]] = v; continue }
            if (owner[u] != owner[v]) { deg[owner[u]]++; deg[owner[v]]++; rParent[owner[v]] = owner[u]; rEntry[owner[v]] = v }
        }
        val entR = owner[entCell]
        val dead = (0 until nr).filter { it != entR && !pt.big(it) && deg[it] == 1 }
        if (dead.size < deadMin || dead.size > deadMax) return null
        val d = dead.size - 1  // besides blood
        // the specials besides blood and the fairy: one on each other dead end, the ones on the map where they are
        val need = intArrayOf(1, 1, puzzles, maxOf(0, d - 2 - minOf(puzzles, 5)))
        val left = IntArray(4) { need[it] - knownSpecial[it] }
        if (left.any { it < 0 }) return null
        val m = left.sum()
        val deadU = dead.filter { it >= nKnown }
        if (deadU.size - (if (knownBlood >= 0) 0 else 1) != m) return null
        // the chance a shuffle of every special over the dead ends puts the map's ones where they are
        val wType = (fact(m) / (fact(left[T]) * fact(left[Y]) * fact(left[P]) * fact(left[R]))) /
            (fact(d) / (fact(need[T]) * fact(need[Y]) * fact(need[P]) * fact(need[R])))

        // blood and the fairy over the 2x2s' insides: each dead end 9 or 10 steps out with a 1x1 four steps back is
        // the game's pick alike; the ones that fit the map keep their share
        val pb = ArrayList<Int>(); val pf = ArrayList<Int>(); val pw = ArrayList<Double>()
        val fitsPath = HashMap<Int, Boolean>()
        for (va in insides(pt)) {
            var nAll = 0; val start = pb.size
            for (b in dead) {
                val t = pt.rooms[b][0]; val dep = va.depth[t]
                if (dep != WALK && dep != WALK + 1) continue
                var f = t; repeat(FAIRY_BEFORE) { f = va.parent[f] }
                val fr = owner[f]
                if (fr == entR || pt.big(fr)) continue
                nAll++
                if (b < nKnown && view.kinds[b] != Kind.BLOOD || fr < nKnown && view.kinds[fr] != Kind.FAIRY) continue
                if (knownBlood >= 0 && b != knownBlood || knownFairy >= 0 && fr != knownFairy) continue
                if (!fitsPath.getOrPut(b) { pathFits(b, rParent, rEntry, parent) }) continue
                pb += b; pf += fr; pw += va.p
            }
            for (i in start until pb.size) pw[i] = pw[i] / nAll
        }
        if (pb.isEmpty()) return null

        val names = secretsOn && secrets != null && unnamedKnown.size + nr - nKnown <= SecretNames.MAX_ROOMS
        if (names) namesTried = true
        return if (names) finishNamed(pt, deadU, left, pb, pf, pw, rParent, rEntry, wType)
            else finishSummed(pt, deadU, left, pb, pf, pw, rParent, rEntry, wType)
    }

    /** The specials summed over in closed form: every (blood, fairy) pick, and each other dead end's share of the shuffle. */
    private fun finishSummed(pt: Particle, deadU: List<Int>, left: IntArray, pb: List<Int>, pf: List<Int>, pw: List<Double>,
                             rParent: IntArray, rEntry: IntArray, wType: Double): Finish? {
        val nr = pt.rooms.size; val m = left.sum()
        val pins = pinKeys.isNotEmpty()
        val pinCache = HashMap<Int, PinShare?>()
        val pinOf = { b: Int -> if (!pins) null else pinCache.getOrPut(b) { pinShare(pt, deadU.filter { it != b }, left[P]) } }
        var fsum = 0.0
        val ws = DoubleArray(pb.size)
        for (i in pb.indices) {
            val share = if (pins) pinOf(pb[i])?.fit ?: 0.0 else 1.0
            ws[i] = pw[i] * share; fsum += ws[i]
        }
        if (!(fsum > 0)) return null
        val dist = arrayOfNulls<DoubleArray>(nr)
        val at = { r: Int -> dist[r] ?: DoubleArray(Cls.COUNT).also { dist[r] = it } }
        val onPath = DoubleArray(nr); val blood = DoubleArray(nr); val fairy = DoubleArray(nr)
        val entR = pt.owner[entCell]
        for (i in pb.indices) {
            val q = ws[i] / fsum; if (q <= 0) continue
            val b = pb[i]; val fr = pf[i]
            if (b >= nKnown) at(b)[Cls.BLOOD] += q
            if (fr >= nKnown) at(fr)[Cls.FAIRY] += q
            blood[b] += q; fairy[fr] += q
            var x = b; while (x >= 0 && x != entR) { onPath[x] += q; x = rParent[x] }
            if (m == 0) continue
            val pin = pinOf(b)
            for (r in deadU) {
                if (r == b) continue
                val dr = at(r)
                if (pin == null) { for (c in 0 until 4) if (left[c] > 0) dr[CLS[c]] += q * left[c] / m; continue }
                // the puzzles by the tab's order (its stretch's count over its dead ends), the rest at random on the others
                val pp = pin.puzzleChance[r] ?: 0.0
                dr[Cls.PUZZLE] += q * pp
                val others = m - left[P]
                if (others > 0) for (c in intArrayOf(T, Y, R)) if (left[c] > 0) dr[CLS[c]] += q * (1 - pp) * left[c] / others
            }
        }
        return Finish(fsum * wType, dist, onPath, blood, fairy, rParent, rEntry, null)
    }

    /** How the tab's named puzzles split the dead ends: the share of puzzle placements that fit, and each dead end's chance of a puzzle. */
    private class PinShare(val fit: Double, val puzzleChance: Map<Int, Double>)

    private fun pinShare(pt: Particle, slots: List<Int>, puzzlesLeft: Int): PinShare? {
        val stretch = Array(pinKeys.size + 1) { ArrayList<Int>() }
        for (r in slots) stretch[stretchOf(pt.rooms[r][0])] += r
        var before = 0; var ways = 1.0
        val chance = HashMap<Int, Double>()
        for (g in stretch.indices) {
            val c = (if (g < pinKeys.size) pinBefore[g] else puzzlesLeft) - before
            if (c < 0 || c > stretch[g].size) return null
            ways *= MapPredictor.choose(stretch[g].size, c)
            for (r in stretch[g]) chance[r] = c.toDouble() / stretch[g].size
            before += c
        }
        val all = MapPredictor.choose(slots.size, puzzlesLeft)
        return if (all > 0) PinShare(ways / all, chance) else null
    }

    /** The puzzles' tab order: column by column, west to east, each north to south. */
    private fun orderKey(t: Int): Int = (t % w) * h + t / w

    /**
     * The last few rooms with the secret total's names: every (blood, fairy) pick and every shuffle of the specials
     * worked out, each weighted by the chance names drawn for its rooms make the total ([SecretNames.solve]).
     */
    private fun finishNamed(pt: Particle, deadU: List<Int>, left: IntArray, pb: List<Int>, pf: List<Int>, pw: List<Double>,
                            rParent: IntArray, rEntry: IntArray, wType: Double): Finish? {
        val sn = secrets ?: return null
        val nr = pt.rooms.size; val m = left.sum()
        val classes = ArrayList<Int>(m); for (c in 0 until 4) repeat(left[c]) { classes += CLS[c] }
        val perms = permutations(classes)
        val masks = LongArray(nr) { r -> var x = 0L; for (t in pt.rooms[r]) x = x or (1L shl t); x }
        val dist = arrayOfNulls<DoubleArray>(nr)
        val at = { r: Int -> dist[r] ?: DoubleArray(Cls.COUNT).also { dist[r] = it } }
        val onPath = DoubleArray(nr); val blood = DoubleArray(nr); val fairy = DoubleArray(nr)
        val names = HashMap<Long, Double>()
        val entR = pt.owner[entCell]
        val cls = IntArray(nr)
        val pools = IntArray(unnamedKnown.size + nr - nKnown)
        for (i in unnamedKnown.indices) pools[i] = unnamedKnown[i]
        var fsum = 0.0
        for (i in pb.indices) {
            val b = pb[i]; val fr = pf[i]
            val slots = deadU.filter { it != b }
            for (perm in perms) {
                // the tab's order: as many puzzles in each stretch between named ones as it lists
                if (pinKeys.isNotEmpty() && !pinsFit(pt, slots, perm, left[P])) continue
                for (r in nKnown until nr) cls[r] = if (pt.big(r)) Cls.BIG else Cls.NORMAL
                if (b >= nKnown) cls[b] = Cls.BLOOD
                if (fr >= nKnown) cls[fr] = Cls.FAIRY
                slots.forEachIndexed { j, r -> cls[r] = perm[j] }
                for (r in nKnown until nr) pools[unnamedKnown.size + r - nKnown] = MapPredictor.poolOfCls(cls[r], Shape.entries[pt.shape[r]])
                val res = sn.solve(pools)
                val lik = res?.likelihood ?: 1.0
                val q = pw[i] / perms.size * lik
                if (!(q > 0)) continue
                fsum += q
                for (r in nKnown until nr) if (!pt.big(r) && cls[r] != Cls.NORMAL) at(r)[cls[r]] += q
                blood[b] += q; fairy[fr] += q
                var x = b; while (x >= 0 && x != entR) { onPath[x] += q; x = rParent[x] }
                if (res != null) for (r in nKnown until nr) {
                    val pool = MapPredictor.poolOfCls(cls[r], Shape.entries[pt.shape[r]])
                    res.byPool[pool]?.forEachIndexed { k, share ->
                        if (share > 0) names.merge(masks[r] * 256 + MapStats.NAMES.indexOf(sn.avail[pool][k]), q * share, Double::plus)
                    }
                }
            }
        }
        if (!(fsum > 0)) return null
        for (r in 0 until nr) dist[r]?.let { for (c in it.indices) it[c] /= fsum }
        for (r in 0 until nr) { onPath[r] /= fsum; blood[r] /= fsum; fairy[r] /= fsum }
        for (k in names.keys) names[k] = names.getValue(k) / fsum
        return Finish(fsum * wType, dist, onPath, blood, fairy, rParent, rEntry, names)
    }

    /** The shuffle [perm] of the specials over [slots] puts as many puzzles between the tab's named ones as it lists. */
    private fun pinsFit(pt: Particle, slots: List<Int>, perm: IntArray, puzzlesLeft: Int): Boolean {
        val inStretch = IntArray(pinKeys.size + 1)
        slots.forEachIndexed { j, r -> if (perm[j] == Cls.PUZZLE) inStretch[stretchOf(pt.rooms[r][0])]++ }
        var before = 0
        for (g in inStretch.indices) {
            val upTo = if (g < pinKeys.size) pinBefore[g] else puzzlesLeft
            if (inStretch[g] != upTo - before) return false
            before = upTo
        }
        return true
    }

    /** How many of the tab's named puzzles come before tile [t] in its order. */
    private fun stretchOf(t: Int): Int {
        val k = orderKey(t); var g = 0
        while (g < pinKeys.size && pinKeys[g] < k) g++
        return g
    }

    /** Every order of [items] (at most 4 here: the named case has at most that many unknown rooms), each once per position. */
    private fun permutations(items: List<Int>): List<IntArray> {
        if (items.isEmpty()) return listOf(IntArray(0))
        val out = ArrayList<IntArray>()
        fun go(rest: List<Int>, acc: List<Int>) {
            if (rest.isEmpty()) { out += acc.toIntArray(); return }
            for (i in rest.indices) go(rest.filterIndexed { j, _ -> j != i }, acc + rest[i])
        }
        go(items, emptyList())
        return out
    }

    /**
     * The path from the entrance to blood room [b]: the map's wither and blood doors on it, as the game makes them (the
     * entrance's room's door a plain one, blood's the blood door, the rest wither doors), and no plain door it shows there.
     */
    private fun pathFits(b: Int, rParent: IntArray, rEntry: IntArray, parent: IntArray): Boolean {
        val path = ArrayList<Int>()
        var r = b; while (r >= 0) { path += r; r = rParent[r] }
        path.reverse()
        val kind = HashMap<Int, Byte>()
        for (i in 0 until path.size - 1) {
            val v = rEntry[path[i + 1]]; val u = parent[v]
            kind[minOf(u, v) * n + maxOf(u, v)] = if (i == 0) Edge.DOOR else if (i == path.size - 2) Edge.BLOOD else Edge.WITHER
        }
        for (i in shownA.indices) if ((kind[shownA[i] * n + shownB[i]] ?: Edge.DOOR) != shownKind[i]) return false
        return true
    }

    /** A kept sample into the tally [t]: each unknown room's classes on its tiles and its door's split, its big room, the doors between unknown rooms. */
    private fun add(t: Tally, pt: Particle, fin: Finish, wgt: Double) {
        t.weight += wgt
        val p = DoubleArray(Cls.COUNT)
        for (r in nKnown until pt.rooms.size) {
            val cs = pt.rooms[r]
            p.fill(0.0)
            if (pt.big(r)) p[Cls.BIG] = 1.0
            else {
                val d = fin.dist[r]
                var rest = 1.0
                if (d != null) for (c in 0 until Cls.COUNT) { p[c] = d[c]; rest -= d[c] }
                // as sampler3.mjs: a rounding residue (1 - 1.0 = 1e-16 on a surely-puzzle tile) is no chance of a normal 1x1,
                // which the calibration's + 0.001 would lift to one
                if (rest > 1e-12) p[Cls.NORMAL] += rest
            }
            for (tile in cs) { val row = t.counts[tileIdx[tile]]; for (c in 0 until Cls.COUNT) if (p[c] > 0) row[c] += wgt * p[c] }
            // the door from the discovered part its side of the map hangs off
            var x = r; while (fin.rParent[x] >= nKnown) x = fin.rParent[x]
            val e = entryIndex[fin.rEntry[x]]
            if (e >= 0) {
                t.splitRooms[e] += wgt; t.splitTiles[e] += wgt * cs.size
                for (c in 0 until Cls.COUNT) t.splitCls[e][c] += wgt * p[c]
            }
            if (pt.big(r)) { var mask = 0L; for (tile in cs) mask = mask or (1L shl tile); t.bigCounts.merge(mask, wgt, Double::plus) }
            val u = fin.rParent[r]
            if (u >= nKnown) {
                val a = fin.rEntry[r]; val b = pt.parent[a]
                val dc = t.doorCounts.getOrPut(minOf(a, b) * n + maxOf(a, b)) { DoubleArray(4) }
                dc[0] += wgt
                dc[1] += wgt * maxOf(0.0, fin.onPath[r] - fin.blood[r])
                dc[2] += wgt * fin.blood[r]
                dc[3] += wgt * (fin.fairy[r] + fin.fairy[u])
            }
        }
        fin.names?.forEach { (k, s) -> t.nameCounts.merge(k, wgt * s, Double::plus) }
    }

    companion object {
        /** Particles a batch. */
        const val PARTICLES = 200
        /**
         * Samples, and batches' worth of weight, before the prediction is done. sampler3.mjs stops at 2000 samples and 20
         * batches' worth; this port runs about 9x faster and more keeps paying. Held-out per-tile log-likelihood, 495
         * snapshots of maps 200-298 (stages 2, 4, 6, 9, 12) run as the worker runs them, the mix table from the other
         * maps: 300 samples and 20 batches' worth -1.0297 (0.5 s a snapshot), 300 and 120 with 120,000 particles at most
         * -1.0182 (1.0 s), 2000 and 120 with the [MAX_TRIES] below -1.0115 (2.3 s, 6 to 8 s for the slowest tenth by
         * the machine's load; the classic sampler -1.0251, 1.7 s). The longer limit alone is -1.0146 with 300 samples,
         * -1.0144 with 1000: it is the 2000 (the classic sampler's, and the worker's own [OdinMapPrediction] SAMPLES)
         * that buys the rest.
         */
        const val MIN_SAMPLES = 2000
        const val MIN_BATCHES = 120.0
        /**
         * Batches' worth of weight before the prediction is shown at all, and the least a finished run can end with:
         * fewer than this ([failed]) and the classic sampler takes over. Few of the trees grown come out right on a hard
         * map (5 to 9 dead ends grown where the puzzles want exactly 2 more): at 120,000 particles two of the 495
         * snapshots ended with 15 and 21 samples (1.6 and 2.2 batches' worth), which scored -33.3 and -36.8 against the
         * classic sampler's -10.5 and -18.2. At [MAX_TRIES] one has 226 (9.4) and scores -8.8; the other has 53 (4.4) and
         * goes to the classic sampler (-18.4). No other snapshot ends below 16. Maps that end near 5 to 10 batches' worth
         * are noisy, and whether such a map hands over is a toss-up by the seed: over 8 seeds, one scored -10.4 to -10.6 the
         * 3 times it handed over after [EMPTY_TRIES] without a sample (the classic sampler's -10.5) and -11 to -19 the 5
         * times it kept 246 to 544; another handed over 7 times in 8 (-18.0 to -18.4). The cost over the 495 is under
         * 0.0004 a tile, and a higher [MIN_SHOW] would only swap which snapshot loses.
         */
        const val MIN_SHOW = 5.0
        /**
         * Particles started at most in one [run] call, in all, and without one kept before giving up. [MAX_TRIES] takes
         * about 8 s; 46 of the 495 snapshots used up 120,000 before the stop above, 34 use up 400,000 now. sampler3.mjs
         * goes to 800,000: on the hardest two that gave 498 samples at -8.9 (against 226 at -8.8) and 71 samples (6.6
         * batches' worth) at -25.0, which the classic sampler beats. [EMPTY_TRIES] without one kept takes about 0.5 s:
         * a map the game can't make costs what the classic sampler does after that (51 s for 2000 samples on the worst
         * of the 495, which shows its first after 0.6 s and every 100 samples on).
         */
        const val CALL_TRIES = 2_000L
        const val MAX_TRIES = 400_000L
        const val EMPTY_TRIES = 20_000L
        /**
         * The calibration of the tiles' chances ([MapPredictor.calibrate]; tools/mappred sampler3.mjs CAL_MOD, calfit.mjs
         * KEEPZERO on this sampler's own run, 2000 samples and 120 batches' worth, of 148 5-puzzle and 120 2-4 puzzle
         * training maps at 2/4/6/9/12 rooms known): per [Cls] (big, 1x1, puzzle, trap, yellow, blood, fairy, rare) the
         * sharpness a, the offset b and the change d per 3 rooms known. The samples are too sure of themselves: a 0.81 to
         * 0.97 (the rares 1.03), most for the big rooms and the normal 1x1s. Held out, 147 5-puzzle maps (735 snapshots)
         * -1.0369 to -1.0350 (+0.0019, s.e. 0.0012 by map) and 100 2-4 puzzle maps +0.0007 (s.e. 0.0011); at sampler3's
         * 300 samples (CAL, a 0.75 to 0.95) +0.0062 and +0.0034.
         */
        val CALIBRATION = arrayOf(
            doubleArrayOf(0.8123, 0.8378, 0.8816, 0.9570, 0.9706, 0.9312, 0.9158, 1.0328),
            doubleArrayOf(0.0656, -0.1019, -0.0325, 0.0128, 0.0441, 0.0454, 0.0341, -0.0677),
            doubleArrayOf(-0.0236, 0.0047, 0.0020, -0.0030, -0.0076, 0.0283, 0.0005, -0.0014),
        )

        /** The most dead ends besides blood on a 5-puzzle map (9 in 7 of 294 recorded maps, 10 in 2). */
        const val MAX_DEAD_5 = 10
        /** The wither path's walk (9 or 10 steps, entrance to blood) and the fairy's steps before blood. */
        const val WALK = 9
        const val FAIRY_BEFORE = MapStats.FAIRY_BEFORE_BLOOD

        // the specials' order in the shuffle, and their classes
        private const val T = 0
        private const val Y = 1
        private const val P = 2
        private const val R = 3
        private val CLS = intArrayOf(Cls.TRAP, Cls.YELLOW, Cls.PUZZLE, Cls.RARE)

        private fun fact(k: Int): Double { var r = 1.0; for (i in 2..k) r *= i; return r }

        /**
         * The weight to draw [GameStats.MIXES] row [i] by on a map that shows [knownBig] big rooms of each shape: maps /
         * success rate, times c!/(c-k)! for c rooms of a shape with k on the map (the ways its rooms can be the ones
         * shown); 0 when the map shows more of a shape than the mix has.
         */
        internal fun mixWeight(i: Int, knownBig: IntArray): Double {
            val c = GameStats.MIXES[i]
            var mult = 1.0
            for (s in 0 until 5) for (j in 0 until knownBig[s]) mult *= (c[s] - j).toDouble()
            return if (mult > 0) c[5] / GameStats.MIX_RATE[i] * mult else 0.0
        }
    }
}
