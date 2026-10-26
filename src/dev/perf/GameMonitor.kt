package com.devgineerclient.perf

import com.devgineerclient.DevgineerClient
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.text
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GPU time between two points of the frame, from timestamps the GPU writes as it gets there: [slots] frames in flight, read
 * back once they're done (never waited for). A frame whose slot is still in flight isn't timed.
 */
class GpuTimer(private val slots: Int) {
    private var pool: GpuQueryPool? = null
    private var failed = false
    private val pending = BooleanArray(slots)
    private var next = 0
    private var open = -1
    private val period by lazy { runCatching { RenderSystem.getDevice().deviceInfo.timestampPeriod() }.getOrDefault(1f).let { if (it > 0f) it else 1f } }

    fun begin() {
        if (failed || pending[next]) return
        try {
            val p = pool ?: RenderSystem.getDevice().createTimestampQueryPool(slots * 2).also { pool = it }
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(p, next * 2)
            open = next
        } catch (e: Throwable) { failed = true; DevgineerClient.logger.warn("[perf] GPU timestamps unavailable", e) }
    }

    fun end() {
        val i = open
        if (i < 0) return
        open = -1
        try {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool!!, i * 2 + 1)
            pending[i] = true; next = (i + 1) % slots
        } catch (e: Throwable) { failed = true }
    }

    /** Each finished frame's GPU time (ns). */
    fun poll(each: (Long) -> Unit) {
        val p = pool ?: return
        for (i in 0 until slots) {
            if (!pending[i]) continue
            val a = p.getValue(i * 2); val b = p.getValue(i * 2 + 1)
            if (!a.isPresent || !b.isPresent) continue
            pending[i] = false
            val ns = ((b.asLong - a.asLong) * period).toLong()
            if (ns in 0..1_000_000_000L) each(ns)
        }
    }
}

/**
 * "Game performance monitor" (temporary, for looking at what the whole game does): where each frame's time goes on the
 * game thread (Minecraft's own phases, from GamePerfMixin), the GPU's time, which mods the game thread is in (sampled
 * 100 times a second), the JVM (heap, GC, allocation, threads' CPU), the world and the connection. Top right, over
 * everything, and in the log every 10 seconds.
 */
object GameMonitor {
    @Volatile var on = false
    private val mc get() = Minecraft.getInstance()

    // --- the frame's phases (game thread; GamePerfMixin) ------------------------------------------------------------------

    const val PACKETS = 0
    const val TASKS = 1
    const val TICK = 2
    const val ACQUIRE = 3
    const val EXTRACT = 4
    const val RENDER = 5
    const val WORLD = 6
    const val GUI = 7
    const val SUBMIT = 8
    const val PRESENT = 9
    const val LIMITER = 10
    const val FRAME = 11
    const val POV = 12
    const val POV_PREP = 13
    private const val PHASES = 14

    private val start = LongArray(PHASES)
    private val acc = LongArray(PHASES)
    private val winSum = LongArray(PHASES)
    private val winMax = LongArray(PHASES)
    private val shownSum = LongArray(PHASES)
    private val shownMax = LongArray(PHASES)
    private var winFrames = 0
    private var shownFrames = 0
    private var winTicks = 0
    private var winTickMax = 0L
    private var shownTicks = 0
    private var shownTickMax = 0L
    private var tickStart = 0L

    @JvmStatic fun begin(i: Int) { if (on) start[i] = System.nanoTime() }
    @JvmStatic fun end(i: Int) {
        if (!on || start[i] == 0L) return
        acc[i] += System.nanoTime() - start[i]; start[i] = 0L
    }
    @JvmStatic fun tickBegin() { if (on) tickStart = System.nanoTime() }
    @JvmStatic fun tickEnd() {
        if (!on || tickStart == 0L) return
        val dt = System.nanoTime() - tickStart; tickStart = 0L
        acc[TICK] += dt; winTicks++; winTickMax = maxOf(winTickMax, dt)
    }
    /** Pose POV's own share (its render inside WORLD, its prepare inside EXTRACT). */
    fun pov(i: Int, ns: Long) { if (on) acc[i] += ns }

