package com.engineerclient.mappred

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.ln
import kotlin.math.max
import kotlin.test.Test

/**
 * Optional: the map prediction as a player gets it, map by map and room by room (ADS_MAP_SEQUENCE=<seq.txt from
 * tools/mappred export-seq.mjs>; skipped when the variable isn't set). Each map's views are played in the order the
 * rooms showed, through one [PredictionSession] per map as the worker drives it: a view begins, the prediction there
 * is taken at once ("0" particles), then the session works and the prediction shown is taken again after each budget
 * point (particles started in the view), and when the dwell is over the next room opens. What is shown is what the
 * worker would have up: the prediction of the session, else (nothing to show yet) the one before. Every point of
 * every view is scored on the view's unknown tiles against the map's truth (the same per-tile log-likelihood as
 * tools/mappred score-kt.mjs: the chance of the true class, at least 1e-4, 1/9 when nothing is shown).
 *
 * Variants (ADS_SEQ_VARIANTS, comma separated, default "nopool,pool"; one run each, all maps) are "name" or
 * "name:key=value:..." with: pool=0|1 (carry samples from view to view), threads, cap, ahead=0|1 (keep drawing after
 * the prediction is done), info=0|1 (a reused part counts for no more than its samples are worth), rw (reused weight),
 * fresh (batches' worth of the view's own samples before it is done), show (samples) and showb (batches' worth) to show
 * a prediction, round (particles a lane starts in a step), chunk (stored samples a lane carries into the view a step).
 * "nopool" is the worker as it was (pool=0, ahead=0).
 *
 * Other variables: ADS_SEQ_POINTS (particles started in the view, default 0,2000,10000,40000), ADS_SEQ_DWELL (particles
 * spent in each view before the next opens: a number, "full" = until the prediction is done, or "real" = the real
 * seconds between rooms (seq.txt made with ORDER=real) times ADS_SEQ_RATE particles a second, default 20000; default
 * 40000), ADS_SEQ_AHEAD (extra particles after "full", default 0), ADS_SEQ_MAPS (first n maps), ADS_SEQ_SKIP (skip the
 * first n), ADS_SEQ_SEED, ADS_SEQ_OUT (prefix of the result files, default the seq.txt path), ADS_SEQ_CPU (CPU points:
 * ms of the calling thread since the view began, its import too, recorded as "c<ms>": what was up then, the prediction
 * published at the last look before it, after begin or a step; one lane, a small round for a fine clock). Writes
 * <prefix>.<variant>.pts.tsv (a row per view and point; the last point, "end", is the dwell's end) and .views.tsv (a row
 * per view: what the pool did at the reveal, and the CPU time), which tools/mappred score-seq.mjs reads.
 */
class MapSequenceCheck {
    private class SeqView(val k: Int, val sec: Double, val line: String)
    private class SeqMap(val id: String, val truth: IntArray, val views: List<SeqView>)

    private fun parse(path: String): List<SeqMap> {
        val out = ArrayList<SeqMap>()
        var id = ""; var truth = IntArray(0); var views = ArrayList<SeqView>()
        fun flush() { if (views.isNotEmpty()) out += SeqMap(id, truth, views) }
        for (line in File(path).readLines()) {
            if (line.isBlank()) continue
            if (line.startsWith("map ")) {
                flush()
                val p = line.split(' ')
                id = p[1]; views = ArrayList()
                // n o P T Y B F R are Cls 0..7 in this order (BIG NORMAL PUZZLE TRAP YELLOW BLOOD FAIRY RARE); E and x are -1
                truth = IntArray(36) { "noPTYBFR".indexOf(p[4][it]) }
            } else if (line.startsWith("view ")) {
                val bar = line.indexOf('|')
                val parts = line.substring(5, bar).split(' ')
                views += SeqView(parts[0].toInt(), parts[1].toDouble(), parts.drop(2).joinToString(" ") + line.substring(bar))
            }
        }
        flush()
        return out
    }

    /** The line "w h puzzles floor|rooms|edges" as the game's view: the map as Odin shows it (rooms cut off from the entrance left out). */
    private fun build(line: String): MapView {
        val (head, rooms, edges) = line.split('|')
        val nums = head.trim().split(' ').map { it.toInt() }
        val v = MapView(nums[0], nums[1], nums[2], FloorRules.of(nums.getOrElse(3) { 7 }) ?: FloorRules.F7)
        for (r in rooms.split(';').filter { it.isNotEmpty() }) {
            val (k, t) = r.split(':')
            v.addRoom(Kind.valueOf(k), t.split(',').map { it.toInt() }.toIntArray())
        }
        for (e in edges.split(';').filter { it.isNotEmpty() }) {
            val (a, b, k) = e.split(',').map { it.toInt() }
            v.setEdge(a, b, k.toByte())
        }
        return v.connectedPart()
    }

