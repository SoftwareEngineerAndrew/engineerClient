package com.engineerclient.pov

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/**
 * One shared teammate's poses as heard ([heard]) and where to draw them now ([drawn]), for [PoseShare]: no game state, only
 * the times given, so test/pov/sim can run it through any network it likes.
 */
class PoseTrack(private val tuning: Tuning = Tuning()) {
    /**
     * How the position buffer follows how late ticks come ([tickLate]): [rise] of a later one is taken each tick, and it
     * eases back down by [ease] a tick. Taken all at once (rise 1), one late tick put the drawing time back by its lateness
     * in a frame, so they were seen to run backwards (up to a block at speed 500), and with 0.99 it then stayed that far
     * behind for seconds. test/pov/sim (PoseTuningTest) measured these as the best: rewinds 28 -> 2 and 16 ms less delay
     * across its hard networks (49 ms on 4G), for a few more small corrections on the worst ones.
     */
    data class Tuning(val rise: Double = 0.25, val ease: Double = 0.97, val posBufferMaxNs: Long = POS_BUFFER_MAX_NS)

    class Sample(val seq: Long, val time: Long, val arrived: Long, val pos: Vec3, val yaw: Float, val pitch: Float, val flags: Int, val item: Int, val delayMs: Int, val direct: Boolean)

    /** Where to draw them now: [item] a registry id (0 for none); [delayMs] the buffer plus the way here as known. */
    class Drawn(val pos: Vec3, val yaw: Float, val pitch: Float, val item: Int, val swingAt: Long, val sneaking: Boolean, val via: String?, val direct: Boolean, val delayMs: Int, val bufferNs: Long, val timed: Boolean)

    val samples = ArrayDeque<Sample>()
    /** The smallest (arrival - send tick time) seen: arrival at the best latency so far; a sample's time is its tick plus this. */
    var offset = Long.MAX_VALUE
    var lastSeq = -1L
    /**
     * Its poses come over TURN, timed in ms: [samples] are then looks only (from every frame and tick), and [ticks] the
     * positions (time, where), with the held item and sneaking from the newest tick.
     */
    var timed = false
    /** Positions keyed by when their game drew them there: a tick after the tick was due (see [heard]). */
    val ticks = ArrayDeque<Pair<Long, Vec3>>()
    /** The newest ticks' ms past each 50: a tick runs at the first frame after it's due, so the earliest of these is when they're due. */
    val phases = IntArray(16)
    var phaseN = 0
    var item = 0
    var sneaking = false
    var lastArrived = 0L
    /** Smoothed: how late a pose comes past the best so far, and the gap between poses while they flow (ns). */
    var jitter = 3_000_000.0
    var gap = TICK_NS.toDouble()
    var buffer = 70_000_000L
    /** How far past its due time (their clock, at the best latency so far) a tick arrives: rising towards the worst lately, easing down ([Tuning], ns). */
    var tickLate = 30_000_000.0
    fun posBuffer() = maxOf(buffer, (tickLate + 3_000_000).toLong()).coerceAtMost(tuning.posBufferMaxNs)
    var source = 0
    var via: String? = null
    var swingAt = 0L
    /** Poses heard (for the debug info's rate). */
    var arrivals = 0L
    @Volatile var gui: PoseShare.Gui? = null
    fun at(s: Sample) = s.time + offset

    /** When the tick their game ran at [ms] was due: on the 50 ms grid where the newest ticks' lateness starts (the end of the widest gap). */
    fun due(ms: Long): Long {
        phases[phaseN++ % phases.size] = Math.floorMod(ms, 50L).toInt()
        val n = minOf(phaseN, phases.size)
        val r = phases.copyOf(n).also { it.sort() }
        var start = r[0]; var widest = -1
        for (i in 0 until n) { val gap = if (i == 0) r[0] + 50 - r[n - 1] else r[i] - r[i - 1]; if (gap > widest) { widest = gap; start = r[i] } }
        return ms - Math.floorMod(ms - start, 50L)
    }

    fun reset() { samples.clear(); ticks.clear(); offset = Long.MAX_VALUE; lastSeq = -1; phaseN = 0 }

    /** When they said (over TURN, [PoseWire.D_ROUTE]) that their frames go by the relay from now. */
    var turnLeftAt = 0L
    var leftTurn = false
    fun turnLeft(now: Long) = synchronized(this) { turnLeftAt = now; leftTurn = true }

