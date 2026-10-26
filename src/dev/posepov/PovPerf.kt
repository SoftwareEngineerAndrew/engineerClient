package com.devgineerclient.posepov

import com.devgineerclient.DevgineerClient
import com.devgineerclient.perf.GameMonitor
import com.devgineerclient.perf.GpuTimer
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLongArray

/**
 * "Performance monitor": what each part of Pose POV costs, on which thread, as time a second (so parts that run every tick and
 * parts that run every frame read the same way), the worst single call, and how often it ran. Off, a timed part costs one
 * volatile read. Shown over the views (and on the HUD outside the menu), and written to the log every 10 seconds.
 */
object PovPerf {
    @Volatile var on = false

    // Game thread, the menu's frames: working out what to draw.
    const val EYES = 0
    const val ENTITIES = 1
    const val BUILD = 2
    const val FEEDS = 3
    const val OVERLAY = 4
    // Game thread, the menu's frames: drawing it.
    const val CULL_WAIT = 5
    const val RESOLVE = 6
    const val BOXES = 7
    const val BLOCKS = 8
    const val FIGURES = 9
    const val HAND = 10
    // Game thread, every tick (menu open or not).
    const val SHARE = 11
    const val WARM = 12
    const val PRERENDER = 13
    const val PREFETCH = 14
    const val LOOKS = 15
    const val PACKETS = 16
    // Inside the above: GPU uploads of pieces, and the room/section builds.
    const val UPLOAD = 17
    const val MAKE = 18
    // Other threads.
    const val DECODE = 19
    const val CULL_AHEAD = 20
    private const val N = 21

    private val names = arrayOf(
        "eyes", "entities", "build", "per view", "labels",
        "cull (waited)", "resolve", "boxes", "blocks", "figures", "hand",
        "sharing", "warm up", "prerender", "prefetch", "looks", "packets",
        "uploads", "builds",
        "decode", "cull ahead",
    )

    private val sum = AtomicLongArray(N)
    private val calls = AtomicLongArray(N)
    private val worst = AtomicLongArray(N)
    // The last full second's.
    private val shownSum = LongArray(N)
    private val shownCalls = LongArray(N)
    private val shownWorst = LongArray(N)
    private var rolledAt = 0L

    // Counts a second.
    private val counts = AtomicLongArray(6)
    const val C_UPLOADS = 0
    const val C_UPLOAD_BYTES = 1
    const val C_DECODES = 2
    const val C_FRAMES = 3
    const val C_ALLOC = 4
    const val C_SLOW = 5
    private val shownCounts = LongArray(6)

    /** The menu's frames this second: the game thread's whole share of them (prepare + render), and the worst one. */
    private var frameNs = 0L
    private var frameWorst = 0L
    private var shownFrameNs = 0L
    private var shownFrameWorst = 0L

    private val gpu = GpuTimer(8)
    private var shownGpuNs = 0.0
    private var gpuSum = 0L
    private var gpuN = 0

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    inline fun <T> time(i: Int, f: () -> T): T {
        if (!on) return f()
        val t = System.nanoTime()
        try { return f() } finally { add(i, System.nanoTime() - t) }
    }

    fun add(i: Int, ns: Long) {
        if (!on) return
        sum.addAndGet(i, ns); calls.incrementAndGet(i)
        worst.accumulateAndGet(i, ns) { a, b -> maxOf(a, b) }
    }

    /** [since] to now as part [i]; now (for the next part). */
    fun lap(i: Int, since: Long): Long { val now = System.nanoTime(); add(i, now - since); return now }

    fun count(c: Int, n: Long = 1) { if (on) counts.addAndGet(c, n) }

    /** Bytes the calling thread has allocated so far (for what one frame of the menu allocates). */
    fun allocated(): Long = if (on) runCatching { threads.currentThreadAllocatedBytes }.getOrDefault(0L) else 0L

    /** One menu frame's game-thread time (prepare + render) and allocations. */
    fun frame(ns: Long, bytes: Long) {
        if (!on) return
        frameNs += ns; frameWorst = maxOf(frameWorst, ns)
        counts.incrementAndGet(C_FRAMES); counts.addAndGet(C_ALLOC, bytes)
        if (ns > 4_000_000L) counts.incrementAndGet(C_SLOW)
    }

    fun gpuBegin() { if (on) gpu.begin() }
    fun gpuEnd() { if (on) gpu.end() }

    private fun roll() {
        val now = System.currentTimeMillis()
        if (now - rolledAt < 1000) return
        rolledAt = now
        for (i in 0 until N) { shownSum[i] = sum.getAndSet(i, 0); shownCalls[i] = calls.getAndSet(i, 0); shownWorst[i] = worst.getAndSet(i, 0) }
        for (i in 0 until 6) shownCounts[i] = counts.getAndSet(i, 0)
        shownFrameNs = frameNs; shownFrameWorst = frameWorst; frameNs = 0; frameWorst = 0
        shownGpuNs = if (gpuN > 0) gpuSum.toDouble() / gpuN else 0.0; gpuSum = 0; gpuN = 0
        logLater = true
    }
    private var logLater = false
    private var loggedAt = 0L

