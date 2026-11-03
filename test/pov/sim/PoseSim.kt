package com.engineerclient.pov.sim

import com.engineerclient.pov.PoseTrack
import com.engineerclient.pov.PoseWire
import com.engineerclient.pov.TurnLink
import net.minecraft.world.phys.Vec3
import java.nio.ByteBuffer
import java.util.PriorityQueue
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The two games' side of it: frame rates (and how unevenly frames come), hitches (a frame that takes [stutterMs], about every
 * [stutterEveryS]), the sender's clock running [driftPpm] apart from the receiver's, and the settings: Sharing FPS, Skip tiny
 * look changes, Optimize server location, and whether TURN is there at all (else the relay alone: UDP blocked, or no key).
 */
data class Env(
    val senderFps: Double = 60.0,
    val receiverFps: Double = 60.0,
    val frameJitter: Double = 0.15,
    val stutterEveryS: Double = 0.0,
    val stutterMs: Double = 0.0,
    val recvStutterEveryS: Double = 0.0,
    val recvStutterMs: Double = 0.0,
    val driftPpm: Double = 0.0,
    val shareFps: Int = 60,
    val skipTiny: Boolean = true,
    val optimize: Boolean = true,
    val turn: Boolean = true,
    val label: String = "",
)

data class Scenario(val group: String, val net: NetProfile, val motion: Motion, val env: Env = Env(), val seconds: Int = 30, val seed: Long = 1,
                    val relay: NetProfile = net.asRelay(), val back: NetProfile = net, val tuning: PoseTrack.Tuning = PoseTrack.Tuning()) {
    val name get() = "$group · ${net.name} · ${motion.name}" + (if (env.label.isNotEmpty()) " · ${env.label}" else "")
}

/** One second of a run: medians of the delays, how many frames showed nothing, and the buffers. */
class Second(val s: Int, val posDelayMs: Double, val lookDelayMs: Double, val behindBlocks: Double, val blankFrames: Int, val frames: Int,
             val bufferMs: Double, val posBufferMs: Double, val viaTurn: Boolean, val relayFrames: Int, val turnBytes: Long)

class Result(
    val scenario: Scenario,
    /** How far behind the teammate's own screen the view draws them: where (while they move) and where they look (while they turn), ms. */
    val posDelay: Stats, val lookDelay: Stats,
    /** How far from the matched spot the drawn one is (blocks; shape: overshoot, wobble), and how far behind where they are now. */
    val shapeErr: Stats, val behind: Stats,
    val lookShapeErr: Stats,
    val frames: Int, val blankFrames: Int, val longestBlankMs: Double, val firstShownMs: Double,
    /** Frames the view jumped further than they could move (not a teleport), and stepped backwards while they ran on. */
    val jumps: Int, val backsteps: Int,
    /** Frames the drawing went back in time (their past replayed again): where, and where they look. */
    val posRewinds: Int, val lookRewinds: Int,
    val routeSwitches: Int, val turnShare: Double,
    val turnUpBytesPerS: Double, val turnDownBytesPerS: Double, val relayFramesPerS: Double, val relayFramesMaxPerS: Int, val relayBytesPerS: Double,
    val turnFramesSent: Long, val turnFramesLost: Long, val turnFramesReordered: Long,
    val heardNs: Double, val drawnNs: Double, val sendNs: Double,
    val seconds: List<Second>,
    /** After each outage ([NetProfile.outages]) ends, how long until they're drawn again (ms; NaN: never). */
    val recoveryMs: List<Double> = emptyList(),
)

class Stats(xs: DoubleArray) {
    val n = xs.size
    private val s = xs.sortedArray()
    fun q(p: Double) = if (n == 0) Double.NaN else s[min(n - 1, max(0, (p * (n - 1)).toInt()))]
    val p50 get() = q(0.5)
    val p95 get() = q(0.95)
    val p99 get() = q(0.99)
    val max get() = if (n == 0) Double.NaN else s[n - 1]
    val mean get() = if (n == 0) Double.NaN else s.average()
}