    // Frame times: the last [HISTORY] (for the graph and the lows).
    private const val HISTORY = 1200
    private val history = LongArray(HISTORY)
    private var historyAt = 0
    private var historyN = 0
    private var lastFrame = 0L
    private var rolledAt = 0L
    private var shownFps = 0.0
    private var shownLow1 = 0.0
    private var shownLow01 = 0.0
    private var shownWorst = 0L
    private var shownHitches = 0
    private var winHitches = 0
    private var mainThread: Thread? = null

    private val gpu = GpuTimer(8)
    private var gpuSum = 0L
    private var gpuN = 0
    private var gpuMax = 0L
    private var shownGpu = 0.0
    private var shownGpuMax = 0L

    /** Frames a second over the last second (for others' "share of a frame"). */
    fun fps(): Double = if (on && shownFps > 0) shownFps else mc.fps.toDouble()

    /** The start of a frame (Minecraft.runTick): closes the last one. */
    @JvmStatic fun frame() {
        val now = System.nanoTime()
        if (!on) { lastFrame = 0L; samplerStop(); return }
        if (mainThread == null) mainThread = Thread.currentThread()
        samplerStart()
        gpu.poll { gpuSum += it; gpuN++; gpuMax = maxOf(gpuMax, it) }
        if (lastFrame != 0L) {
            val dt = now - lastFrame
            acc[FRAME] = dt
            history[historyAt] = dt; historyAt = (historyAt + 1) % HISTORY; if (historyN < HISTORY) historyN++
            for (i in 0 until PHASES) { winSum[i] += acc[i]; winMax[i] = maxOf(winMax[i], acc[i]) }
            winFrames++
            if (shownFps > 0 && dt > 3e9 / shownFps && dt > 20_000_000L) winHitches++
        }
        acc.fill(0L)
        lastFrame = now
        val ms = System.currentTimeMillis()
        if (ms - rolledAt >= 1000) roll(ms)
    }

    @JvmStatic fun gpuBegin() { if (on) gpu.begin() }
    @JvmStatic fun gpuEnd() { if (on) gpu.end() }

    private fun roll(ms: Long) {
        val secs = if (rolledAt == 0L) 1.0 else (ms - rolledAt) / 1000.0
        rolledAt = ms
        winSum.copyInto(shownSum); winMax.copyInto(shownMax); shownFrames = winFrames
        shownTicks = winTicks; shownTickMax = winTickMax
        shownFps = winFrames / secs
        shownHitches = winHitches
        winSum.fill(0); winMax.fill(0); winFrames = 0; winTicks = 0; winTickMax = 0; winHitches = 0
        shownGpu = if (gpuN > 0) gpuSum.toDouble() / gpuN else 0.0; shownGpuMax = gpuMax; gpuSum = 0; gpuN = 0; gpuMax = 0
        if (historyN > 10) {
            val sorted = LongArray(historyN) { history[it] }.also { it.sort() }
            fun low(p: Double): Double { val k = (historyN * p).toInt().coerceAtLeast(1); var s = 0L; for (i in historyN - k until historyN) s += sorted[i]; return 1e9 / (s.toDouble() / k) }
            shownLow1 = low(0.01); shownLow01 = low(0.001); shownWorst = sorted[historyN - 1]
        }
        sampleJvm(secs)
        drainSamples()
        logLater = true
    }

    // --- the game thread, sampled -----------------------------------------------------------------------------------------

    private class Sample(val bucket: String, val code: String?, val top: String)
    private val samples = ConcurrentLinkedQueue<Sample>()
    private const val KEEP = 500
    private val kept = ArrayDeque<Sample>()
    @Volatile private var sampler: Thread? = null

    private fun samplerStart() {
        if (sampler != null) return
        val main = mainThread ?: return
        sampler = Thread({
            while (on) {
                runCatching { classify(main.stackTrace) }.getOrNull()?.let { samples += it }
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            }
            sampler = null
        }, "dc game monitor sampler").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }
    private fun samplerStop() { sampler?.interrupt() }