    private class Variant(spec: String) {
        val name: String
        private val kv: Map<String, String>
        init {
            val p = spec.split(':')
            name = p[0]
            kv = p.drop(1).associate { it.substringBefore('=') to it.substringAfter('=', "1") }
        }
        val pool = (kv["pool"] ?: if (name == "nopool") "0" else "1") == "1"
        val ahead = (kv["ahead"] ?: if (name == "nopool") "0" else "1") == "1"
        val threads = (kv["threads"] ?: "1").toInt()
        val config = PredictionSession.Config(
            pool = pool,
            cap = (kv["cap"] ?: "40000").toInt(),
            roundTries = (kv["round"] ?: "600").toInt(),
            byInfo = (kv["info"] ?: "1") == "1",
            reusedWeight = (kv["rw"] ?: "0.5").toDouble(),
            freshBatches = (kv["fresh"] ?: "0").toDouble(),
            showSamples = (kv["show"] ?: "0").toInt(),
            showBatches = (kv["showb"] ?: GameSampler.MIN_SHOW.toString()).toDouble(),
            importChunk = (kv["chunk"] ?: "3000").toInt(),
        )
    }

    private fun cpu(): Long = ManagementFactory.getThreadMXBean().currentThreadCpuTime

    /** The sum over the view's unknown tiles of the log chance of the true class, and how many tiles. */
    private fun score(v: MapView, p: Prediction?, truth: IntArray): Pair<Double, Int> {
        var ll = 0.0; var n = 0
        for (t in 0 until 36) {
            if (v.roomAt[t] >= 0) continue
            val c = truth[t]; if (c < 0) continue
            val pr = p?.probs?.get(t)
            val prob = if (pr == null) 1.0 / 9 else pr[c].toDouble()
            ll += ln(max(prob, 1e-4)); n++
        }
        return ll to n
    }