/**
 * One teammate (the sender) sharing with you (the receiver) over [Scenario.net], simulated in virtual time: the sender's
 * game ticks and frames as a game does (20 ticks a second, run at the first frame after they're due, at most 10 a frame),
 * its [PoseWire.Sender] decides what goes when, exactly as in the game; TURN's frames cross [Link]s with TurnLink's own rules
 * (a counter not past the last heard is dropped; pings and pongs each way, 1 s apart while live and 200 ms until; live for
 * 2.5 s after the last heard), the relay's cross a TCP-like link; the route is [PoseWire.viaTurn] on the round trips as
 * measured; and your game draws them from a [PoseTrack] every frame. Each drawn pose is then matched against what the
 * sender's own screen showed, to find how far behind it is.
 */
class PoseSim(private val sc: Scenario) {
    private val env = sc.env
    private val m = sc.motion
    private val durMs = sc.seconds * 1000.0
    private val rng = SplittableRandom(sc.seed * 7919 + 17)
    private val up = Link(sc.net, sc.seed * 31 + 1, durMs.toLong() + 10_000)
    private val down = Link(sc.back, sc.seed * 31 + 2, durMs.toLong() + 10_000)
    private val relayUp = Link(sc.relay, sc.seed * 31 + 3, durMs.toLong() + 10_000)
    /** Round trips to the relay (each game's own, half the relay path each), as its pings measure them. */
    private val relayProbeA = Link(sc.relay.copy(baseMs = sc.relay.baseMs / 2, tcp = false, loss = 0.0, badEveryS = 0.0, steps = sc.relay.steps.map { it.first to it.second / 2 }), sc.seed * 31 + 4, durMs.toLong() + 10_000)
    private val relayProbeB = Link(sc.relay.copy(baseMs = sc.relay.baseMs / 2, tcp = false, loss = 0.0, badEveryS = 0.0, steps = sc.relay.steps.map { it.first to it.second / 2 }), sc.seed * 31 + 5, durMs.toLong() + 10_000)

    private val track = PoseTrack(sc.tuning)
    private val sender = PoseWire.Sender()

    private class Ev(val t: Double, val order: Long, val fn: () -> Unit)
    private val q = PriorityQueue<Ev>(compareBy<Ev>({ it.t }, { it.order }))
    private var order = 0L
    private fun at(t: Double, fn: () -> Unit) { q.add(Ev(t, order++, fn)) }

    // Clocks: the receiver's is the reference; the sender's runs [driftPpm] apart, from somewhere else entirely.
    private val drift = 1 + env.driftPpm * 1e-6
    private val senderOff = 7_000_000_000_000L + rng.nextLong(1_000_000_000_000L)
    private val recvOff = 3_000_000_000_000L + rng.nextLong(1_000_000_000_000L)
    private fun senderNs(t: Double) = (t * drift * 1e6).toLong() + senderOff
    private fun senderMs(t: Double) = ((senderNs(t) / 1_000_000L) and 0xFFFF).toInt()
    private fun recvNs(t: Double) = (t * 1e6).toLong() + recvOff

    // The sender's side of TURN.
    private var sCtr = 0L
    private var sHeard = 0L
    private var sHeardAt = -1e9
    private var sRtt = -1.0
    private val sPings = HashMap<Int, Double>()
    private var sLastPing = -1e9
    private var theirRelayMs = -1
    private var viaTurn: Boolean? = null
    private var routeSwitches = 0
    private var turnTicks = 0; private var ticks = 0
    // The receiver's side.
    private var rCtr = 0L
    private var rHeard = 0L
    private var rHeardAt = -1e9
    private var rRtt = -1.0
    private val rPings = HashMap<Int, Double>()
    private var rLastPing = -1e9
    // Each game's round trip to the relay (smoothed as PoseShare does), and when it last pinged.
    private var sRelayRtt = -1.0; private var rRelayRtt = -1.0; private var relayPingAt = -1e9
    private var nextPing = 1

