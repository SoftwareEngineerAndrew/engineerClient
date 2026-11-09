package com.devgineerclient.perf

import com.devgineerclient.DevgineerClient
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import com.odtheking.odin.utils.skyblock.LocationUtils
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import java.io.BufferedWriter
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData

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
 * Records how smoothly the game runs, from the first frame of the launch on, for working out what to make faster. Draws
 * nothing. One file a launch, logs/perf/perf-<time>.jsonl (the newest [KEEP_FILES] kept), a JSON object a line:
 *  - "s": a second: frame rate, frame times (avg, 99th percentile, worst), each phase of the game thread (avg and worst ms,
 *    GamePerfMixin), ticks, GPU time, heap, allocation, GC, classes loaded and JIT time (the warm-up after a launch), CPU by
 *    thread, where the game thread was (sampled 100 times a second, by mod), the world, where you are and the screen;
 *  - "hitch": every frame of [HITCH_NS] or more: its phases, the GCs during it, the game thread's stacks sampled in it;
 *  - "gc": every collection, with its pause; "mark": the launch's moments (LaunchTimeline), world loads and screens opened;
 *  - "first": the first minute after reaching Skyblock in a line (also to logs/perf/summary.log): how smooth the start was.
 * The game thread only takes nanoTimes and adds them up; everything else (the JVM, threads, the file) is on two background
 * threads, and thread CPU comes from /proc, so nothing here pauses the game.
 */