    /** Background threads of the feature, and their CPU a second. */
    private val threadCpu = HashMap<Long, Long>()
    private var threadAt = 0L
    private var threadShown = emptyList<Pair<String, Double>>()
    private fun threadsNow(): List<Pair<String, Double>> {
        val now = System.nanoTime()
        if (now - threadAt < 1_000_000_000L) return threadShown
        val dt = (now - threadAt).toDouble(); threadAt = now
        val byName = HashMap<String, Double>()
        runCatching {
            val ids = threads.allThreadIds
            val infos = threads.getThreadInfo(ids, 0)
            for (k in ids.indices) {
                val name = infos[k]?.threadName ?: continue
                if (!(name.startsWith("pose pov") || name.startsWith("devgineerclient-pose"))) continue
                val cpu = threads.getThreadCpuTime(ids[k])
                if (cpu < 0) continue
                val prev = threadCpu.put(ids[k], cpu)
                if (prev != null) byName.merge(name, (cpu - prev) / dt * 100, Double::plus)
            }
        }
        threadShown = byName.entries.sortedByDescending { it.value }.map { it.key to it.value }
        return threadShown
    }

    private fun ms(ns: Long) = "%.2f".format(ns / 1e6)

    /** The monitor's lines (text, colour). */
    fun lines(): List<Pair<String, Int>> {
        gpu.poll { gpuSum += it; gpuN++ }
        roll()
        val grey = 0xFFB0B0B0.toInt(); val white = 0xFFE8E8E8.toInt(); val yellow = 0xFFE0C040.toInt(); val red = 0xFFFF6060.toInt()
        val out = ArrayList<Pair<String, Int>>()
        val frames = shownCounts[C_FRAMES]
        val fps = GameMonitor.fps().coerceAtLeast(1.0)
        // The whole: what the menu's frames cost the game thread against the frame, and the GPU's time for the views.
        if (frames > 0) {
            val per = shownFrameNs.toDouble() / frames
            val frame = 1e9 / fps
            out += "POV frame: %.2f ms of a %.2f ms frame (%.0f%%) · worst %s ms · %d over 4 ms · GPU %.2f ms · %.0f KB allocated a frame".format(
                per / 1e6, frame / 1e6, per / frame * 100, ms(shownFrameWorst), shownCounts[C_SLOW], shownGpuNs / 1e6, shownCounts[C_ALLOC] / 1024.0 / frames) to
                if (per > frame * 0.3) red else if (per > frame * 0.15) yellow else white
        } else out += "POV frame: menu closed (the per-tick parts below still run)" to grey
        fun row(title: String, parts: IntRange) {
            val sb = StringBuilder(title)
            var total = 0L
            for (i in parts) {
                total += shownSum[i]
                if (shownCalls[i] == 0L) continue
                sb.append(" · ").append(names[i]).append(' ').append(ms(shownSum[i] / maxOf(frames, 1)))
                if (shownWorst[i] > 2_000_000L) sb.append(" (worst ").append(ms(shownWorst[i])).append(')')
            }
            out += sb.toString() to (if (total > 50_000_000L) yellow else grey)
        }
        row("prepare ms/frame:", EYES..OVERLAY)
        row("draw ms/frame:", CULL_WAIT..HAND)
        // Every tick, whether the menu is open or not: as ms a second (1000 is a whole core).
        run {
            val sb = StringBuilder("ticks ms/s:")
            var total = 0L
            for (i in SHARE..MAKE) {
                total += shownSum[i]
                if (shownCalls[i] == 0L) continue
                sb.append(" · ").append(names[i]).append(' ').append("%.1f".format(shownSum[i] / 1e6))
                if (shownWorst[i] > 2_000_000L) sb.append(" (worst ").append(ms(shownWorst[i])).append(')')
            }
            out += sb.toString() to (if (total > 30_000_000L) yellow else grey)
        }
        out += "workers ms/s: decode %.1f (%d pieces, %.1f ms each) · cull ahead %.1f (%d) · uploads %d (%.1f MB/s)".format(
            shownSum[DECODE] / 1e6, shownCalls[DECODE], if (shownCalls[DECODE] > 0) shownSum[DECODE] / 1e6 / shownCalls[DECODE] else 0.0,
            shownSum[CULL_AHEAD] / 1e6, shownCalls[CULL_AHEAD], shownCounts[C_UPLOADS], shownCounts[C_UPLOAD_BYTES] / 1048576.0) to grey
        val ts = threadsNow()
        if (ts.isNotEmpty()) out += ("threads CPU: " + ts.joinToString(" · ") { "%s %.0f%%".format(it.first.removePrefix("pose pov ").removePrefix("devgineerclient-pose-"), it.second) }) to grey
        out += "memory: %d bundles in RAM (%.0f MB) · heap %d of %d MB".format(
            MeshBundles.count, MeshBundles.bytes / 1048576.0, Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) shr 20 }, Runtime.getRuntime().maxMemory() shr 20) to grey
        val nowMs = System.currentTimeMillis()
        if (logLater && nowMs - loggedAt > 10_000) {
            loggedAt = nowMs
            DevgineerClient.logger.info("[pose pov perf] " + out.joinToString(" | ") { it.first })
        }
        logLater = false
        return out
    }
}