    private var turnFramesSent = 0L; private var turnFramesLostOrDropped = 0L; private var reordered = 0L
    private var turnUpBytes = 0L; private var turnDownBytes = 0L
    private var relayFrames = 0L; private var relayBytes = 0L
    private val relayPerSecond = IntArray(sc.seconds + 2)
    private val turnBytesPerSecond = LongArray(sc.seconds + 2)
    private val turnPerSecond = BooleanArray(sc.seconds + 2)

    private var heardNs = 0L; private var heardCalls = 0L
    private var drawnNs = 0L; private var drawnCalls = 0L
    private var sendNs = 0L; private var sendCalls = 0L

    private class Rec(val t: Double, val pos: Vec3?, val yaw: Float, val pitch: Float, val buffer: Long, val posBuffer: Long)
    private val recs = ArrayList<Rec>()

    private val LIVE_MS = 2500.0
    private val frameNs = 1_000_000_000L / env.shareFps.toLong().coerceIn(20L, 240L)

    private fun live(heardAt: Double, now: Double) = now - heardAt < LIVE_MS

    // ------------------------------------------------------------------ TURN frames each way

    private fun turnSend(kind: Int, body: ByteArray, now: Double) {
        val ctr = ++sCtr
        val size = 4 + 4 + body.size + 12
        turnUpBytes += size + 28L
        val sec = (now / 1000).toInt(); if (sec in turnBytesPerSecond.indices) turnBytesPerSecond[sec] += size + 28L
        turnFramesSent++
        val arrivals = up.send(now)
        if (arrivals.isEmpty()) turnFramesLostOrDropped++
        for (a in arrivals) at(a) { receiverGot(ctr, kind, body, a) }
    }

    /** At the receiver's TurnLink: the counter check, then PoseShare.direct. */
    private fun receiverGot(ctr: Long, kind: Int, body: ByteArray, now: Double) {
        val c = TurnLink.counter(rHeard, ctr and 0xFFFFFF)
        if (c <= rHeard) { reordered++; return }
        rHeard = c; rHeardAt = now
        when (kind) {
            TurnLink.PING -> { val n = ByteBuffer.wrap(body).int; receiverSend(TurnLink.PONG, n, now) }
            TurnLink.PONG -> { val n = ByteBuffer.wrap(body).int; rPings.remove(n)?.let { sent -> val ms = now - sent; rRtt = if (rRtt < 0) ms else rRtt * 0.7 + ms * 0.3 } }
            PoseWire.D_TICK -> PoseWire.readTick(body)?.let { heard(true, it, now, if (rRtt > 0) (rRtt / 2).toInt() else 0) }
            PoseWire.D_LOOK -> PoseWire.readLook(body)?.let { heard(true, it, now, if (rRtt > 0) (rRtt / 2).toInt() else 0) }
        }
    }

    private fun receiverSend(kind: Int, n: Int, now: Double) {
        val ctr = ++rCtr
        turnDownBytes += 4 + 4 + 6 + 12 + 28L
        val myRelay = if (rRelayRtt > 0) (rRelayRtt / 2).toInt() else -1
        for (a in down.send(now)) at(a) { senderGot(ctr, kind, n, myRelay, a) }
    }

    private fun senderGot(ctr: Long, kind: Int, n: Int, theirRelay: Int, now: Double) {
        val c = TurnLink.counter(sHeard, ctr and 0xFFFFFF)
        if (c <= sHeard) return
        sHeard = c; sHeardAt = now
        theirRelayMs = theirRelay
        when (kind) {
            TurnLink.PING -> turnSend(TurnLink.PONG, ByteBuffer.allocate(6).putInt(n).putShort(0).array(), now)
            TurnLink.PONG -> sPings.remove(n)?.let { sent -> val ms = now - sent; sRtt = if (sRtt < 0) ms else sRtt * 0.7 + ms * 0.3 }
        }
    }