    private val infra = arrayOf("java.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.lwjgl.", "com.mojang.", "net.minecraft.", "it.unimi.", "com.google.",
        "org.joml.", "io.netty.", "org.prismlauncher.", "org.multimc.", "net.minecraftforge.", "cpw.", "org.spongepowered.", "net.fabricmc.", "org.slf4j.", "org.apache.", "com.llamalad7.", "oshi.", "com.sun.")
    private val mixinHandlers = setOf("handler", "redirect", "modify", "wrapOperation", "wrapWithCondition", "localvar", "constant", "modifyExpressionValue", "modifyReturnValue", "modifyArg", "modifyArgs", "modifyVariable")

    private fun ownMod(cls: String): String? {
        for ((prefix, label) in arrayOf("com.devgineerclient." to "devgineer", "com.engineerclient." to "engineer", "com.coffeeclient." to "coffee")) {
            if (cls.startsWith(prefix)) return label + "/" + cls.substring(prefix.length).substringBefore('.')
        }
        return null
    }

    private fun modOf(cls: String, method: String): String? {
        val parts = method.split('$')
        if (parts.size >= 4 && parts[0] in mixinHandlers) return "mixin " + parts[2]
        ownMod(cls)?.let { return it }
        if (infra.any { cls.startsWith(it) }) return null
        return when {
            cls.startsWith("com.odtheking.") -> "Odin"
            cls.startsWith("net.caffeinemc.") || cls.startsWith("me.jellysquid.") -> "Sodium"
            cls.startsWith("me.flashyreese.") -> "Sodium Extra"
            else -> cls.split('.').let { if (it.size > 3) it.take(3).joinToString(".") else it.dropLast(1).joinToString(".") }
        }
    }

    private fun short(e: StackTraceElement) = e.className.substringAfterLast('.') + "." + e.methodName

    private fun classify(st: Array<StackTraceElement>): Sample? {
        if (st.isEmpty()) return null
        val top = short(st[0])
        for (e in st) {
            val m = modOf(e.className, e.methodName) ?: continue
            return Sample(m, short(e), top)
        }
        // No mod's code on the stack: what Minecraft (or the driver) is doing.
        val waiting = st.take(12).any { it.methodName == "limitDisplayFPS" || it.methodName == "present" || it.methodName == "acquireNextTexture" || it.methodName == "sleep" || it.methodName == "park" }
        val bucket = when {
            waiting -> "waiting (limiter/vsync/GPU)"
            st[0].className.startsWith("org.lwjgl.") -> "GL/driver calls"
            else -> "Minecraft"
        }
        return Sample(bucket, null, top)
    }

    private var shownBuckets = emptyList<Pair<String, Double>>()
    private var shownCode = emptyList<Pair<String, Double>>()
    private var shownTop = emptyList<Pair<String, Double>>()
    private fun drainSamples() {
        while (true) { val s = samples.poll() ?: break; kept.addLast(s); if (kept.size > KEEP) kept.removeFirst() }
        val n = kept.size.toDouble().coerceAtLeast(1.0)
        fun top(key: (Sample) -> String?, k: Int) = kept.mapNotNull(key).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(k).map { it.key to it.value / n * 100 }
        shownBuckets = top({ it.bucket }, 8); shownCode = top({ it.code }, 6); shownTop = top({ it.top }, 5)
    }

    // --- the JVM ----------------------------------------------------------------------------------------------------------

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    private val gcs = ManagementFactory.getGarbageCollectorMXBeans()
    private val gcLast = HashMap<String, LongArray>()
    private val cpuLast = HashMap<Long, Long>()
    private var allocLast = 0L
    private var mainAllocLast = 0L

    private var heapUsed = 0L; private var heapCommitted = 0L; private var heapMax = 0L
    private var direct = 0L
    private var rss = 0L
    private var allocRate = 0.0; private var mainAllocRate = 0.0
    private var gcLine = ""
    private var processCpu = 0.0; private var systemCpu = 0.0
    private var threadCount = 0
    /** Every thread's CPU over the last second (% of one core), busiest first. */
    var threadCpu: List<Pair<String, Double>> = emptyList(); private set