    /** Where to draw them at [now] (System.nanoTime's clock), or null when nothing recent enough has come. */
    fun drawn(now: Long): Drawn? = synchronized(this) {
        val t = this
        val last = t.samples.lastOrNull() ?: return null
        if (now - last.arrived > STALE_NS) return null
        val at = now - t.buffer
        // The two poses either side of the drawing time, or the newest two to run on from (for a little while).
        var a = t.samples.first(); var b = last
        for (s in t.samples) { if (t.at(s) <= at) a = s else { b = s; break } }
        var pos: Vec3; val yaw: Float; val pitch: Float
        if (a === b || t.at(b) == t.at(a)) { pos = b.pos; yaw = b.yaw; pitch = b.pitch }
        else {
            // A look is never run on past the newest (a turn that stopped would swing on); a tick-at-a-time pose is.
            val k = ((minOf(at, t.at(b) + EXTRAPOLATE_NS) - t.at(a)).toDouble() / (t.at(b) - t.at(a))).coerceIn(0.0, if (t.timed) 1.0 else Double.MAX_VALUE)
            pos = a.pos.lerp(b.pos, k)
            yaw = a.yaw + Mth.wrapDegrees(b.yaw - a.yaw) * k.toFloat()
            pitch = Mth.lerp(k.toFloat().coerceAtMost(1f), a.pitch, b.pitch)
        }
        var itemId = b.item; var sneaking = b.flags and 2 != 0
        if (t.timed) {
            // Where they are: between the two ticks either side (as their game drew them), or past the newest by its move.
            // Drawn [posBuffer] behind rather than the looks' [buffer]: a tick comes up to a frame after it was due, and drawn
            // sooner the next one often wasn't there yet, so the move was run on and pulled back when it came (jitter whenever
            // they changed direction). The looks stay as close as they come.
            val atPos = now - t.posBuffer()
            val p1 = t.ticks.lastOrNull() ?: return null
            val p0 = t.ticks.lastOrNull { it.first < p1.first }
            val t1 = p1.first + t.offset
            var lo: Pair<Long, Vec3>? = null; var hi = p1
            for (p in t.ticks) { if (p.first + t.offset <= atPos) lo = p else { hi = p; break } }
            pos = when {
                lo == null -> t.ticks.first().second
                atPos < t1 && hi !== lo -> lo.second.lerp(hi.second, ((atPos - lo.first - t.offset).toDouble() / (hi.first - lo.first)).coerceIn(0.0, 1.0))
                p0 == null -> p1.second
                else -> p0.second.lerp(p1.second, 1.0 + (minOf(atPos - t1, CARRY_NS)).coerceAtLeast(0L).toDouble() / (p1.first - p0.first))
            }
            itemId = t.item; sneaking = t.sneaking
        }
        val delay = ((if (t.timed) t.posBuffer() else t.buffer) / 1_000_000).toInt() + b.delayMs
        return Drawn(pos, yaw, pitch, itemId, t.swingAt, sneaking, t.via, b.direct, delay, t.buffer, t.timed)
    }