    /** TurnLink.tick's pings, each way: a second apart while live, 200 ms until. */
    private fun pings(now: Double) {
        if (!env.turn) return
        if (now - sLastPing >= (if (live(sHeardAt, now)) 1000.0 else 200.0)) {
            sLastPing = now
            val n = nextPing++
            sPings[n] = now
            turnSend(TurnLink.PING, ByteBuffer.allocate(6).putInt(n).putShort(0).array(), now)
        }
        if (now - rLastPing >= (if (live(rHeardAt, now)) 1000.0 else 200.0)) {
            rLastPing = now
            val n = nextPing++
            rPings[n] = now
            receiverSend(TurnLink.PING, n, now)
        }
        // Each game's ping to the relay, every 2 s (PoseShare's, smoothed 0.7/0.3).
        if (now - relayPingAt >= 2000) {
            relayPingAt = now
            val a = relayProbeA.send(now).firstOrNull()?.let { it - now }?.let { it + (relayProbeA.send(now + it).firstOrNull()?.minus(now + it) ?: it) }
            val b = relayProbeB.send(now).firstOrNull()?.let { it - now }?.let { it + (relayProbeB.send(now + it).firstOrNull()?.minus(now + it) ?: it) }
            if (a != null) sRelayRtt = if (sRelayRtt < 0) a else sRelayRtt * 0.7 + a * 0.3
            if (b != null) rRelayRtt = if (rRelayRtt < 0) b else rRelayRtt * 0.7 + b * 0.3
        }
    }

    // ------------------------------------------------------------------ the relay

    // The relay's allowance for each game (adr src/pose.js RATE, BURST): past it, frames are dropped.
    private var tokens = 200.0; private var tokensAt = 0.0
    var relayDropped = 0L; private set

    private fun relaySend(frame: ByteArray, now: Double) {
        tokens = min(200.0, tokens + (now - tokensAt) * 100 / 1000); tokensAt = now
        if (tokens < 1) { relayDropped++; return }
        tokens--
        relayFrames++; relayBytes += frame.size + 1 + 6 + 40L
        val sec = (now / 1000).toInt(); if (sec in relayPerSecond.indices) relayPerSecond[sec]++
        // The relay prepends the sender's slot.
        val out = byteArrayOf(1) + frame
        for (a in relayUp.send(now)) at(a) {
            val b = ByteBuffer.wrap(out, 2, out.size - 2)
            val h = PoseWire.readRelay(b)
            heard(false, h, a, h.halfMs)
        }
    }

    private fun heard(direct: Boolean, h: PoseWire.Heard, now: Double, delayMs: Int) {
        val t0 = System.nanoTime()
        val ok = track.heard(null, 1, recvNs(now), direct, direct, h.raw, h.pos, h.yaw, h.pitch, h.flags, h.item, delayMs)
        if (trace) arrivals += Triple(now, if (direct) (if (h.pos == null) "look" else "tick") else "relay", ok)
        if (now > 2000) { heardNs += System.nanoTime() - t0; heardCalls++ }
    }

    // ------------------------------------------------------------------ the sender's game

    private var nextTick = 0L
    private fun local(t: Double) = t * drift

    private fun senderFrame(t: Double) {
        val c0 = System.nanoTime()
        // Ticks: the ones due, at most 10 a frame (a game further behind skips the rest).
        var ran = 0
        while (nextTick * 50.0 <= local(t) && ran < 10) { senderTick(nextTick, t); nextTick++; ran++ }
        if (ran == 10 && nextTick * 50.0 <= local(t)) nextTick = floor(local(t) / 50).toLong() + 1
        // The frame's look (PoseShare.frame), over TURN only.
        if (viaTurn == true) {
            val bodies = sender.look(senderNs(t), senderMs(t), m.yaw(t / 1000), m.pitch(t / 1000), frameNs, env.skipTiny)
            bodies?.forEach { turnSend(PoseWire.D_LOOK, it, t) }
        }
        if (t > 2000) { sendNs += System.nanoTime() - c0; sendCalls++ }
        pings(t)
        var dt = 1000.0 / env.senderFps * (1 + env.frameJitter * (rng.nextDouble() * 2 - 1))
        if (env.stutterEveryS > 0 && rng.nextDouble() < dt / (env.stutterEveryS * 1000)) dt += env.stutterMs * (0.5 + rng.nextDouble())
        if (t + dt < durMs) at(t + dt) { senderFrame(t + dt) }
    }

