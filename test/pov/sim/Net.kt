package com.engineerclient.pov.sim

import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One way of a network path, as a model of what the internet does to packets: a base one-way delay, jitter that wanders
 * (correlated over [jitterTauMs], as queues are) plus per-packet noise and rare spikes, random and bursty loss
 * (Gilbert-Elliott), reordering, duplicates, congestion that builds and drains (bufferbloat), stalls that hold packets and
 * release them together (Wi-Fi power save, a busy router), outages that drop everything, and route changes. [tcp]: the
 * relay's WebSocket, which never loses or reorders but makes everything behind a lost packet wait for its resend.
 * Times in ms of simulated time.
 */
data class NetProfile(
    val name: String,
    val baseMs: Double,
    val jitterMs: Double = 0.0,
    val jitterTauMs: Double = 200.0,
    val noiseMs: Double = 0.0,
    val spikeP: Double = 0.0,
    val spikeMs: Double = 0.0,
    val loss: Double = 0.0,
    /** Loss bursts: about every [badEveryS] for [badMs], packets are lost at [badLoss] (a fading signal, a busy cell). */
    val badEveryS: Double = 0.0,
    val badMs: Double = 0.0,
    val badLoss: Double = 0.0,
    val reorderP: Double = 0.0,
    val reorderMs: Double = 0.0,
    val dupP: Double = 0.0,
    /** Congestion: every [bloatEveryS] (on average) for [bloatForS], delay ramps up to [bloatMs] and back. */
    val bloatEveryS: Double = 0.0,
    val bloatForS: Double = 0.0,
    val bloatMs: Double = 0.0,
    /** Stalls: every [stallEveryS] (on average), packets are held for [stallMs] then released together. */
    val stallEveryS: Double = 0.0,
    val stallMs: Double = 0.0,
    /** Everything lost in these windows (start ms, length ms). */
    val outages: List<Pair<Long, Long>> = emptyList(),
    /** The base delay changes to the second at the first (ms): a new route. */
    val steps: List<Pair<Long, Double>> = emptyList(),
    val tcp: Boolean = false,
) {
    /** The same path as the relay sees it: two legs through one Cloudflare location, over TCP (a WebSocket). */
    fun asRelay(extraMs: Double = 15.0) = copy(name = "$name (relay)", baseMs = baseMs * 1.25 + extraMs, reorderP = 0.0, dupP = 0.0,
        steps = steps.map { it.first to it.second * 1.25 + extraMs }, tcp = true)
}

/** A [NetProfile] running: [send] gives when a packet sent at [atMs] arrives (none when lost; two when duplicated). */
class Link(val p: NetProfile, seed: Long, private val durationMs: Long) {
    private val r = SplittableRandom(seed)
    private var ou = 0.0
    private var lastAt = -1.0
    private var lastArrival = 0.0
    private val bloats = windows(p.bloatEveryS, p.bloatForS * 1000)
    private val stalls = windows(p.stallEveryS, p.stallMs)
    private val bads = windows(p.badEveryS, p.badMs)
    var sent = 0L; private set
    var lost = 0L; private set

    /** Random windows (start, length) over the run: about one every [everyS]. */
    private fun windows(everyS: Double, lenMs: Double): List<Pair<Double, Double>> {
        if (everyS <= 0 || lenMs <= 0) return emptyList()
        val out = ArrayList<Pair<Double, Double>>()
        var t = expo(everyS * 1000)
        while (t < durationMs) { out += t to lenMs * (0.6 + 0.8 * r.nextDouble()); t += lenMs + expo(everyS * 1000) }
        return out
    }

    private fun expo(mean: Double) = -ln(1 - r.nextDouble()) * mean
    private fun gauss() = r.nextGaussian()

    fun base(atMs: Double): Double = p.steps.lastOrNull { it.first <= atMs }?.second ?: p.baseMs

    private fun bloat(atMs: Double): Double {
        for ((s, len) in bloats) if (atMs >= s && atMs < s + len) { val k = (atMs - s) / len; return p.bloatMs * (if (k < 0.7) k / 0.7 else (1 - k) / 0.3) }
        return 0.0
    }

    private fun outage(atMs: Double) = p.outages.any { (s, len) -> atMs >= s && atMs < s + len }

    /** Lost in the network (not counting outages). */
    private fun drops(atMs: Double): Boolean {
        val bad = bads.any { (s, len) -> atMs >= s && atMs < s + len }
        return r.nextDouble() < (if (bad) p.badLoss else p.loss)
    }