    private fun sampleJvm(secs: Double) {
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        heapUsed = heap.used; heapCommitted = heap.committed; heapMax = heap.max
        direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java).filter { it.name == "direct" }.sumOf { it.memoryUsed }
        rss = runCatching { java.io.File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() * 1024 }.getOrDefault(0L)
        val sb = StringBuilder()
        for (gc in gcs) {
            val prev = gcLast.getOrPut(gc.name) { longArrayOf(gc.collectionCount, gc.collectionTime) }
            val dc = gc.collectionCount - prev[0]; val dt = gc.collectionTime - prev[1]
            prev[0] = gc.collectionCount; prev[1] = gc.collectionTime
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(gc.name).append(' ').append(dc).append("x ").append(dt).append(" ms")
        }
        gcLine = sb.toString()
        processCpu = (os?.processCpuLoad ?: 0.0) * 100 * Runtime.getRuntime().availableProcessors()
        systemCpu = (os?.cpuLoad ?: 0.0) * 100
        val ids = threads.allThreadIds
        threadCount = ids.size
        runCatching {
            val alloc = threads.getThreadAllocatedBytes(ids).sum()
            if (allocLast != 0L) allocRate = (alloc - allocLast) / secs
            allocLast = alloc
            mainThread?.let { val m = threads.getThreadAllocatedBytes(it.threadId()); if (mainAllocLast != 0L) mainAllocRate = (m - mainAllocLast) / secs; mainAllocLast = m }
        }
        runCatching {
            val cpu = threads.getThreadCpuTime(ids)
            val infos = threads.getThreadInfo(ids, 0)
            val out = ArrayList<Pair<String, Double>>()
            val seen = HashSet<Long>()
            for (k in ids.indices) {
                val info = infos[k] ?: continue
                if (cpu[k] < 0) continue
                seen += ids[k]
                val prev = cpuLast.put(ids[k], cpu[k]) ?: continue
                out += info.threadName to (cpu[k] - prev) / (secs * 1e9) * 100
            }
            cpuLast.keys.retainAll(seen)
            // Pools of the same kind ("Worker-Main-3", "Worker-Main-4") together.
            threadCpu = out.groupBy({ it.first.replace(Regex("[-# ]?\\d+$"), "") }, { it.second }).map { (k, v) -> (if (v.size > 1) "$k ×${v.size}" else k) to v.sum() }
                .sortedByDescending { it.second }
        }
    }

    // --- the world and the connection -------------------------------------------------------------------------------------

    private var timeAt = 0L
    private var timeGame = 0L
    private val tps = ArrayDeque<Double>()
    private var lastPacketAt = 0L

    /** A time update from the server (Hypixel sends one a second): its game time against ours gives the server's ticks a second. */
    fun timePacket(gameTime: Long) {
        val now = System.nanoTime()
        if (timeAt != 0L && gameTime > timeGame) {
            val t = (gameTime - timeGame) / ((now - timeAt) / 1e9)
            if (t in 0.0..40.0) { tps.addLast(t); if (tps.size > 10) tps.removeFirst() }
        }
        timeAt = now; timeGame = gameTime
    }
    @JvmStatic fun packet() { lastPacketAt = System.nanoTime() }

    // --- the overlay ------------------------------------------------------------------------------------------------------

    private var logLater = false
    private var loggedAt = 0L

    private fun ms(ns: Long) = "%.2f".format(ns / 1e6)
    private fun ms(ns: Double) = "%.2f".format(ns / 1e6)
    private fun mb(b: Long) = b / 1048576
    private fun mb(b: Double) = b / 1048576

    private var cached: List<Pair<String, Int>> = emptyList()
    private var cachedAt = 0L

    fun lines(): List<Pair<String, Int>> {
        val now = System.nanoTime()
        if (now - cachedAt < 250_000_000L && cached.isNotEmpty()) return cached
        cachedAt = now
        cached = build()
        return cached
    }

    private fun build(): List<Pair<String, Int>> {
        val grey = 0xFFB0B0B0.toInt(); val white = 0xFFE8E8E8.toInt(); val yellow = 0xFFE0C040.toInt(); val red = 0xFFFF6060.toInt(); val head = 0xFF80C8FF.toInt()
        val out = ArrayList<Pair<String, Int>>()
        val n = shownFrames.coerceAtLeast(1)
        fun avg(i: Int) = shownSum[i].toDouble() / n
        val frame = avg(FRAME)
        val opts = mc.options
        val limit = runCatching { mc.framerateLimitTracker.framerateLimit }.getOrDefault(0)
        out += "Game performance" to head
        out += "%.0f fps · %s ms a frame · 1%% low %.0f · 0.1%% low %.0f · worst %s ms (last %d frames) · %d hitches/s".format(
            shownFps, ms(frame), shownLow1, shownLow01, ms(shownWorst), historyN, shownHitches) to (if (shownLow1 < 60) red else if (shownLow1 < 120) yellow else white)
        out += "limit %s (setting %s%s) · vsync %s · window %dx%d · render distance %d · sim %d".format(
            if (limit >= 260 || limit <= 0) "none" else "$limit", opts.framerateLimit().get().let { if (it >= 260) "unlimited" else "$it" },
            if (limit in 1..259 && limit != opts.framerateLimit().get()) ", lowered: AFK/menu/minimised" else "",
            if (opts.enableVsync().get()) "on" else "off", mc.window.width, mc.window.height, opts.renderDistance().get(), opts.simulationDistance().get()) to grey

        // Where the frame goes, on the game thread.
        out += "Game thread, ms a frame (avg / worst in the last second)" to head
        fun part(name: String, ns: Double, max: Long) = "%s %s / %s".format(name, ms(ns), ms(max))
        val world = avg(WORLD) - avg(POV)
        val known = avg(PACKETS) + avg(TASKS) + avg(TICK) + avg(ACQUIRE) + avg(EXTRACT) + avg(RENDER) + avg(SUBMIT) + avg(PRESENT) + avg(LIMITER)
        out += listOf(
            part("packets", avg(PACKETS), shownMax[PACKETS]), part("tasks", avg(TASKS), shownMax[TASKS]),
            part("ticks", avg(TICK), shownMax[TICK]) + " (%d/s, worst tick %s)".format(shownTicks, ms(shownTickMax)),
        ).joinToString(" · ") to grey
        out += listOf(
            part("extract (HUD, screens)", avg(EXTRACT) - avg(POV_PREP), shownMax[EXTRACT]), part("world", world, shownMax[WORLD]),
            part("GUI draw", avg(GUI), shownMax[GUI]), part("other render", avg(RENDER) - avg(WORLD) - avg(GUI), shownMax[RENDER]),
        ).joinToString(" · ") to grey
        out += listOf(
            part("Pose POV", avg(POV) + avg(POV_PREP), maxOf(shownMax[POV], shownMax[POV_PREP])), part("submit", avg(SUBMIT), shownMax[SUBMIT]),
            part("wait for image", avg(ACQUIRE), shownMax[ACQUIRE]), part("present", avg(PRESENT), shownMax[PRESENT]),
            part("limiter sleep", avg(LIMITER), shownMax[LIMITER]), "rest " + ms(frame - known),
        ).joinToString(" · ") to grey
        val waits = avg(ACQUIRE) + avg(PRESENT) + avg(LIMITER)
        val busy = frame - waits
        val gpuShare = if (frame > 0) shownGpu / frame else 0.0
        val verdict = when {
            avg(LIMITER) > frame * 0.2 -> "held back by the fps limit"
            gpuShare > 0.85 || (avg(ACQUIRE) + avg(PRESENT)) > frame * 0.25 -> "GPU-bound (or vsync): the game thread waits on the GPU"
            else -> "CPU-bound: the game thread is busy %.0f%% of the frame".format(busy / frame.coerceAtLeast(1.0) * 100)
        }
        out += "GPU %s ms a frame (worst %s) · %s".format(ms(shownGpu), ms(shownGpuMax), verdict) to (if (verdict.startsWith("CPU")) yellow else white)
        runCatching { RenderSystem.getDevice().deviceInfo }.getOrNull()?.let { d ->
            out += "%s · %s · %s %s".format(d.name(), d.backendName(), d.vendorName(), d.driverInfo().take(40)) to grey
        }

        // Which code the game thread is in.
        out += "Game thread by mod (sampled, last %d)".format(kept.size) to head
        out += shownBuckets.joinToString(" · ") { "%s %.0f%%".format(it.first, it.second) } to white
        if (shownCode.isNotEmpty()) out += ("mods' hottest: " + shownCode.joinToString(" · ") { "%s %.0f%%".format(it.first, it.second) }) to grey
        out += ("top of stack: " + shownTop.joinToString(" · ") { "%s %.0f%%".format(it.first, it.second) }) to grey

        out += "JVM" to head
        out += "heap %d / %d MB (max %d) · direct %d MB · process %d MB · allocating %.0f MB/s (game thread %.0f)".format(
            mb(heapUsed), mb(heapCommitted), mb(heapMax), mb(direct), mb(rss), mb(allocRate), mb(mainAllocRate)) to (if (heapUsed > heapMax * 0.85) red else grey)
        out += "GC last second: $gcLine" to grey
        out += "CPU: game %.0f%% of a core · whole system %.0f%% · %d threads".format(processCpu, systemCpu, threadCount) to grey
        out += ("busiest threads: " + threadCpu.take(7).joinToString(" · ") { "%s %.0f%%".format(it.first, it.second) }) to grey

        out += "World and connection" to head
        val level = mc.level
        if (level != null) {
            val conn = mc.connection?.connection
            val ping = mc.player?.let { p -> mc.connection?.getPlayerInfo(p.uuid)?.latency }
            out += "%d entities · %d chunks · %d sections drawn · particles %s".format(
                level.entityCount, level.chunkSource.loadedChunksCount, runCatching { mc.levelRenderer.visibleSections().size }.getOrDefault(-1),
                runCatching { mc.particleEngine.countParticles() }.getOrDefault("?")) to grey
            out += "server %s tps · packets %.0f in, %.0f out a second · last packet %d ms ago · ping %s".format(
                if (tps.isEmpty()) "?" else "%.1f".format(tps.average()), conn?.averageReceivedPackets ?: 0f, conn?.averageSentPackets ?: 0f,
                if (lastPacketAt == 0L) -1 else (System.nanoTime() - lastPacketAt) / 1_000_000, ping?.let { "$it ms" } ?: "?") to grey
        } else out += "not in a world" to grey

        val nowMs = System.currentTimeMillis()
        if (logLater && nowMs - loggedAt > 10_000) { loggedAt = nowMs; DevgineerClient.logger.info("[game monitor] " + out.joinToString(" | ") { it.first }) }
        logLater = false
        return out
    }

    /** The frame-time graph (last 240 frames) and [lines], top right; scaled down to fit. */
    fun draw(gfx: GuiGraphicsExtractor) {
        val lines = lines()
        val gw = gfx.guiWidth()
        val w = lines.maxOf { mc.font.width(it.first) } + 8
        val graphH = 40
        val h = lines.size * 10 + graphH + 10
        val scale = minOf(1f, gw * 0.62f / w, (gfx.guiHeight() - 4f) / h)
        val pose = gfx.pose()
        pose.pushMatrix()
        pose.translate(gw - w * scale - 2, 2f)
        pose.scale(scale, scale)
        gfx.fill(0, 0, w, h, 0xB0000000.toInt())
        // Frame times, newest on the right: green under a 144 Hz frame, yellow under 60 Hz, red past it; a line at 60 Hz.
        val bars = minOf(historyN, 240)
        val bw = (w - 8) / 240f
        for (k in 0 until bars) {
            val ns = history[(historyAt - bars + k + HISTORY) % HISTORY]
            val bh = (ns / 1e6 / 33.3 * graphH).toInt().coerceIn(1, graphH)
            val c = if (ns < 6_944_444L) 0xFF50D070.toInt() else if (ns < 16_666_667L) 0xFFE0C040.toInt() else 0xFFFF5050.toInt()
            val x = 4 + ((240 - bars + k) * bw).toInt()
            gfx.fill(x, 4 + graphH - bh, x + maxOf(1, bw.toInt()), 4 + graphH, c)
        }
        gfx.fill(4, 4 + graphH / 2, w - 4, 5 + graphH / 2, 0x60FFFFFF)
        var y = graphH + 8
        for ((text, color) in lines) { gfx.text(text, 4, y, Color((color shr 16) and 255, (color shr 8) and 255, color and 255), shadow = true); y += 10 }
        pose.popMatrix()
    }
}