    private fun senderTick(n: Long, t: Double) {
        val due = n * 50.0 / drift / 1000
        val pos = m.pos(due)
        val yaw = m.yaw(t / 1000); val pitch = m.pitch(t / 1000)
        val flags = if (m.sneaking(due)) 2 else 0
        val item = m.item(due)
        // PoseShare.routes, every tick.
        val was = viaTurn
        viaTurn = env.turn && PoseWire.viaTurn(viaTurn, live(sHeardAt, t), env.optimize, if (sRtt > 0) sRtt / 2 else -1.0,
            if (sRelayRtt > 0 && theirRelayMs >= 0) sRelayRtt / 2 + theirRelayMs else -1.0)
        if (was != null && was != viaTurn) routeSwitches++
        ticks++; if (viaTurn == true) { turnTicks++; val sec = (t / 1000).toInt(); if (sec in turnPerSecond.indices) turnPerSecond[sec] = true }
        val nano = senderNs(t)
        var relay = true
        if (viaTurn == true) {
            val bodies = sender.tick(nano, senderMs(t), pos.x, pos.y, pos.z, yaw, pitch, flags, item)
            bodies?.forEach { turnSend(PoseWire.D_TICK, it, t) }
            relay = false
        }
        val frame = PoseWire.relay(1, -1, 0, n.toInt(), pos.x, pos.y, pos.z, yaw, pitch, flags, sRelayRtt, item)
        for (f in sender.relayFrames(frame, nano, relay)) relaySend(f, t)
    }

    // ------------------------------------------------------------------ the receiver's game

    private fun receiverFrame(t: Double) {
        val c0 = System.nanoTime()
        val d = track.drawn(recvNs(t))
        if (t > 2000) { drawnNs += System.nanoTime() - c0; drawnCalls++ }
        recs += Rec(t, d?.pos, d?.yaw ?: 0f, d?.pitch ?: 0f, track.buffer, track.posBuffer())
        var dt = 1000.0 / env.receiverFps * (1 + env.frameJitter * (rng.nextDouble() * 2 - 1))
        if (env.recvStutterEveryS > 0 && rng.nextDouble() < dt / (env.recvStutterEveryS * 1000)) dt += env.recvStutterMs * (0.5 + rng.nextDouble())
        if (t + dt < durMs) at(t + dt) { receiverFrame(t + dt) }
    }

    // ------------------------------------------------------------------ what their own screen showed

    /** Where the sender's own game draws them at [t] (ms): between its last two ticks by how far it is into the next. */
    private fun truthPos(t: Double): Vec3 {
        val k = local(t) / 50
        val n = floor(k)
        val a = m.pos((n - 1) * 50 / drift / 1000); val b = m.pos(n * 50 / drift / 1000)
        return a.lerp(b, k - n)
    }

    fun run(): Result {
        val s0 = rng.nextDouble() * 10; val r0 = rng.nextDouble() * 10
        at(s0) { senderFrame(s0) }
        at(r0) { receiverFrame(r0) }
        while (q.isNotEmpty()) { val e = q.poll(); if (e.t > durMs + 3000) break; e.fn() }
        return measure()
    }

    /** POV_SIM_TRACE=<part of a scenario name>: every frame of it to build/pov-sim/trace-<n>.tsv (drawn, truth, the track). */
    private val trace = System.getenv("POV_SIM_TRACE")?.let { sc.name.contains(it) } == true
    private val traceRows = StringBuilder()
    private val arrivals = ArrayList<Triple<Double, String, Boolean>>()