    /** The one-way delay a packet sent now meets (ms), before stalls. */
    private fun delay(atMs: Double): Double {
        if (p.jitterMs > 0) {
            // (A resend is timed later than packets sent after it: never a step back in time.)
            val dt = if (lastAt < 0) 1e9 else max(0.0, atMs - lastAt)
            val a = exp(-dt / p.jitterTauMs)
            ou = a * ou + sqrt(1 - a * a) * gauss()
        }
        lastAt = max(lastAt, atMs)
        var d = base(atMs) + p.jitterMs * kotlin.math.abs(ou) + bloat(atMs)
        if (p.noiseMs > 0) d += p.noiseMs * kotlin.math.abs(gauss())
        if (p.spikeP > 0 && r.nextDouble() < p.spikeP) d += expo(p.spikeMs)
        return max(0.05, d)
    }

    /** Held by a stall: released at its end. */
    private fun stalled(arriveMs: Double): Double {
        for ((s, len) in stalls) if (arriveMs >= s && arriveMs < s + len) return s + len + r.nextDouble() * 0.5
        return arriveMs
    }

    fun send(atMs: Double): List<Double> {
        sent++
        if (!p.tcp) {
            if (outage(atMs) || drops(atMs)) { lost++; return emptyList() }
            // One flow keeps its order on one route (later packets queue behind earlier ones), except a reordering: one held back.
            var a = stalled(atMs + delay(atMs))
            if (p.reorderP > 0 && r.nextDouble() < p.reorderP) a += p.reorderMs * (0.5 + r.nextDouble())
            else { a = max(a, lastArrival); lastArrival = a }
            return if (p.dupP > 0 && r.nextDouble() < p.dupP) listOf(a, a + 0.3 + r.nextDouble()) else listOf(a)
        }
        // TCP: a lost packet is sent again after the retransmit timeout (doubling each time), and nothing behind it is read
        // before it comes (head-of-line blocking).
        val rto = max(200.0, 3 * base(atMs))
        var t = atMs; var wait = rto; var tries = 0
        while ((outage(t) || drops(t)) && tries < 12) { t += wait; wait *= 2; tries++ }
        val a = max(stalled(t + delay(t)), lastArrival)
        lastArrival = a
        return listOf(a)
    }
}

/** The networks every scenario runs over (one way). */
object Nets {
    val LAN = NetProfile("lan", 1.0, noiseMs = 0.2)
    val GOOD = NetProfile("good", 15.0, jitterMs = 2.0, noiseMs = 0.5)
    val TYPICAL = NetProfile("typical", 40.0, jitterMs = 5.0, noiseMs = 2.0, loss = 0.002)
    val FAR = NetProfile("far", 90.0, jitterMs = 5.0, noiseMs = 2.0, loss = 0.005)
    val VERY_FAR = NetProfile("very far", 160.0, jitterMs = 10.0, noiseMs = 3.0, loss = 0.01)
    val JITTERY = NetProfile("jittery", 30.0, jitterMs = 25.0, jitterTauMs = 120.0, noiseMs = 8.0)
    val WIFI = NetProfile("wifi", 10.0, jitterMs = 3.0, noiseMs = 2.0, loss = 0.01, stallEveryS = 8.0, stallMs = 250.0)
    val MOBILE = NetProfile("4g", 50.0, jitterMs = 20.0, noiseMs = 10.0, spikeP = 0.02, spikeMs = 150.0, badEveryS = 3.0, badMs = 100.0, badLoss = 0.6)
    val LOSSY_5 = TYPICAL.copy(name = "5% loss", loss = 0.05)
    val LOSSY_15 = TYPICAL.copy(name = "15% loss", loss = 0.15)
    val BURST_LOSS = TYPICAL.copy(name = "burst loss", badEveryS = 1.5, badMs = 150.0, badLoss = 0.8)
    val REORDER = TYPICAL.copy(name = "reordering", reorderP = 0.05, reorderMs = 15.0)
    val BLOAT = TYPICAL.copy(name = "bufferbloat", bloatEveryS = 15.0, bloatForS = 4.0, bloatMs = 300.0)
    val OUTAGES = TYPICAL.copy(name = "outages", outages = listOf(10_000L to 1_000L, 22_000L to 3_000L, 34_000L to 8_000L))
    val ROUTE_CHANGE = TYPICAL.copy(name = "route change", steps = listOf(15_000L to 120.0, 30_000L to 25.0, 45_000L to 60.0))
    val SATELLITE = NetProfile("satellite", 300.0, jitterMs = 30.0, noiseMs = 10.0, loss = 0.01)
    val HORRIBLE = NetProfile("horrible", 120.0, jitterMs = 40.0, noiseMs = 20.0, spikeP = 0.03, spikeMs = 200.0, badEveryS = 2.0, badMs = 150.0, badLoss = 0.7,
        stallEveryS = 10.0, stallMs = 400.0, reorderP = 0.03, reorderMs = 30.0)

    val ALL = listOf(LAN, GOOD, TYPICAL, FAR, VERY_FAR, JITTERY, WIFI, MOBILE, LOSSY_5, LOSSY_15, BURST_LOSS, REORDER, BLOAT, OUTAGES, ROUTE_CHANGE, SATELLITE, HORRIBLE)
}