object PerfMonitor : Module(
    name = "Performance Monitor",
    key = null,
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Records how smooth the game runs from launch on (every hitch and what caused it, a line a second) to logs/perf. Draws nothing.",
) {
    private var switchedOn by BooleanSetting("Switched On", false, desc = "").hide()

    fun enableByDefault() {
        if (switchedOn) return
        switchedOn = true
        if (!enabled) toggle()
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }

    private val on get() = enabled

    init {
        on<LevelEvent.Load> { mark("world load") }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ -> mark("screen " + screen.javaClass.simpleName) }
    }

    /** A frame this long or longer is a hitch (a visible stutter: three frames at 144 Hz). */
    private const val HITCH_NS = 20_000_000L
    private const val KEEP_FILES = 30

    // --- the frame's phases (game thread; GamePerfMixin, GamePerfRenderMixin) -----------------------------------------------

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
    private const val PHASES = 11
    private val NAMES = arrayOf("packets", "tasks", "tick", "acquire", "extract", "render", "world", "gui", "submit", "present", "limiter")

    private val start = LongArray(PHASES)
    private val acc = LongArray(PHASES)
    private var tickStart = 0L

    @JvmStatic fun begin(i: Int) { if (on) start[i] = System.nanoTime() }
    @JvmStatic fun end(i: Int) {
        if (start[i] == 0L) return
        acc[i] += System.nanoTime() - start[i]; start[i] = 0L
    }
    @JvmStatic fun tickBegin() { if (on) tickStart = System.nanoTime() }
    @JvmStatic fun tickEnd() {
        if (tickStart == 0L) return
        val dt = System.nanoTime() - tickStart; tickStart = 0L
        acc[TICK] += dt; win.ticks++; if (dt > win.tickMax) win.tickMax = dt
    }

    private val gpu = GpuTimer(8)
    @JvmStatic fun gpuBegin() { if (on) gpu.begin() }
    @JvmStatic fun gpuEnd() { if (on) gpu.end() }

    /** One second of frames, filled on the game thread, then handed to the writer. */
    private class Window(val startNs: Long) {
        val sum = LongArray(PHASES); val max = LongArray(PHASES)
        val frames = LongArray(4096); var n = 0
        var ticks = 0; var tickMax = 0L
        var gpuSum = 0L; var gpuN = 0; var gpuMax = 0L
        var endNs = 0L
        var place = ""; var screen = ""
        var entities = -1; var chunks = -1; var sections = -1; var packetsIn = 0f; var packetsOut = 0f
    }
    private class Hitch(val endNs: Long, val dt: Long, val phases: LongArray, val place: String, val screen: String)

    private var win = Window(System.nanoTime())
    private var lastFrame = 0L
    private var main: Thread? = null
    private val windows = ConcurrentLinkedQueue<Window>()
    private val hitches = ConcurrentLinkedQueue<Hitch>()
    private var hitchesThisSecond = 0

    /** The start of a frame (Minecraft.runTick): closes the last one. */
    @JvmStatic fun frame() {
        if (!on) { lastFrame = 0L; return }
        val now = System.nanoTime()
        if (main == null) { main = Thread.currentThread(); startThreads() }
        val w = win
        gpu.poll { w.gpuSum += it; w.gpuN++; if (it > w.gpuMax) w.gpuMax = it }
        if (lastFrame != 0L) {
            val dt = now - lastFrame
            for (i in 0 until PHASES) { w.sum[i] += acc[i]; if (acc[i] > w.max[i]) w.max[i] = acc[i] }
            if (w.n < w.frames.size) w.frames[w.n++] = dt
            if (dt >= HITCH_NS && hitchesThisSecond++ < 50) hitches += Hitch(now, dt, acc.copyOf(), place(), screen())
        }
        acc.fill(0L)
        lastFrame = now
        if (now - w.startNs >= 1_000_000_000L) roll(w, now)
    }

    private fun roll(w: Window, now: Long) {
        w.endNs = now
        w.place = place(); w.screen = screen()
        runCatching {
            mc.level?.let { level ->
                w.entities = level.entityCount; w.chunks = level.chunkSource.loadedChunksCount
                w.sections = runCatching { mc.levelRenderer.visibleSections().size }.getOrDefault(-1)
            }
            mc.connection?.connection?.let { w.packetsIn = it.averageReceivedPackets; w.packetsOut = it.averageSentPackets }
        }
        windows += w
        win = Window(now)
        hitchesThisSecond = 0
    }

    private fun place(): String = when {
        mc.level == null -> if (mc.gui.overlay() != null) "loading" else "menu"
        DungeonUtils.inBoss -> "boss"
        DungeonUtils.inDungeons -> "dungeon"
        LocationUtils.isInSkyblock -> "skyblock"
        else -> "lobby"
    }
    private fun screen(): String = mc.gui.screen()?.javaClass?.simpleName ?: ""

    // --- marks (any thread) -----------------------------------------------------------------------------------------------

    private val startMs = ProcessHandle.current().info().startInstant().map { it.toEpochMilli() }.orElse(ManagementFactory.getRuntimeMXBean().startTime)
    private fun tMs() = System.currentTimeMillis() - startMs
    private fun tOf(ns: Long) = tMs() - (System.nanoTime() - ns) / 1_000_000
    private val marks = ConcurrentLinkedQueue<Pair<String, Long>>()
    @Volatile private var skyblockAt = -1L

    /** A moment worth lining the numbers up against: the launch's (LaunchTimeline), a world load, a screen opening. */
    fun mark(name: String) {
        if (!enabled) return
        val t = tMs()
        marks += name to t
        if (name == "sb-world" && skyblockAt < 0) skyblockAt = t
    }

    // --- the game thread, sampled -------------------------------------------------------------------------------------------

    private class Sample(val ns: Long, val bucket: String, val stack: String)
    private val samples = ConcurrentLinkedQueue<Sample>()

    private fun startThreads() {
        val m = main ?: return
        Thread({
            while (true) {
                if (on) runCatching { classify(m.stackTrace) }.getOrNull()?.let { samples += it }
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            }
        }, "dc perf sampler").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        Thread({ writerLoop() }, "dc perf writer").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        listenGc()
    }

    private val infra = arrayOf("java.", "jdk.", "sun.", "kotlin.", "kotlinx.", "org.lwjgl.", "com.mojang.", "net.minecraft.", "it.unimi.", "com.google.",
        "org.joml.", "io.netty.", "org.prismlauncher.", "org.multimc.", "cpw.", "org.spongepowered.", "net.fabricmc.", "org.slf4j.", "org.apache.", "com.llamalad7.", "oshi.", "com.sun.")
    private val mixinHandlers = setOf("handler", "redirect", "modify", "wrapOperation", "wrapWithCondition", "localvar", "constant", "modifyExpressionValue", "modifyReturnValue", "modifyArg", "modifyArgs", "modifyVariable")

    private fun modOf(cls: String, method: String): String? {
        val parts = method.split('$')
        if (parts.size >= 4 && parts[0] in mixinHandlers) return "mixin " + parts[2]
        for ((prefix, label) in arrayOf("com.devgineerclient." to "devgineer", "com.engineerclient." to "engineer", "com.coffeeclient." to "coffee")) {
            if (cls.startsWith(prefix)) return label + "/" + cls.substring(prefix.length).substringBefore('.')
        }
        if (infra.any { cls.startsWith(it) }) return null
        return when {
            cls.startsWith("com.odtheking.") -> "Odin"
            cls.startsWith("net.caffeinemc.") -> "Sodium"
            else -> cls.split('.').let { if (it.size > 3) it.take(3).joinToString(".") else it.dropLast(1).joinToString(".") }
        }
    }

    private fun short(e: StackTraceElement) = e.className.substringAfterLast('.') + "." + e.methodName

    private fun classify(st: Array<StackTraceElement>): Sample? {
        if (st.isEmpty()) return null
        val stack = st.take(10).joinToString(" < ") { short(it) }
        for (e in st) modOf(e.className, e.methodName)?.let { return Sample(System.nanoTime(), it, stack) }
        val waiting = st.take(12).any { it.methodName == "limitDisplayFPS" || it.methodName == "present" || it.methodName == "acquireNextTexture" || it.methodName == "sleep" || it.methodName == "park" }
        val bucket = if (waiting) "waiting" else if (st[0].className.startsWith("org.lwjgl.")) "GL/driver" else "Minecraft"
        return Sample(System.nanoTime(), bucket, stack)
    }

    // --- GC (the JVM's own notifications, on its thread) --------------------------------------------------------------------

    private class Gc(val endNs: Long, val ms: Long, val name: String, val cause: String)
    private val gcs = ConcurrentLinkedQueue<Gc>()

    private fun listenGc() {
        for (bean in ManagementFactory.getGarbageCollectorMXBeans()) {
            val emitter = bean as? NotificationEmitter ?: continue
            emitter.addNotificationListener({ n, _ ->
                runCatching {
                    val data = n.userData as CompositeData
                    val info = data.get("gcInfo") as CompositeData
                    val ms = info.get("duration") as Long
                    gcs += Gc(System.nanoTime(), ms, data.get("gcName") as String, data.get("gcCause") as String)
                }
            }, { it.type == "com.sun.management.gc.notification" }, null)
        }
    }

    // --- the writer ---------------------------------------------------------------------------------------------------------

    private var out: BufferedWriter? = null
    private val recentSamples = ArrayDeque<Sample>()
    private val recentGcs = ArrayDeque<Gc>()
    private val bucketCount = HashMap<String, Int>()
    private var bucketN = 0

    private fun open(): BufferedWriter? {
        out?.let { return it }
        return runCatching {
            val dir = mc.gameDirectory.toPath().resolve("logs").resolve("perf")
            Files.createDirectories(dir)
            val name = "perf-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".jsonl"
            Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith("perf-") }.sorted(Comparator.reverseOrder()).skip(KEEP_FILES - 1L).forEach { Files.deleteIfExists(it) } }
            Files.newBufferedWriter(dir.resolve(name), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }.onFailure { DevgineerClient.logger.warn("[perf] can't open the perf log", it) }.getOrNull()?.also { out = it }
    }

    private fun writerLoop() {
        while (true) {
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            runCatching { drain() }.onFailure { DevgineerClient.logger.warn("[perf] writer", it) }
        }
    }

    private fun drain() {
        val w = open() ?: return
        val now = System.nanoTime()
        while (true) { val s = samples.poll() ?: break; recentSamples.addLast(s); bucketCount.merge(s.bucket, 1, Int::plus); bucketN++ }
        while (true) {
            val g = gcs.poll() ?: break
            recentGcs.addLast(g)
            w.write("{\"gc\":\"${esc(g.name)}\",\"t\":${tOf(g.endNs)},\"ms\":${g.ms},\"cause\":\"${esc(g.cause)}\"}\n")
        }
        while (true) { val (name, t) = marks.poll() ?: break; w.write("{\"mark\":\"${esc(name)}\",\"t\":$t}\n") }
        // A hitch once its samples are surely in (they come every 10 ms).
        while (true) {
            val h = hitches.peek() ?: break
            if (now - h.endNs < 50_000_000L) break
            hitches.poll()
            writeHitch(w, h)
        }
        while (true) { val s = windows.poll() ?: break; writeSecond(w, s) }
        while (recentSamples.isNotEmpty() && now - recentSamples.first().ns > 3_000_000_000L) recentSamples.removeFirst()
        while (recentGcs.isNotEmpty() && now - recentGcs.first().endNs > 3_000_000_000L) recentGcs.removeFirst()
        firstMinute(w)
        w.flush()
    }

    private fun writeHitch(w: BufferedWriter, h: Hitch) {
        val from = h.endNs - h.dt
        val sb = StringBuilder(512)
        sb.append("{\"hitch\":").append(ms(h.dt)).append(",\"t\":").append(tOf(h.endNs)).append(",\"place\":\"").append(h.place).append("\"")
        if (h.screen.isNotEmpty()) sb.append(",\"screen\":\"").append(esc(h.screen)).append("\"")
        sb.append(",\"phases\":{")
        var first = true
        for (i in 0 until PHASES) if (h.phases[i] >= 500_000L) { if (!first) sb.append(','); first = false; sb.append('"').append(NAMES[i]).append("\":").append(ms(h.phases[i])) }
        sb.append('}')
        val gcIn = recentGcs.filter { it.endNs >= from && it.endNs - it.ms * 1_000_000 <= h.endNs }
        if (gcIn.isNotEmpty()) sb.append(",\"gc\":[").append(gcIn.joinToString(",") { "{\"name\":\"${esc(it.name)}\",\"ms\":${it.ms}}" }).append(']')
        val inIt = recentSamples.filter { it.ns in from..h.endNs }
        if (inIt.isNotEmpty()) sb.append(",\"stacks\":[").append(inIt.joinToString(",") { "\"" + esc(it.bucket + ": " + it.stack) + "\"" }).append(']')
        sb.append("}\n")
        w.write(sb.toString())
    }

    // The JVM and the threads, read once a second here.
    private val threadBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val osBean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    private val classBean = ManagementFactory.getClassLoadingMXBean()
    private val jitBean = ManagementFactory.getCompilationMXBean()
    private var allocLast = -1L; private var mainAllocLast = -1L; private var classesLast = -1L; private var jitLast = -1L
    private val gcLast = HashMap<String, LongArray>()
    private val taskCpuLast = HashMap<String, Long>()
    private val ticksPerSecond = 100.0

    private fun writeSecond(w: BufferedWriter, s: Window) {
        val secs = (s.endNs - s.startNs) / 1e9
        val n = s.n.coerceAtLeast(1)
        val sorted = s.frames.copyOf(s.n).also { it.sort() }
        val sb = StringBuilder(1024)
        sb.append("{\"s\":").append(tOf(s.endNs)).append(",\"place\":\"").append(s.place).append('"')
        if (s.screen.isNotEmpty()) sb.append(",\"screen\":\"").append(esc(s.screen)).append('"')
        sb.append(",\"fps\":").append("%.0f".format(s.n / secs))
        if (s.n > 0) {
            sb.append(",\"frame\":").append(ms(sorted.sum() / n)).append(",\"p99\":").append(ms(sorted[((s.n - 1) * 0.99).toInt()])).append(",\"worst\":").append(ms(sorted[s.n - 1]))
            sb.append(",\"hitches\":").append(sorted.count { it >= HITCH_NS })
        }
        sb.append(",\"phases\":{")
        for (i in 0 until PHASES) { if (i > 0) sb.append(','); sb.append('"').append(NAMES[i]).append("\":[").append(ms(s.sum[i] / n)).append(',').append(ms(s.max[i])).append(']') }
        sb.append("},\"ticks\":").append(s.ticks).append(",\"tickWorst\":").append(ms(s.tickMax))
        if (s.gpuN > 0) sb.append(",\"gpu\":[").append(ms(s.gpuSum / s.gpuN)).append(',').append(ms(s.gpuMax)).append(']')
        // The JVM.
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        sb.append(",\"heapMB\":[").append(heap.used shr 20).append(',').append(heap.committed shr 20).append(']')
        runCatching {
            val alloc = threadBean.totalThreadAllocatedBytes
            if (allocLast >= 0) sb.append(",\"allocMBs\":").append("%.0f".format((alloc - allocLast) / secs / 1048576)); allocLast = alloc
            main?.let { val m = threadBean.getThreadAllocatedBytes(it.threadId()); if (mainAllocLast >= 0) sb.append(",\"mainAllocMBs\":").append("%.0f".format((m - mainAllocLast) / secs / 1048576)); mainAllocLast = m }
        }
        val gcParts = ArrayList<String>()
        for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
            val prev = gcLast.getOrPut(gc.name) { longArrayOf(gc.collectionCount, gc.collectionTime) }
            val dc = gc.collectionCount - prev[0]; val dt = gc.collectionTime - prev[1]
            prev[0] = gc.collectionCount; prev[1] = gc.collectionTime
            if (dc > 0) gcParts += "\"${esc(gc.name)}\":[$dc,$dt]"
        }
        if (gcParts.isNotEmpty()) sb.append(",\"gc\":{").append(gcParts.joinToString(",")).append('}')
        val classes = classBean.totalLoadedClassCount
        if (classesLast >= 0 && classes != classesLast) sb.append(",\"classes\":").append(classes - classesLast); classesLast = classes
        runCatching { val jit = jitBean.totalCompilationTime; if (jitLast >= 0 && jit != jitLast) sb.append(",\"jitMs\":").append(jit - jitLast); jitLast = jit }
        osBean?.let { sb.append(",\"cpu\":[").append("%.0f".format(it.processCpuLoad * 100 * Runtime.getRuntime().availableProcessors())).append(',').append("%.0f".format(it.cpuLoad * 100)).append(']') }
        rssMb()?.let { sb.append(",\"rssMB\":").append(it) }
        // Threads' CPU from /proc (% of a core), the busiest, pools of a kind together.
        threadCpu(secs).takeIf { it.isNotEmpty() }?.let { t -> sb.append(",\"threads\":{").append(t.take(6).joinToString(",") { "\"${esc(it.first)}\":${"%.0f".format(it.second)}" }).append('}') }
        // Where the game thread was this second (sampled).
        if (bucketN > 0) {
            sb.append(",\"where\":{").append(bucketCount.entries.sortedByDescending { it.value }.take(8).joinToString(",") { "\"${esc(it.key)}\":${it.value * 100 / bucketN}" }).append('}')
            bucketCount.clear(); bucketN = 0
        }
        if (s.entities >= 0) sb.append(",\"entities\":").append(s.entities).append(",\"chunks\":").append(s.chunks).append(",\"sections\":").append(s.sections)
        sb.append(",\"pktIn\":").append("%.0f".format(s.packetsIn)).append(",\"pktOut\":").append("%.0f".format(s.packetsOut))
        sb.append("}\n")
        w.write(sb.toString())
        firstFrames(s)
    }

    private fun rssMb(): Long? = runCatching { Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong() / 1024 }.getOrNull()

    private fun threadCpu(secs: Double): List<Pair<String, Double>> = runCatching {
        val out = HashMap<String, Double>()
        val seen = HashSet<String>()
        Files.list(Path.of("/proc/self/task")).use { tasks ->
            for (task in tasks) {
                val stat = runCatching { Files.readString(task.resolve("stat")) }.getOrNull() ?: continue
                val name = stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')'))
                val f = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                val cpu = f[11].toLong() + f[12].toLong()
                val key = task.fileName.toString()
                seen += key
                val prev = taskCpuLast.put(key, cpu) ?: continue
                val pool = name.replace(Regex("[-# ]?\\d+$"), "")
                out.merge(pool, (cpu - prev) / ticksPerSecond / secs * 100, Double::plus)
            }
        }
        taskCpuLast.keys.retainAll(seen)
        out.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }.getOrDefault(emptyList())

    // --- the first minute after reaching Skyblock ---------------------------------------------------------------------------

    private val firstFrameTimes = ArrayList<Long>()
    private var firstDone = false

    private fun firstFrames(s: Window) {
        if (firstDone || skyblockAt < 0 || tOf(s.endNs) < skyblockAt) return
        for (i in 0 until s.n) firstFrameTimes += s.frames[i]
    }

    private fun firstMinute(w: BufferedWriter) {
        if (firstDone || skyblockAt < 0 || tMs() - skyblockAt < 61_000 || firstFrameTimes.isEmpty()) return
        firstDone = true
        val f = firstFrameTimes.toLongArray().also { it.sort() }
        val k = (f.size / 100).coerceAtLeast(1)
        val low1 = 1e9 / (f.takeLast(k).average())
        val line = "frames %d · avg %s ms · 1%% low %.0f fps · worst %s ms · hitches ≥20 ms %d, ≥50 ms %d, ≥100 ms %d".format(
            f.size, ms(f.sum() / f.size), low1, ms(f.last()), f.count { it >= 20_000_000L }, f.count { it >= 50_000_000L }, f.count { it >= 100_000_000L })
        w.write("{\"first\":\"${esc(line)}\",\"skyblockAt\":$skyblockAt}\n")
        runCatching {
            Files.writeString(mc.gameDirectory.toPath().resolve("logs").resolve("perf").resolve("summary.log"),
                "${LocalDateTime.now().withNano(0)}  skyblock at ${"%.1f".format(skyblockAt / 1000.0)} s · first minute: $line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private fun ms(ns: Long) = "%.2f".format(ns / 1e6)
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