    private fun measure(): Result {
        if (trace) {
            traceRows.append("t\tdrawn x\tdrawn z\ttruth x\ttruth z\tdrawn yaw\ttruth yaw\tbuffer\tpos buffer\n")
            for (r in recs) { val tp = truthPos(r.t); traceRows.append(String.format(java.util.Locale.ROOT, "%.1f\t%.3f\t%.3f\t%.3f\t%.3f\t%.1f\t%.1f\t%.1f\t%.1f\n",
                r.t, r.pos?.x ?: Double.NaN, r.pos?.z ?: Double.NaN, tp.x, tp.z, r.yaw, m.yaw(r.t / 1000), r.buffer / 1e6, r.posBuffer / 1e6)) }
            for ((t, k, ok) in arrivals) traceRows.append(String.format(java.util.Locale.ROOT, "%.1f\t%s %s\n", t, k, if (ok) "kept" else "DROPPED"))
            java.io.File("build/pov-sim").mkdirs()
            java.io.File("build/pov-sim/trace-" + sc.name.replace(Regex("[^A-Za-z0-9]+"), "_") + ".tsv").writeText(traceRows.toString())
        }
        val posD = ArrayList<Double>(); val lookD = ArrayList<Double>(); val shape = ArrayList<Double>(); val behind = ArrayList<Double>(); val lookShape = ArrayList<Double>()
        var blank = 0; var longest = 0.0; var blankStart = -1.0; var first = -1.0; var frames = 0
        var jumps = 0; var backsteps = 0; var posRewinds = 0; var lookRewinds = 0
        var lastPosAt = Double.NaN; var lastLookAt = Double.NaN
        var prevPosTau = 100.0; var prevLookTau = 100.0
        var prev: Rec? = null
        val perSec = Array(sc.seconds + 1) { ArrayList<DoubleArray>() }
        val blankPerSec = IntArray(sc.seconds + 1); val framesPerSec = IntArray(sc.seconds + 1)
        for (r in recs) {
            if (r.t < 1000) { prev = r; continue }  // the first second: linking up
            frames++
            val sec = (r.t / 1000).toInt().coerceAtMost(sc.seconds)
            framesPerSec[sec]++
            if (r.pos == null) {
                if (first >= 0) { blank++; blankPerSec[sec]++; if (blankStart < 0) blankStart = r.t }
                prev = r; continue
            }
            if (blankStart >= 0) { longest = max(longest, r.t - blankStart); blankStart = -1.0; lastPosAt = Double.NaN; lastLookAt = Double.NaN }
            if (first < 0) first = r.t
            val now = truthPos(r.t)
            // Matched whenever they moved (or turned) in the last 1.5 s: what's drawn can be from then.
            val moving = (1..6).any { now.distanceTo(truthPos(r.t - it * 250.0)) > 0.05 }
            var pd = Double.NaN; var ld = Double.NaN
            if (moving) {
                val (tau, err) = bestTau(prevPosTau) { tau -> r.pos.distanceTo(truthPos(r.t - tau)) }
                prevPosTau = tau
                if (now.distanceTo(truthPos(r.t - 200)) > 0.3) behind += r.pos.distanceTo(now)
                // Only where the match is one moment: they were moving then (still, any moment of it matches).
                if (truthPos(r.t - tau).distanceTo(truthPos(r.t - tau - 30)) > 0.08) {
                    pd = tau; posD += tau; shape += err
                    // Drawn from earlier in their past than the frame before was (8 ms is past the matching's own wobble).
                    if (err < 0.1 && !lastPosAt.isNaN() && r.t - tau < lastPosAt - 8 && !Motions.teleportNear(m, r.t / 1000, 1.6)) posRewinds++
                    if (err < 0.1) lastPosAt = r.t - tau
                } else lastPosAt = Double.NaN
            }
            val turning = (1..6).any { Motions.angle(m.yaw(r.t / 1000), m.yaw((r.t - it * 250.0) / 1000)) > 0.5f || abs(m.pitch(r.t / 1000) - m.pitch((r.t - it * 250.0) / 1000)) > 0.5f }
            if (turning) {
                val (tau, err) = bestTau(prevLookTau) { tau -> Motions.angle(r.yaw, m.yaw((r.t - tau) / 1000)).toDouble() + abs(r.pitch - m.pitch((r.t - tau) / 1000)) }
                prevLookTau = tau
                if (Motions.angle(m.yaw((r.t - tau) / 1000), m.yaw((r.t - tau - 30) / 1000)) > 0.9f) {
                    ld = tau; lookD += tau; lookShape += err
                    if (err < 1.0 && !lastLookAt.isNaN() && r.t - tau < lastLookAt - 8) lookRewinds++
                    if (err < 1.0) lastLookAt = r.t - tau
                } else lastLookAt = Double.NaN
            }
            perSec[sec] += doubleArrayOf(pd, ld, if (moving) r.pos.distanceTo(now) else Double.NaN, r.buffer / 1e6, r.posBuffer / 1e6)
            // Glitches: further in a frame than they could go (and no teleport), or back against a steady run.
            val p = prev
            if (p?.pos != null) {
                val dt = (r.t - p.t) / 1000
                val step = r.pos.distanceTo(p.pos)
                var speed = 0.0
                var tt = r.t - 1000.0
                while (tt < r.t) { speed = max(speed, truthPos(tt + 50).distanceTo(truthPos(tt)) * 20); tt += 50 }
                val tele = Motions.teleportNear(m, r.t / 1000, 1.6)
                if (!tele && step > max(0.6, 3 * speed * dt + 0.1)) jumps++
                val v1 = truthPos(r.t - 150).subtract(truthPos(r.t - 300)); val v2 = truthPos(r.t).subtract(truthPos(r.t - 150))
                if (!tele && v1.length() > 0.3 && v2.length() > 0.3 && v1.normalize().dot(v2.normalize()) > 0.95) {
                    val dir = v2.normalize()
                    if (r.pos.subtract(p.pos).dot(dir) < -0.03) backsteps++
                }
            }
            prev = r
        }
        if (blankStart >= 0) longest = max(longest, recs.last().t - blankStart)
        val seconds = (1..sc.seconds).map { s ->
            val rows = perSec.getOrNull(s) ?: arrayListOf()
            fun med(i: Int) = rows.map { it[i] }.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
            Second(s, med(0), med(1), med(2), blankPerSec.getOrElse(s) { 0 }, framesPerSec.getOrElse(s) { 0 }, med(3), med(4), turnPerSecond.getOrElse(s) { false },
                relayPerSecond.getOrElse(s) { 0 }, turnBytesPerSecond.getOrElse(s) { 0 })
        }
        val secs = sc.seconds.toDouble()
        return Result(sc, Stats(posD.toDoubleArray()), Stats(lookD.toDoubleArray()), Stats(shape.toDoubleArray()), Stats(behind.toDoubleArray()),
            Stats(lookShape.toDoubleArray()),
            frames, blank, longest, if (first < 0) Double.NaN else first - 1000, jumps, backsteps, posRewinds, lookRewinds,
            routeSwitches, if (ticks == 0) 0.0 else turnTicks.toDouble() / ticks,
            turnUpBytes / secs, turnDownBytes / secs, relayFrames / secs, relayPerSecond.max(), relayBytes / secs,
            turnFramesSent, turnFramesLostOrDropped, reordered,
            if (heardCalls == 0L) 0.0 else heardNs.toDouble() / heardCalls, if (drawnCalls == 0L) 0.0 else drawnNs.toDouble() / drawnCalls,
            if (sendCalls == 0L) 0.0 else sendNs.toDouble() / sendCalls, seconds,
            sc.net.outages.filter { it.first + it.second < durMs - 1000 }.map { (s, len) -> val end = (s + len).toDouble(); recs.firstOrNull { it.t >= end && it.pos != null }?.let { it.t - end } ?: Double.NaN })
    }

    /**
     * The delay (ms back in the sender's screen) that best matches what's drawn: near the last one first (a path can
     * come back on itself), the whole 0-1.5 s when that doesn't fit.
     */
    private inline fun bestTau(near: Double, cost: (Double) -> Double): Pair<Double, Double> {
        var best = near; var bestC = Double.MAX_VALUE
        var tau = max(0.0, near - 80)
        while (tau <= near + 80) { val c = cost(tau); if (c < bestC) { bestC = c; best = tau }; tau += 1.0 }
        if (bestC > 0.15) {
            tau = 0.0
            while (tau <= 1500) { val c = cost(tau); if (c < bestC - 0.05) { bestC = c; best = tau }; tau += 2.0 }
        }
        return best to bestC
    }
}