    /**
     * A pose heard at [now]: [raw] the sender's tick (relayed) or its ms (over TURN, [timed]), both 16 bits; [pos] null for a
     * look alone; [item] -1 when it didn't come; [delayMs] how long it took to come, as far as known. False when it was
     * dropped (another source still fresh, the relay's ticks while TURN comes, or one older than the newest).
     */
    fun heard(via: String?, source: Int, now: Long, direct: Boolean, timed: Boolean, raw: Long, pos: Vec3?, yaw: Float, pitch: Float, flags: Int, item: Int, delayMs: Int): Boolean = synchronized(this) {
        val t = this
        val fresh = t.samples.lastOrNull()?.let { now - it.arrived <= STALE_NS } == true
        // One source at a time: another (someone else seeing them) takes over only once this one has gone quiet.
        if (t.source != source) {
            if (fresh) return false
            t.source = source; reset()
        }
        // TURN over the relay's ticks: while it comes, the same pose a tick at a time (relayed for others) is
        // skipped, unless they said their frames go by the relay from now ([turnLeft]: no waiting for TURN to go stale,
        // the view held still meanwhile); it starting (or ticks after it stopped) starts the timing over.
        if (t.timed != timed) {
            val left = t.leftTurn && now - t.turnLeftAt < SWITCH_NS
            if (!timed && fresh && !left) return false
            // To TURN on a position (a look alone would leave nothing to draw them at until the next tick), and not from a
            // late TURN frame just after they left it.
            if (timed && ((pos == null && fresh) || (t.leftTurn && now - t.turnLeftAt < LATE_NS))) return false
            t.timed = timed; reset()
            t.gap = if (timed) 16_000_000.0 else TICK_NS.toDouble()
            // Ticks need a tick's buffer at least from the first: TURN's few ms would run each on and pull it back.
            if (!timed) t.buffer = maxOf(t.buffer, 70_000_000L)
        }
        t.via = via
        // 16 bits unwrapped against the newest (ticks wrap after 55 minutes, ms after 65 s).
        val s = if (t.lastSeq < 0) raw else t.lastSeq + ((raw - (t.lastSeq and 0xFFFF) + 0x8000) and 0xFFFF) - 0x8000
        // Relayed ticks only ever move on; over TURN, a pose sent again from before a move comes after later looks.
        if (!timed && t.lastSeq >= 0 && s <= t.lastSeq) return false
        if (timed && t.lastSeq >= 0 && s < t.lastSeq - 1000) return false
        val time = if (timed) s * 1_000_000L else s * TICK_NS
        val off = now - time
        // A gap (they were still, or nobody was watching), or a clock that jumped: timing starts over from here.
        if (!fresh || (t.offset != Long.MAX_VALUE && kotlin.math.abs(off - t.offset) > 1_000_000_000L)) reset()
        if (s > t.lastSeq) t.lastSeq = s
        // Best latency so far, let drift upward slowly (4 ms a second) so a clock that runs a little apart doesn't strand it.
        val since = if (t.lastArrived == 0L) 0L else (now - t.lastArrived).coerceIn(0L, 1_000_000_000L)
        t.offset = if (t.offset == Long.MAX_VALUE) off else minOf(off, t.offset + since / 250)
        // How far behind to draw: the usual gap between poses (while they flow; a still pose's 100 ms isn't one) plus
        // twice the usual lateness, eased towards so the drawing time never jumps.
        val late = (off - t.offset).toDouble().coerceAtLeast(0.0)
        t.jitter = t.jitter * 0.9 + late * 0.1
        val gap = now - t.lastArrived
        if (t.lastArrived != 0L && gap < 80_000_000L) t.gap = t.gap * 0.9 + gap * 0.1
        t.lastArrived = now
        val target = (t.gap + 2 * t.jitter + 2_000_000).toLong().coerceIn(BUFFER_MIN_NS, BUFFER_MAX_NS)
        t.buffer = (t.buffer * 0.95 + target * 0.05).toLong()
        // Kept in time order (a pose from before a move can come after it).
        val sample = Sample(s, time, now, pos ?: Vec3.ZERO, yaw, pitch.coerceIn(-90f, 90f), flags, maxOf(item, 0), delayMs, direct)
        var i = t.samples.size
        while (i > 0 && t.samples[i - 1].time > time) i--
        t.samples.add(i, sample)
        while (t.samples.size > 32) t.samples.removeFirst()
        if (timed && pos != null) {
            // Their game draws them between their last two ticks by how far it is into the next, so a tick's position is
            // where they're drawn a tick after it was due; and it's timed by when it was due, not when it ran (the first
            // frame after, up to a frame late, which made their speed surge and drop).
            val due = t.due(s)
            val key = (due + 50) * 1_000_000L
            // How late this one came after it was due: rising towards a later one over about half a second, easing down over about two.
            val tickLate = (now - t.offset - due * 1_000_000L).toDouble().coerceAtLeast(0.0)
            t.tickLate = if (tickLate > t.tickLate) t.tickLate + (tickLate - t.tickLate) * tuning.rise else t.tickLate * tuning.ease + tickLate * (1 - tuning.ease)
            var j = t.ticks.size
            while (j > 0 && t.ticks[j - 1].first > key) j--
            if (j > 0 && t.ticks[j - 1].first == key && t.ticks[j - 1].second != pos && (j < 2 || t.ticks[j - 2].first < key - TICK_NS)) {
                // Two ticks in one frame (theirs caught up after a slow one): the first was due a tick earlier.
                t.ticks[j - 1] = (key - TICK_NS) to t.ticks[j - 1].second
            }
            if (j == 0 || t.ticks[j - 1].first != key) t.ticks.add(j, key to pos)
            while (t.ticks.size > 8) t.ticks.removeFirst()
            if (j >= t.ticks.size - 1) { if (item >= 0) t.item = item; t.sneaking = flags and 2 != 0 }
        }
        if (flags and 1 != 0) t.swingAt = now
        t.arrivals++
        return true
    }

    companion object {
        const val TICK_NS = 50_000_000L
        /** How far behind the newest pose a shared player is drawn (to ride out uneven arrival): each track's own, from how often
         *  and how evenly its poses come, within these. */
        const val BUFFER_MIN_NS = 6_000_000L
        const val BUFFER_MAX_NS = 90_000_000L
        /** The most a shared position is drawn behind its due time (see [posBuffer]). */
        const val POS_BUFFER_MAX_NS = 120_000_000L
        const val EXTRAPOLATE_NS = 100_000_000L
        /** How far past a peer's newest tick its position is carried on by that tick's move. */
        const val CARRY_NS = 60_000_000L
        /** A shared view older than this isn't shown: an old frame would pass for where they are now. */
        const val STALE_NS = 300_000_000L
        /** After [turnLeft]: the relay's ticks are taken at once for this long, and TURN's (late ones) not for [LATE_NS]. */
        const val SWITCH_NS = 2_000_000_000L
        const val LATE_NS = 300_000_000L
    }
}
