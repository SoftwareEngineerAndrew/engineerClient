package com.engineerclient.mappred

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The map prediction worker's work, as an object the game's worker thread drives and the headless tests can too
 * ([OdinMapPrediction] holds one; no game classes in here). It lives across the map changes of one dungeon:
 *
 *  - [begin] a view (a map the game shows): the samples drawn for earlier views are not thrown away. Every view the
 *    map shows later is the one before plus facts, and the facts are a function of the true map, so a sample (a whole
 *    map: its rooms and door tree) drawn for the earlier view that agrees with the new one is a sample of the new
 *    view's maps too, at the weight it had ([GameSampler.reuse]; [MapView.refines] says when that holds). Those
 *    samples are the prediction for the new view at once, however the map opened up (every room it could have opened
 *    is a share of the samples), and fresh samples for the new view then top them up. A view that isn't the one before
 *    plus facts (a misread corrected, another dungeon) starts a fresh pool.
 *  - [step]: a round of work, the last one's results in [prediction]: first until the prediction is as good as a fresh
 *    run's ([GameSampler.MIN_SAMPLES] samples and [GameSampler.MIN_BATCHES] batches' worth), then, with [ahead], on
 *    drawing more for the same view until the pool is full ([Config.cap] samples), which is what a reveal keeps a share of.
 *  - [threads] samplers ("lanes") of one view side by side, each with its own random numbers and tally.
 *  - [cancel]: stop at the next batch (a new view is coming).
 *
 * Samples reused from several views are on different scales (each view's weights are its own: the mix weights' total and
 * the like), so each earlier view's samples ([Group]) are a part of their own, a self-normalised estimate; the
 * prediction is the parts' mean weighted by what each is worth in fresh batches ([worth]). A batch keeps its identity
 * when its samples are reused (a batch's samples grew from a few ancestors, so the survivors of it carry no more than
 * the batch's weight), and a part is worth no more fresh batches than it has samples to match a fresh batch's, so the
 * count stays honest. The classic sampler's path (the other floors, a map the game process can't fit, or the Prediction
 * Model setting) is the worker's loop as it was.
 */