    @Test fun playSequences() {
        val path = System.getenv("ADS_MAP_SEQUENCE")
        assumeTrue(path != null && File(path).isFile)
        val maps = parse(path!!).drop(System.getenv("ADS_SEQ_SKIP")?.toInt() ?: 0).let { m -> System.getenv("ADS_SEQ_MAPS")?.toInt()?.let { m.take(it) } ?: m }
        val variants = (System.getenv("ADS_SEQ_VARIANTS") ?: "nopool,pool").split(',').filter { it.isNotBlank() }.map { Variant(it.trim()) }
        val points = (System.getenv("ADS_SEQ_POINTS") ?: "0,2000,10000,40000").split(',').map { it.trim().toInt() }.sorted()
        val cpuPoints = (System.getenv("ADS_SEQ_CPU") ?: "").split(',').filter { it.isNotBlank() }.map { it.trim().toInt() }.sorted()
        val dwellSpec = System.getenv("ADS_SEQ_DWELL") ?: "40000"
        val extra = (System.getenv("ADS_SEQ_AHEAD") ?: "0").toLong()
        val rate = (System.getenv("ADS_SEQ_RATE") ?: "20000").toDouble()
        val seed = (System.getenv("ADS_SEQ_SEED") ?: "5").toLong()
        val prefix = System.getenv("ADS_SEQ_OUT") ?: path
        for (variant in variants) {
            val pts = StringBuilder("map\tk\tpoint\tll\ttiles\tsamples\tbatches\tshown\n")
            val vws = StringBuilder("map\tk\tcarried\tgroups\tseen\tfit\tlastSeen\tlastFit\tlastShare\tlastB0\tlastB1\tinstantBatches\tinstantKept\timportMs\tbeginMs\ttotalMs\ttries\tkept\tpool\tgame\tfell\tdone\tdwell\n")
            var totalCpu = 0L; var totalTries = 0L
            val lanes = variant.threads
            val round = variant.config.roundTries
            for ((mi, m) in maps.withIndex()) {
                val session = PredictionSession(threads = lanes, ahead = variant.ahead, seed = seed * 1_000_003L + mi, config = variant.config)
                var shown: Prediction? = null
                var lastKey = ""
                val views = m.views
                for ((vi, sv) in views.withIndex()) {
                    val v = build(sv.line)
                    val key = v.key()
                    if (key == lastKey) continue
                    lastKey = key
                    val dwell: Long = when (dwellSpec) {
                        "full" -> -1L
                        "real" -> (((views.getOrNull(vi + 1)?.sec ?: (sv.sec + 3.0)) - sv.sec).coerceAtLeast(0.5) * rate).toLong()
                        else -> dwellSpec.toLong()
                    }
                    val stale = shown
                    val c0 = cpu()
                    session.begin(v, game = true)
                    val c1 = cpu()
                    fun row(label: String, p: Prediction?, new: Boolean) {
                        val (ll, n) = score(v, p, m.truth)
                        pts.append("${m.id}\t${sv.k}\t$label\t${"%.5f".format(ll)}\t$n\t${session.kept}\t${"%.2f".format(session.batches)}\t${if (new) 1 else 0}\n")
                    }
                    fun record(label: String) {
                        val p = session.prediction()
                        if (p != null) shown = p
                        row(label, shown, p != null)
                    }
                    record("0")
                    val instantBatches = session.batches; val instantKept = session.kept
                    // the CPU points (ms of this thread since the view began, the import too): what is up at each is what was
                    // published at the last look before it (after begin, after each step), the view before's until then
                    var nextCpu = 0
                    var up = stale; var upNew = false
                    fun recordCpu() {
                        val ms = (cpu() - c0) / 1e6
                        while (nextCpu < cpuPoints.size && cpuPoints[nextCpu] < ms) { row("c" + cpuPoints[nextCpu], up, upNew); nextCpu++ }
                        val p = session.prediction()
                        if (p != null) { shown = p; upNew = true }
                        up = shown
                    }
                    recordCpu()
                    var next = 0
                    while (next < points.size && points[next] <= 0) next++
                    var running = true
                    var doneAt = -1L
                    while (running) {
                        val used = session.tries
                        if (dwell >= 0 && used >= dwell) break
                        if (dwell < 0) {
                            if (session.isDone && !session.importing && doneAt < 0) doneAt = used
                            if (doneAt >= 0 && used >= doneAt + extra) break
                        }
                        var want = (round * lanes).toLong()
                        if (next < points.size) want = minOf(want, maxOf(1L, points[next] - used))
                        if (dwell >= 0) want = minOf(want, dwell - used)
                        if (doneAt >= 0) want = minOf(want, doneAt + extra - used)
                        running = session.step(((want + lanes - 1) / lanes).toInt())
                        val now = session.tries
                        while (next < points.size && points[next] <= now) { record(points[next].toString()); next++ }
                        recordCpu()
                    }
                    // the points the view never reached (its work ended first) show what it ended with
                    while (next < points.size) { record(points[next].toString()); next++ }
                    while (nextCpu < cpuPoints.size) { row("c" + cpuPoints[nextCpu], up, upNew); nextCpu++ }
                    record("end")
                    val c2 = cpu()
                    val rev = session.reveal
                    totalCpu += c2 - c0; totalTries += session.tries
                    vws.append("${m.id}\t${sv.k}\t${if (rev?.carried == true) 1 else 0}\t${rev?.groups ?: 0}\t${rev?.seen ?: 0}\t${rev?.fit ?: 0}\t${rev?.lastSeen ?: 0}\t${rev?.lastFit ?: 0}\t")
                    vws.append("${"%.4f".format(rev?.lastShare ?: 0.0)}\t${"%.2f".format(rev?.lastBatchesBefore ?: 0.0)}\t${"%.2f".format(rev?.lastBatchesAfter ?: 0.0)}\t${"%.2f".format(instantBatches)}\t$instantKept\t")
                    vws.append("${"%.1f".format((rev?.nanos ?: 0) / 1e6)}\t${"%.1f".format((c1 - c0) / 1e6)}\t${"%.1f".format((c2 - c0) / 1e6)}\t${session.tries}\t${session.kept}\t${session.poolSize}\t${if (session.usesGameProcess) 1 else 0}\t${if (session.fellBack) 1 else 0}\t${if (session.isDone) 1 else 0}\t$dwell\n")
                }
                session.close()
            }
            File("$prefix.${variant.name}.pts.tsv").writeText(pts.toString())
            File("$prefix.${variant.name}.views.tsv").writeText(vws.toString())
            println("sequence ${variant.name}: ${maps.size} maps, ${totalCpu / 1_000_000} ms cpu, $totalTries particles")
        }
    }
}