internal class PredictionSession(
    /** Samplers at once for the game process (the caller's worker thread is one of them; the rest are helper threads of the lowest priority). */
    var threads: Int = 1,
    /** Keep drawing for the same view after the prediction is done, until the pool is full ([Config.cap]). */
    var ahead: Boolean = true,
    /** The random numbers' seed; with one thread the same calls give the same samples. */
    val seed: Long = System.nanoTime(),
    val config: Config = Config(),
) {
    class Config(
        /** Carry samples from view to view (false: every view starts from nothing, as before). */
        val pool: Boolean = true,
        /** Most samples kept for a reveal (about [BYTES_PER_SAMPLE] bytes each). */
        val cap: Int = 40_000,
        /** Particles all lanes may start for a view in all while drawing ahead of the target (a hard map would go on for ever). */
        val aheadTries: Long = 1_500_000L,
        /** Particles a lane starts in one [step]. */
        val roundTries: Int = 600,
        /** Samples and batches' worth the prediction is done at ([GameSampler.MIN_SAMPLES], [GameSampler.MIN_BATCHES]). */
        val samples: Int = GameSampler.MIN_SAMPLES,
        val batches: Double = GameSampler.MIN_BATCHES,
        /** A reused part counts for its batches' worth, but for no more than its samples are worth next to a fresh batch's (false: its batches' worth). */
        val byInfo: Boolean = true,
        /**
         * Reused samples count for this much of what they are worth, for the mean and for being done. Half: a reused batch's
         * worth is an upper bound (its samples are fewer and from further back than a fresh batch's), and at 1 the view's
         * prediction once done was a little behind a fresh run's on 2-3 rooms (-0.0014 a tile, s.e. 0.0005; at 0.5 -0.0010
         * s.e. 0.0003) for the same gain over the dwell (+0.0127).
         */
        val reusedWeight: Double = 0.5,
        /** Batches' worth the new view's own samples must have before it is done, whatever the pool brought. */
        val freshBatches: Double = 0.0,
        /** Samples a prediction has to rest on to be shown at all (with the batches' worth [GameSampler.MIN_SHOW]). */
        val showSamples: Int = 0,
        /** Batches' worth a prediction has to be worth to be shown before it is done (with [showSamples]). */
        val showBatches: Double = GameSampler.MIN_SHOW,
    )

    /**
     * One earlier view's samples, whole, as they fit the current view ([key]: the structure they were drawn for, the law
     * and scale they share). [part] is what they add up to under the view they were last finished for, [sig] that view's
     * [GameSampler.finishSignature]; with [anyNames] (some finish weighed the secret total's names) and the fewest
     * [minRooms] of a sample, the next view can tell whether it would finish them the same, and keep the part as it is.
     */
    private class Group(val key: String, var stored: ArrayList<Stored>) {
        var part: Part? = null
        var sig = ""
        var anyNames = false
        var minRooms = Int.MAX_VALUE
    }

    /** What a [begin] made of the pool, for the harness: how many stored samples fit the new view, and what the newest group (the view before's own samples) kept. */
    class Reveal(
        /** The pool was carried over (false: the view isn't one the earlier plus facts, or pooling is off). */
        val carried: Boolean,
        val groups: Int,
        /** Samples stored in all groups before, and those that fit the new view. */
        val seen: Int,
        val fit: Int,
        /** The newest group: its stored samples, those that fit, the share of its weight that did, and its batches' worth before and after. */
        val lastSeen: Int,
        val lastFit: Int,
        val lastShare: Double,
        val lastBatchesBefore: Double,
        val lastBatchesAfter: Double,
        /** What all the groups are worth in fresh batches, and their samples, right after. */
        val batches: Double,
        val kept: Int,
        /** CPU time of the import, nanoseconds (this thread). */
        val nanos: Long,
    )

    /** A pool put by: the groups that fitted [view] (and whether the secret total's names weighed them), for a view that refines it. */
    private class Stash(val view: MapView, val groups: ArrayList<Group>, val secrets: Boolean)

    private enum class Mode { IDLE, GAME, CLASSIC }

    private var mode = Mode.IDLE
    private var view: MapView? = null
    private var viewKey = ""
    private var viewSig = ""
    private var viewSecrets = false
    private var viewSeq = 0
    private var srcSeq = 0
    private var lanes: Array<GameSampler> = emptyArray()
    /** Each lane's random numbers (a test puts its own in). */
    internal var rnds: Array<Random> = emptyArray()
    private val groups = ArrayList<Group>()
    /** The pool of the view before a view that did not build on it, kept for the view after that. */
    private var stash: Stash? = null
    /** The newest group: the view before's own samples. */
    private var latest: Group? = null
    private var reused = ArrayList<Part>()
    private var done = false
    private var emptyFrom = 0L
    /** Samples' worth of a fresh batch in the view before's own samples (what a reused batch is measured against until this view has its own). */
    private var rho = RHO0
    private var predictor: MapPredictor? = null
    private var classicRnd: Random = Random(seed)
    private var classicDone = false
    @Volatile private var cancelled = false
    private var helpers: ExecutorService? = null
    private var helperCount = 0

    /** What the last [begin] did with the pool. */
    var reveal: Reveal? = null; private set

    /** The game-process sampler was dropped for the classic one for this view (it threw, or ended with too little). */
    var fellBack = false; private set
    var error: Exception? = null; private set

    /** True while the samples come from the game's own steps ([GameSampler]); false for the classic sampler, or before [begin]. */
    val usesGameProcess: Boolean get() = mode == Mode.GAME

    /** The view's samplers are done with what the target asks (the prediction is as good as a fresh run's). */
    val isDone: Boolean get() = if (mode == Mode.GAME) done else classicDone

    /** Particles started for the current view so far, over all lanes. */
    val tries: Long get() = lanes.sumOf { it.tries }

    /** Samples kept in all, reused and fresh. */
    val kept: Int get() = reused.sumOf { it.kept } + lanes.sumOf { it.kept }

    /** Samples stored for the next reveal. */
    val poolSize: Int get() = groups.sumOf { it.stored.size } + lanes.sumOf { it.store?.size ?: 0 }

    /** What the reused parts and the new view's own samples are worth together, in fresh batches. */
    val batches: Double get() = weights(freshCounts()).sum()

    fun cancel() { cancelled = true }

    /** Throws the pool away (a new dungeon); the helper threads stay. */
    fun clear() {
        groups.clear(); stash = null; reused = ArrayList(); lanes = emptyArray(); view = null; predictor = null; mode = Mode.IDLE; latest = null
    }

    fun close() { helpers?.shutdownNow(); helpers = null; helperCount = 0; clear() }

    /**
     * Starts [v] as the map to predict: the pool of the views before is carried over when [v] says all they said and
     * more, else dropped. [game]: F7's game process where the floor has it (else, or when it can't fit the map, the
     * classic sampler). What the pool gives is [prediction] at once, when it has enough for one.
     */
    fun begin(v: MapView, game: Boolean = v.rules.game) {
        cancelled = false
        val before = view
        val beforeKey = viewKey
        // the view before's own samples join the pool (kept whole, in the lanes' stores)
        latest = null
        if (mode == Mode.GAME) closeLanes(beforeKey)
        lanes = emptyArray(); reused = ArrayList(); predictor = null
        view = v; viewKey = v.structureKey(); fellBack = false; error = null; done = false; emptyFrom = 0L; classicDone = false
        viewSeq++
        val mp = try { MapPredictor(v, gameProcess = game) } catch (e: Exception) { error = e; null }
        val g0 = mp?.game
        if (mp == null || g0 == null) {
            groups.clear(); stash = null; reveal = null
            mode = Mode.CLASSIC; predictor = mp ?: MapPredictor(v, gameProcess = false)
            classicRnd = Random(seed + viewSeq * 7_919L)
            return
        }
        mode = Mode.GAME
        var carried = config.pool && before != null && v.refines(before) && !(viewSecrets && !mp.secretsActive)
        if (!config.pool) groups.clear()
        else {
            // A view that isn't the one before plus facts costs the pool, unless the next view undoes it: a glitch (a room gone
            // for a tick, a tab list mid-update) would otherwise cost all of it. So the pool is put by, once, for a view
            // that says all the view it fitted did (and added to the pool of the glitch, if that view builds on it too).
            val s = stash
            val back = s != null && v.refines(s.view) && !(s.secrets && !mp.secretsActive)
            if (carried) { if (back) { groups.addAll(0, s!!.groups); stash = null } }
            else if (back) { groups.clear(); groups.addAll(s!!.groups); stash = null; carried = true }
            else { stash = if (before != null && groups.isNotEmpty()) Stash(before, ArrayList(groups), viewSecrets) else stash; groups.clear() }
        }
        viewSecrets = mp.secretsActive
        val n = threads.coerceIn(1, MAX_THREADS)
        lanes = Array(n) { if (it == 0) g0 else mp.newGameLane()!! }
        rnds = Array(n) { Random(seed xor (viewSeq.toLong() shl 20) xor (it.toLong() shl 44)) }
        for (l in lanes) { l.store = if (config.pool) ArrayList() else null; l.srcId = ++srcSeq }
        if (n > 1) ensureHelpers(n - 1)
        reveal = importGroups(carried, latest)
        evaluate()
    }

    /** The lanes of the view before into a group of its own (or the one with the same structure: the same law, the same scale). */
    private fun closeLanes(key: String) {
        latest = null
        val part = fresh()
        if (part.batches >= RHO_BATCHES && part.samples > 0) rho = maxOf(1.0, part.samples / part.batches)
        viewSig = lanes.firstOrNull()?.finishSignature ?: viewSig
        if (!config.pool) return
        val st = ArrayList<Stored>()
        for (l in lanes) { l.store?.let { st += it }; l.store = null }
        if (st.isEmpty()) return
        var anyNames = false; var minRooms = Int.MAX_VALUE
        for (s in st) { if (s.names) anyNames = true; if (s.whole.shape.size < minRooms) minRooms = s.whole.shape.size }
        val g = groups.firstOrNull { it.key == key }
        if (g == null) {
            latest = Group(key, st).also { it.part = part; it.sig = viewSig; it.anyNames = anyNames; it.minRooms = minRooms }
            groups += latest!!
        } else {
            // the same law at the same scale: the parts add if they were worked out for the same finish
            val old = g.part
            if (old != null && g.sig == viewSig) {
                val sum = Tally(old.tally.counts.size, old.tally.splitRooms.size)
                sum.addScaled(old.tally, 1.0); sum.addScaled(part.tally, 1.0)
                g.part = Part(sum, old.kept + part.kept, old.w1 + part.w1, old.w2 + part.w2, old.s2 + part.s2)
            } else g.part = null
            g.stored.addAll(st); g.anyNames = g.anyNames || anyNames; g.minRooms = minOf(g.minRooms, minRooms)
            latest = g
        }
    }

    /** A group's samples that fit the new view, finished for it, the work shared by the lanes (cut at batch boundaries). */
    private fun reuseAll(stored: List<Stored>): GameSampler.Reused {
        val n = lanes.size
        if (n == 1 || stored.size < PARALLEL_IMPORT) return lanes[0].reuse(stored)
        val cuts = IntArray(n + 1); cuts[n] = stored.size
        for (i in 1 until n) {
            var c = maxOf(cuts[i - 1], stored.size * i / n)
            while (c in 1 until stored.size && stored[c].src == stored[c - 1].src && stored[c].batch == stored[c - 1].batch) c++
            cuts[i] = c
        }
        val futures = (1 until n).map { i -> helpers!!.submit(Callable { lanes[i].reuse(stored.subList(cuts[i], cuts[i + 1])) }) }
        val first = lanes[0].reuse(stored.subList(cuts[0], cuts[1]))
        val survivors = first.survivors
        val sum = first.part.tally
        var kept = first.part.kept; var w1 = first.part.w1; var w2 = first.part.w2; var s2 = first.part.s2
        var anyNames = first.anyNames; var minRooms = first.minRooms
        for (f in futures) {
            val r = try { f.get() } catch (e: ExecutionException) { throw (e.cause as? Exception ?: e) }
            survivors.addAll(r.survivors); sum.addScaled(r.part.tally, 1.0)
            kept += r.part.kept; w1 += r.part.w1; w2 += r.part.w2; s2 += r.part.s2
            anyNames = anyNames || r.anyNames; minRooms = minOf(minRooms, r.minRooms)
        }
        return GameSampler.Reused(survivors, Part(sum, kept, w1, w2, s2), anyNames, minRooms)
    }

    /** Every group's samples that fit the new view, finished for it: the reused parts. */
    private fun importGroups(carried: Boolean, newest: Group?): Reveal {
        val t0 = threadCpuNanos()
        val g = lanes[0]
        val sig = g.finishSignature
        viewSig = sig
        val namesUpTo = g.namesUpTo
        var seen = 0; var fit = 0
        var lastSeen = 0; var lastFit = 0; var lastShare = 0.0; var lastB0 = 0.0; var lastB1 = 0.0
        val it = groups.iterator()
        while (it.hasNext()) {
            val grp = it.next()
            val before = grp.stored.size
            val old = grp.part
            val r: GameSampler.Reused
            if (old != null && grp.sig == sig && !grp.anyNames && grp.minRooms > namesUpTo) {
                // nothing the finish reads has changed (a name, a secret total that no sample's finish weighs): the part is as it was
                r = GameSampler.Reused(grp.stored, old, false, grp.minRooms)
            } else {
                r = reuseAll(grp.stored)
                grp.part = r.part; grp.sig = sig; grp.anyNames = r.anyNames; grp.minRooms = r.minRooms
            }
            seen += before; fit += r.survivors.size
            if (grp === newest) {
                lastSeen = before; lastFit = r.survivors.size
                lastShare = if (old != null && old.tally.weight > 0) r.part.tally.weight / old.tally.weight else 1.0
                lastB0 = old?.batches ?: 0.0; lastB1 = r.part.batches
            }
            if (r.survivors.isEmpty()) { it.remove(); continue }
            grp.stored = r.survivors
            reused += r.part
        }
        trim()
        return Reveal(carried, groups.size, seen, fit, lastSeen, lastFit, lastShare, lastB0, lastB1, batches, kept, threadCpuNanos() - t0)
    }

    /** The pool back down to [Config.cap] less room for the new view's own samples: the oldest views' first batches go. */
    private fun trim() {
        val room = (config.cap * OLD_SHARE).toInt()
        var extra = groups.sumOf { it.stored.size } - room
        var gi = 0
        while (extra > 0 && gi < groups.size) {
            val grp = groups[gi]
            val st = grp.stored
            // whole batches from the front (a batch's samples are together)
            var k = 0
            while (k < st.size && extra > 0) {
                val s = st[k]
                var j = k
                while (j < st.size && st[j].src == s.src && st[j].batch == s.batch) j++
                extra -= j - k; k = j
            }
            if (k >= st.size) { groups.removeAt(gi); continue }
            if (k > 0) { grp.stored = ArrayList(st.subList(k, st.size)); grp.part = null }
            gi++
        }
    }

    private fun ensureHelpers(n: Int) {
        if (helperCount == n && helpers != null) return
        helpers?.shutdownNow()
        helpers = Executors.newFixedThreadPool(n) { r -> Thread(r, "engineerclient-map-prediction-lane").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        helperCount = n
    }

    /** The lanes' own samples as one part (the lanes draw from one law at one scale, so their tallies add). */
    private fun fresh(): Part {
        if (lanes.isEmpty()) return Part(Tally(0, 0), 0, 0.0, 0.0)
        if (lanes.size == 1) return lanes[0].part()
        val parts = lanes.map { it.part() }
        val sum = Tally(lanes[0].tally.counts.size, lanes[0].tally.splitRooms.size)
        for (p in parts) sum.addScaled(p.tally, 1.0)
        return Part(sum, parts.sumOf { it.kept }, parts.sumOf { it.w1 }, parts.sumOf { it.w2 }, parts.sumOf { it.s2 })
    }

    /** What a set of samples counts for without their tally: how many, their batches' weights and their own ([Part]'s fields). */
    private class Counts(val kept: Int, val w1: Double, val w2: Double, val s1: Double, val s2: Double) {
        val batches: Double get() = if (w2 > 0) w1 * w1 / w2 else 0.0
        val samples: Double get() = if (s2 > 0) s1 * s1 / s2 else 0.0
    }

    /** The lanes' counts, summed (no tallies merged): for the stop rule after every batch. */
    private fun freshCounts(): Counts {
        var k = 0; var w1 = 0.0; var w2 = 0.0; var s1 = 0.0; var s2 = 0.0
        for (l in lanes) { val p = l.part(); k += p.kept; w1 += p.w1; w2 += p.w2; s1 += p.tally.weight; s2 += p.s2 }
        return Counts(k, w1, w2, s1, s2)
    }

    /**
     * What a reused part is worth in fresh batches: its batches' worth, but for no more than its samples are worth
     * next to a fresh batch's ([rho] samples' worth each: a batch whose samples are mostly gone is a poor batch).
     */
    private fun worth(p: Part, rho: Double): Double =
        (if (config.byInfo) minOf(p.batches, p.samples / rho) else p.batches) * config.reusedWeight

    /** The weights of the parts, the reused ones first and the new view's own last: what each is worth in fresh batches. */
    private fun weights(fresh: Counts): DoubleArray {
        val r = if (fresh.batches >= RHO_BATCHES && fresh.samples > 0) maxOf(1.0, fresh.samples / fresh.batches) else rho
        return DoubleArray(reused.size + 1) { if (it < reused.size) worth(reused[it], r) else fresh.batches }
    }

    /** True when the prediction is as good as a fresh run's: the stop rule of [GameSampler.run] over the parts together. */
    private fun reached(): Boolean {
        val f = freshCounts()
        val b = weights(f).sum()
        val k = f.kept + reused.sumOf { it.kept }
        return k >= config.samples && b >= config.batches && f.batches >= config.freshBatches
    }

    /** After a round: done when the target is reached or the particles are used up; the secret total off or the classic sampler when nothing fits. */
    private fun evaluate() {
        if (mode != Mode.GAME || done) return
        val k = kept
        val started = tries
        if (reached()) done = true
        else if (started >= lanes[0].triesCap) done = true
        else if (k == 0 && started - emptyFrom >= GameSampler.EMPTY_TRIES) {
            if (lanes.any { it.namesWereTried } && lanes[0].secretsOn) { for (l in lanes) l.dropNames(); emptyFrom = started } else done = true
        }
        if (done && batches < GameSampler.MIN_SHOW) fallBack(null)
    }

    /** The game process ended with too little (or threw): the classic sampler takes this view, and the pool goes. */
    private fun fallBack(e: Exception?) {
        val v = view ?: return
        error = e
        groups.clear(); stash = null; reused = ArrayList(); lanes = emptyArray(); fellBack = true; latest = null
        mode = Mode.CLASSIC; predictor = MapPredictor(v, gameProcess = false)
        classicRnd = Random(seed + viewSeq * 7_919L)
        classicDone = false
    }

    /**
     * A round of work: false when there is none left (the view is done and, with [ahead], the pool full). Whatever
     * it did is in [prediction] after it. [tries]: particles a lane starts in the round.
     */
    fun step(tries: Int = config.roundTries): Boolean {
        if (cancelled) return false
        return when (mode) {
            Mode.GAME -> gameStep(tries)
            Mode.CLASSIC -> classicStep()
            Mode.IDLE -> false
        }
    }

    private fun gameStep(budget: Int): Boolean {
        if (done && !(ahead && poolSize < config.cap && tries < config.aheadTries)) return false
        try { runLanes(budget) } catch (e: Exception) { fallBack(unwrap(e)); return true }
        // a hard map keeps few of its particles for long: the pool is for the next room, it doesn't grow without bound meanwhile
        if (poolSize > 2L * config.cap) for (l in lanes) l.storeLimit = l.store?.size ?: 0
        evaluate()
        return true
    }

    private fun unwrap(e: Exception): Exception = (e as? ExecutionException)?.cause as? Exception ?: e

    private fun runLanes(budget: Int) {
        val stop = { cancelled }
        if (lanes.size == 1) {
            lanes[0].runBatches(budget, rnds[0]) { cancelled || !done && reached() }
            return
        }
        val pool = helpers!!
        val futures = (1 until lanes.size).map { i -> pool.submit(Callable { lanes[i].runBatches(budget, rnds[i], stop) }) }
        var failure: Exception? = null
        try { lanes[0].runBatches(budget, rnds[0], stop) } catch (e: Exception) { failure = e }
        for (f in futures) try { f.get() } catch (e: Exception) { if (failure == null) failure = e }
        failure?.let { throw it }
    }

    /** The worker's loop of old, a chunk of [BATCH] samples a step until [SAMPLES] or nothing more to get. */
    private fun classicStep(): Boolean {
        val p = predictor ?: return false
        if (classicDone || p.accepted >= SAMPLES) { classicDone = true; return false }
        val k = p.run(BATCH, 40_000, classicRnd)
        if (k == 0 && (p.accepted > 0 || p.level > 2)) classicDone = true
        return true
    }

    /**
     * What the samples so far agree on; null while there are too few to show (right after a door opens the first
     * batch's samples come from few ancestors and would show rooms as near certain: the caller keeps the last
     * prediction up meanwhile).
     */
    fun prediction(): Prediction? = when (mode) {
        Mode.GAME -> gamePrediction()
        Mode.CLASSIC -> try { predictor?.prediction() } catch (e: Exception) { error = e; null }
        Mode.IDLE -> null
    }

    private fun gamePrediction(): Prediction? {
        val g = lanes.firstOrNull() ?: return null
        val parts = reused + fresh()
        val w = weights(freshCounts())
        val samples = parts.sumOf { it.kept }
        if (samples == 0 || !done && (w.sum() < config.showBatches || samples < config.showSamples)) return null
        return try { g.predictionOf(parts, w) } catch (e: Exception) { error = e; fallBack(e); null }
    }

    companion object {
        const val MAX_THREADS = 4
        /** The pool keeps this share of the cap for the old views' survivors at the start of a view; the rest is room for the new view's own samples. */
        const val OLD_SHARE = 0.75
        /** About what a stored sample takes (its tree, its share of a layout, its record). */
        const val BYTES_PER_SAMPLE = 110
        /** Samples' worth in a fresh batch before any has been seen (what a batch of the view before measured is used from then on), and batches a measure needs. */
        const val RHO0 = 20.0
        const val RHO_BATCHES = 5.0
        /** A group this big is imported by several lanes at once. */
        const val PARALLEL_IMPORT = 3_000
        /** The classic sampler's worker loop: samples a chunk, and samples in all ([OdinMapPrediction]'s SAMPLES, BATCH). */
        const val BATCH = 100
        const val SAMPLES = 2000

        /** Half the cores, at least 1 and at most 4. */
        fun autoThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, MAX_THREADS)

        private fun threadCpuNanos(): Long {
            val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            return if (bean.isCurrentThreadCpuTimeSupported) bean.currentThreadCpuTime else System.nanoTime()
        }
    }
}
