package com.devgineerclient.practice

import com.devgineerclient.DevgineerClient
import com.devgineerclient.DevgineerClient.mc
import com.engineerclient.p3sim.P3Sim
import com.devgineerclient.practice.I4Geometry.Board
import com.devgineerclient.practice.I4Geometry.Lattice
import com.devgineerclient.practice.I4Geometry.Shooter
import com.devgineerclient.practice.I4Geometry.StanceGeometry
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.itemId
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.util.Mth
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.atan
import kotlin.math.tan

/**
 * i4 (the Arrows Device): paints the screen pixels where putting your crosshair lands every arrow of the aim to fire
 * NOW (#00FF00), and the aim to move to NEXT (cyan, a pre-aim), for your bow (held, else the first in your hotbar,
 * else the last one seen) and Terror (Hydra Strike) stacks.
 *
 * Three parts:
 * - [I4Geometry]: every aim's spots for where you stand, what you hold and your mouse (a stance), worked out off the
 *   client thread when the stance changes (one worker thread splitting the work over a pool of [PARTS]; the other
 *   sneak stance next, and the last few kept, so a sneak or a stack flicker costs nothing); the old one stays drawn
 *   until the new one is in.
 * - [I4Solver] and [I4Arrows]: the board as you can know it, every arrow on its way to it (from its packets, the
 *   tick it spawns), and NOW and NEXT picked from the stance's aims, on the client thread at each event, on the
 *   board's clock ([clock]).
 * - [I4Paint]: NOW and NEXT drawn in the world pass, over every block and under every screen.
 *
 * Auto Zoom narrows the FOV while you look at the board from the plate. The server aims a shortbow shot with the
 * newest look it has when the shot fires, so hold the spot until the arrows are out. Odin's own aim cubes are hidden
 * while this is on (ArrowsDeviceAimMixin).
 */
object I4Aims : Module(
    name = "i4 Aim",
    category = Category.custom("Devgineer Client"),
    description = "i4: paints where to aim now (green) and next (cyan), from a simulated best strategy.",
    key = null,
) {
    private val minChance by NumberSetting("Min Hit Chance", 90, 50, 100, 1, unit = "%", desc = "Spots are drawn when every arrow of the aim lands on its cell at least this often (1.8's aim noise on the main arrow, which Hydra Strike's inherit).")
    private val showNext by BooleanSetting("Show Next Aim", true, desc = "Also paint (cyan) where to move while the cooldown runs: the aim after the one you fire now.")
    private val autoZoom by BooleanSetting("Auto Zoom", true, desc = "Narrow your FOV while you look at the board from the plate, so each mouse count is a bigger step on screen. Mouse speed in degrees doesn't change.")
    private val zoom by NumberSetting("Zoom", 2.0, 1.25, 4.0, 0.25, unit = "x", desc = "How far Auto Zoom magnifies.").withDependency { autoZoom }

    private val BOWS = setOf("TERMINATOR", "MOSQUITO_BOW", "ITEM_SPIRIT_BOW", "JUJU_SHORTBOW", "ARTISANAL_SHORTBOW", "LAST_BREATH")
    private val PLATE = Vec3(63.5, 127.0, 35.5)
    private val STACKS = Regex("(\\d+)⁑")
    private val CODES = Regex("§.")
    private val DEVICE_LINE = Regex("^(.{1,16}) completed a device! \\(\\d/\\d\\)$")
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

    /**
     * Hydra Strike stacks, from the action bar (Hypixel's and P3 Sim's: `N⁑` every 10 ticks while you have any).
     * Other action bars leave it; Hypixel sends a blank one now and then even at 10, so 0 is when no `N⁑` came for
     * [STACKS_GONE] ticks (P3 Sim's blank one at 0 is taken at once).
     */
    private var stacks = 0
    private var stacksAt = Int.MIN_VALUE / 2
    private const val STACKS_GONE = 40

    private val solver = I4Solver()
    private val arrows = I4Arrows(solver)
    /** Arrows spawned in front of the board (entity id to client tick), so motion packets of others are dropped on the network thread. */
    private val tracked = ConcurrentHashMap<Int, Int>()

    private var ticks = 0
    private var awayTicks = 0
    private var board: Board? = null

    /**
     * The board's clock: Hypixel's server ticks (its unbundled pings, one a tick: Odin's server tick), counted on the
     * client thread in the order they came with the board's and the arrows' packets, so lag stretches nothing; client
     * ticks only while no ping has come for [NO_PINGS] ticks (P3 Sim pings every server tick too).
     */
    private var clock = 0
    private var pingAt = Int.MIN_VALUE / 2
    private const val NO_PINGS = 100

    /** Runs [f] on the client thread: at once when already there (a bundle's packets keep their place), else queued behind what came before. */
    private inline fun onClient(crossinline f: () -> Unit) { if (mc.isSameThread) f() else mc.execute { f() } }

    /** Read by ArrowsDeviceAimMixin: Odin's aim cubes are hidden while this draws its own. */
    @JvmStatic fun replacesOdinAims(): Boolean = enabled

    private var lastBow = "TERMINATOR"

    /** The bow the volley is worked out for: the one you hold, else the first in your hotbar, else the last one found. */
    private fun bow(): String {
        val p = mc.player ?: return lastBow
        val found = p.mainHandItem.itemId.takeIf { it in BOWS }
            ?: (0 until 9).firstNotNullOfOrNull { p.inventory.getItem(it).itemId.takeIf { id -> id in BOWS } }
        if (found != null) lastBow = found
        return lastBow
    }

    // ------------------------------------------------------------------ the stance

    /** Everything a stance's geometry depends on (position in mm, held still 2 ticks). */
    private data class StanceKey(val board: Int, val bow: String, val stacks: Int, val crouch: Boolean, val x: Long, val y: Long, val z: Long,
                                 val step: Double, val min: Double, val yaw0: Double, val pitch0: Double)

    /** The last few stances worked out (a sneak or a 10/9/10 stack flicker costs nothing). */
    private val cache = object : LinkedHashMap<StanceKey, StanceGeometry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StanceKey, StanceGeometry>) = size > 6
    }
    /** The geometry drawn and picked from (client thread). */
    private var geo: StanceGeometry? = null

    /** A stance being worked out: the one you need, or one worked out ahead ([ahead]: the other sneak stance, or a warm-up) that gives way to it. */
    private class Job(val key: StanceKey?, val ahead: Boolean) { @Volatile var cancelled = false }
    /** The job on the worker (one at a time; client thread). */
    private var job: Job? = null
    private val finished = ConcurrentLinkedQueue<Pair<Job, StanceGeometry>>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ec-i4-geometry").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    /**
     * The stance's rows are split over this many threads, busy only while a stance is worked out. Offline on the
     * game's JRE: one thread takes about 80-350 ms (Mosquito 0 to Terminator 10); four bring a warm stance to about
     * 20-100 ms, but in about 4 JVM sessions in 10 the JIT settles on slower code (150-170 ms for a Terminator at 10
     * stacks); six keep that under about 130 ms. The first one of a session runs cold (about 200 ms for a Terminator at
     * 10 stacks), so one is worked out ahead at Goldor's start ([warm]).
     */
    private const val PARTS = 6
    private val parts = Executors.newFixedThreadPool(PARTS) { Thread(it, "ec-i4-geometry-part").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    /** The mouse lattice: kept while your look stays on it (whole counts), moved when it drifts off. */
    private var lattice: Lattice? = null
    private var lastPos: Triple<Long, Long, Long>? = null
    private var stillTicks = 0
    private var stablePos: Triple<Long, Long, Long>? = null
    /** Client ticks since you were last on the plate. */
    private var offPlateTicks = 0
    private val PLATE_BLOCK = BlockPos(63, 127, 35)

    /** You stand on the plate: your box over its block, feet on it (as I4Complete). */
    private fun onPlate(): Boolean {
        val b = mc.player?.boundingBox ?: return false
        return b.maxX > PLATE_BLOCK.x && b.minX < PLATE_BLOCK.x + 1 && b.maxZ > PLATE_BLOCK.z && b.minZ < PLATE_BLOCK.z + 1 &&
            b.minY >= PLATE_BLOCK.y - 0.01 && b.minY < PLATE_BLOCK.y + 0.25
    }

    /** How far (in counts, 0-0.5) your look sits off [l]: whole mouse counts keep it on. */
    private fun offLattice(l: Lattice, yaw: Float, pitch: Float): Double {
        fun off(d: Double) = Math.abs(d - Math.rint(d))
        return maxOf(off(Mth.wrapDegrees(yaw - l.yaw0) / l.step), off((pitch - l.pitch0) / l.step))
    }

    /** Takes the worker's finished stances in (every tick, at the board or not, so the worker is free again). */
    private fun drain() {
        while (true) {
            val (j, g) = finished.poll() ?: break
            if (job === j) job = null
            val k = j.key ?: continue
            // Given way before it was done (a finished one is kept, as is a failed one, so it isn't tried again and again).
            if (j.cancelled && g.evaluated == 0) continue
            cache[k] = g
            DevgineerClient.logger.info("[dc] i4 stance %.0fms%s %s %d stacks %s, %d volleys flown: %s".format(g.millis, if (j.ahead) " (ahead)" else "", k.bow, k.stacks,
                if (k.crouch) "sneaking" else "standing", g.evaluated, g.aims.joinToString(" ") { "${it}=${it.spots}@%.2f".format(it.bestChance) }))
        }
    }

    /** The current stance is looked up, and the next one needed is started. */
    private fun updateStance() {
        val p = mc.player ?: return
        val b = board ?: return
        val step = I4Geometry.mouseStep(mc.options.sensitivity().get())
        var l = lattice
        if (l == null || l.step != step || offLattice(l, p.yRot, p.xRot) > 0.3) {
            l = Lattice(Mth.wrapDegrees(p.yRot.toDouble()), p.xRot.toDouble(), step)
            lattice = l
        }
        val pos = p.position()
        val q = Triple(Math.round(pos.x * 1000), Math.round(pos.y * 1000), Math.round(pos.z * 1000))
        if (q == lastPos) stillTicks++ else { stillTicks = 0; lastPos = q }
        if (stillTicks >= 2) stablePos = q
        val sp = stablePos ?: return
        val key = StanceKey(System.identityHashCode(b), bow(), stacks, p.isShiftKeyDown, sp.first, sp.second, sp.third, step, minChance / 100.0, l.yaw0, l.pitch0)
        cache[key]?.let { if (geo !== it) { geo = it; replan() } }
        val running = job
        if (running != null) {
            // Something worked out ahead gives way to the stance you need.
            if (running.ahead && running.key != key && key !in cache) running.cancelled = true
            return
        }
        val want = if (key !in cache) key else key.copy(crouch = !key.crouch).takeIf { it !in cache } ?: return
        start(Job(want, ahead = want != key), b, Shooter(want.bow, Vec3(want.x / 1000.0, want.y / 1000.0, want.z / 1000.0), want.crouch, want.stacks),
            Lattice(want.yaw0, want.pitch0, want.step), want.min)
    }

    private fun start(j: Job, b: Board, shooter: Shooter, lat: Lattice, min: Double) {
        job = j
        worker.execute {
            val g = try { I4Geometry.stance(b, shooter, lat, min, parts, PARTS) { j.cancelled } } catch (t: Throwable) {
                DevgineerClient.logger.error("[dc] i4 stance failed", t)
                StanceGeometry(b, shooter, lat, min, emptyList(), 0, 0.0)
            }
            finished += j to g
        }
    }

    /**
     * Works one stance out and throws it away, so the geometry code is compiled before you reach the plate (cold,
     * the first stance takes about twice as long): at Goldor's start, for the plate's middle with the bow you have.
     */
    private fun warm() {
        drain()
        if (job != null) return
        val step = I4Geometry.mouseStep(mc.options.sensitivity().get())
        val shooter = Shooter(bow(), PLATE, false, 10)
        val look = I4Geometry.lookAt(shooter.eye, Vec3(66.5, 128.5, I4Geometry.FACE))
        start(Job(null, ahead = true), I4Geometry.DEFAULT, shooter, Lattice(look.first, look.second, step), minChance / 100.0)
    }

    // ------------------------------------------------------------------ picking and drawing

    private var pick = I4Solver.Pick(null, null)
    /** What is drawn, and what it was built for (its geometry, null when nothing is drawn, and its NOW and NEXT). */
    private var mesh: I4Paint.Mesh? = null
    private var meshGeo: StanceGeometry? = null
    private var meshNow: I4Geometry.Aim? = null
    private var meshNext: I4Geometry.Aim? = null

    /** NOW and NEXT for how the board is; the paint is rebuilt when they change. */
    private fun replan() {
        val t0 = System.nanoTime()
        val g = geo
        val p = solver.plan(g, showNext)
        stats.plan(System.nanoTime() - t0)
        pick = p
        val drawn = if (g != null && atBoardCached()) g else null
        if (p.now === meshNow && p.next === meshNext && meshGeo === drawn) return
        meshNow = p.now; meshNext = p.next; meshGeo = drawn
        mesh = drawn?.let { I4Paint.build(it.lattice, p.now, p.next) }
    }

    /** Draw and pick timings, for the log ([clear] after each line). */
    private class Timings {
        var since = 0; var frames = 0; var drawNs = 0L; var drawMax = 0L; var plans = 0; var planNs = 0L; var planMax = 0L; var volleys = 0
        fun frame(ns: Long) { frames++; drawNs += ns; if (ns > drawMax) drawMax = ns }
        fun plan(ns: Long) { plans++; planNs += ns; if (ns > planMax) planMax = ns }
        fun clear() { frames = 0; drawNs = 0; drawMax = 0; plans = 0; planNs = 0; planMax = 0; volleys = 0; since = ticks }
        override fun toString() = "draw %d frames max %.3f ms mean %.3f ms; pick max %d us mean %d us".format(frames, drawMax / 1e6,
            if (frames > 0) drawNs / 1e6 / frames else 0.0, planMax / 1000, if (plans > 0) planNs / 1000 / plans else 0)
    }
    /** Over the attempt (its summary line), and over the last 10 s at the board (a line of its own). */
    private val attempt = Timings()
    private val rolling = Timings()
    private object Stats {
        fun frame(ns: Long) { attempt.frame(ns); rolling.frame(ns) }
        fun plan(ns: Long) { attempt.plan(ns); rolling.plan(ns) }
    }
    private val stats = Stats

    /** One line for an attempt that ended ([c]: its counts), with its draw and pick timings. */
    private fun summary(why: String, c: I4Solver.Counts) {
        if (c.lights > 0 || attempt.volleys > 0) DevgineerClient.logger.info(
            "[dc] i4 attempt %s: %.1fs, %d lights %d blues, %d volleys %d strikes %d missed; %s; stance %s".format(why, (ticks - attempt.since) / 20.0,
                c.lights, c.blues, attempt.volleys, c.strikes, c.rollbacks, attempt, geo?.let { "%.0fms".format(it.millis) } ?: "none"))
        attempt.clear()
    }

    /** The solver's attempt that a restart just ended, if any, logged. */
    private fun ended() { solver.ended?.let { solver.ended = null; summary("ended (a new one started)", it) } }

    private fun reset() {
        if (solver.started && !solver.complete) summary("reset", solver.counts)
        solver.reset(); arrows.clear(); tracked.clear(); pick = I4Solver.Pick(null, null); mesh = null; meshGeo = null; meshNow = null; meshNext = null
    }

    /** Everything read or worked out, as on a new world (the module was off: its events weren't seen). */
    private fun forget() {
        reset(); board = null; geo = null; cache.clear(); lattice = null; stablePos = null; lastPos = null; stillTicks = 0
        job?.cancelled = true
    }

    override fun onEnable() { forget(); super.onEnable() }
    override fun onDisable() { super.onDisable(); forget() }

    /** You're at the plate with the board there (all 9 cells blue terracotta or emerald). */
    private fun atBoard(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        if (p.position().distanceTo(PLATE) > 3.0) return false
        return I4Geometry.CELLS.all { val s = level.getBlockState(it); s.`is`(Blocks.BLUE_TERRACOTTA) || s.`is`(Blocks.EMERALD_BLOCK) }
    }

    /** The board's real blocks: every collision box from x 62-70, y 124-132, z 49-51. */
    private fun readBoard(): Board {
        val level = mc.level ?: return I4Geometry.DEFAULT
        val boxes = ArrayList<AABB>(); val cellOf = ArrayList<Int>()
        for (x in 62..70) for (y in 124..132) for (z in 49..51) {
            val pos = BlockPos(x, y, z)
            val cell = I4Geometry.CELLS.indexOf(pos)
            for (b in level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) { boxes += b.move(pos); cellOf += cell }
        }
        if (I4Geometry.CELLS.indices.any { it !in cellOf }) return I4Geometry.DEFAULT
        DevgineerClient.logger.info("[dc] i4 board: ${boxes.size} boxes: " + boxes.indices.joinToString(" ") { i ->
            val b = boxes[i]; "${cellOf[i]}:[%.4f %.4f %.4f %.4f %.4f %.4f]".format(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ) })
        return Board(boxes, cellOf.toIntArray())
    }

    private fun onArrow(id: Int, x: Double, y: Double, z: Double, v: Vec3, data: Int) {
        val b = board ?: return
        val me = mc.player?.id ?: return
        val t0 = System.nanoTime()
        val strikes = solver.counts.strikes
        arrows.added(id, x, y, z, v.x, v.y, v.z, data, me, clock, b, bow(), stacks, P3Sim.inSim)
        replan()
        val volley = arrows.lastVolley
        if (volley != null) {
            arrows.lastVolley = null
            attempt.volleys++
            DevgineerClient.logger.info("[dc] i4 volley $volley (%d us) -> now=${pick.now} next=${pick.next} ${solver.describe()}".format((System.nanoTime() - t0) / 1000))
        } else if (solver.counts.strikes != strikes) logOther(id)
    }

    /** Another arrow's (a teammate's, or a Duplex copy's) strike, once known. */
    private fun logOther(id: Int) {
        DevgineerClient.logger.info("[dc] i4 arrow id=$id t=$clock -> now=${pick.now} next=${pick.next} ${solver.describe()}")
    }

    init {
        on<LevelEvent.Load> { forget() }
        // Its pipeline is registered with the game's own, before the shaders load.
        I4Paint.init()

        on<TickEvent.Server> { onClient { clock++; pingAt = ticks } }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (!overlay) return@onReceive
            val text = content.string.replace(CODES, "")
            val n = STACKS.find(text)?.groupValues?.get(1)?.toIntOrNull()
            if (n != null) onClient { stacks = n.coerceIn(0, 10); stacksAt = ticks }
            else if (text.isBlank()) onClient { if (P3Sim.inSim) stacks = 0 }
        }

        onReceive<ClientboundSystemChatPacket>(ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CODES, "")
            if (msg == GOLDOR_START) onClient { reset(); warm() }
            DEVICE_LINE.matchEntire(msg)?.let { m ->
                onClient {
                    if (m.groupValues[1] == mc.player?.gameProfile?.name && atBoard()) { solver.completed(clock); replan(); summary("complete", solver.counts) }
                }
            }
        }

        // Network thread (or the client's, inside a bundle): only arrows that can reach the board are passed on, in order.
        onReceive<ClientboundAddEntityPacket>(ignoreCancelled = true) {
            if (type != EntityType.ARROW && type != EntityType.SPECTRAL_ARROW) return@onReceive
            if (!I4Arrows.watched(x, y, z)) return@onReceive
            val arrow = id; val px = x; val py = y; val pz = z; val v = movement; val owner = data
            tracked[arrow] = ticks
            onClient { if (enabled) DevgineerClient.safely("i4 arrow") { onArrow(arrow, px, py, pz, v, owner) } }
        }

        onReceive<ClientboundSetEntityMotionPacket>(ignoreCancelled = true) {
            val id = id()
            if (!tracked.containsKey(id)) return@onReceive
            val v = movement()
            onClient {
                val b = board ?: return@onClient
                if (enabled) DevgineerClient.safely("i4 arrow") {
                    val strikes = solver.counts.strikes
                    arrows.motion(id, v.x, v.y, v.z, clock, b, P3Sim.inSim); replan()
                    if (solver.counts.strikes != strikes) logOther(id)
                }
            }
        }

        on<BlockUpdateEvent> {
            // P3 Sim's own server changes its chunks on its thread too: only the client's board counts.
            if (!mc.isSameThread) return@on
            val i = I4Geometry.CELLS.indexOf(pos)
            if (i < 0) return@on
            DevgineerClient.safely("i4 block") {
                if (updated.`is`(Blocks.EMERALD_BLOCK)) solver.emerald(i, clock)
                else if (old.`is`(Blocks.EMERALD_BLOCK) && updated.`is`(Blocks.BLUE_TERRACOTTA)) solver.blue(i, clock)
                else return@safely
                ended()
                // An attempt's summary covers it from its first light.
                if (solver.counts.lights == 1 && solver.counts.blues == 0 && updated.`is`(Blocks.EMERALD_BLOCK)) attempt.clear()
                replan()
            }
        }

        on<TickEvent.End> {
            ticks++
            if (ticks - pingAt > NO_PINGS) clock++
            drain()
            if (tracked.isNotEmpty()) tracked.entries.removeIf { ticks - it.value > 60 }
            if (!atBoard()) {
                mesh = null; meshGeo = null
                // Away 2 s: the attempt is over (the board itself stays read until the world changes).
                if (++awayTicks > 40 && solver.started) reset()
                return@on
            }
            awayTicks = 0
            DevgineerClient.safely("i4 aims") {
                if (board == null) { board = readBoard(); attempt.clear(); rolling.clear() }
                if (stacks > 0 && ticks - stacksAt > STACKS_GONE) stacks = 0
                // The plate lets go up to 10 server ticks after you step off (a little more for lag).
                offPlateTicks = if (onPlate()) 0 else offPlateTicks + 1
                solver.offPlate = offPlateTicks in 1..15
                updateStance()
                solver.tick(clock)
                replan()
                // Every 10 s at the board: the draw and pick timings (the attempt's own counts wait for its end).
                if (ticks - rolling.since >= 200) {
                    DevgineerClient.logger.info("[dc] i4 at the board: %s; stance %s".format(rolling, geo?.let { "%.0fms".format(it.millis) } ?: "none"))
                    rolling.clear()
                }
            }
        }

        // The world's pass, after everything in it (translucent blocks and particles too): over every block, under
        // every screen. With Fabulous graphics (improved transparency) translucent blocks, particles and weather are
        // composited after this pass and would tint it; the default and Fancy modes draw them before.
        LevelRenderEvents.END_MAIN.register { ctx ->
            val m = mesh ?: return@register
            if (!enabled || !mc.options.cameraType.isFirstPerson || mc.cameraEntity !== mc.player) return@register
            val t0 = System.nanoTime()
            DevgineerClient.safely("i4 paint") { I4Paint.draw(ctx, m) }
            stats.frame(System.nanoTime() - t0)
        }
    }

    private fun atBoardCached(): Boolean = board != null && (mc.player?.position()?.distanceTo(PLATE) ?: 99.0) <= 3.0

    private const val ZOOM_SECONDS = 0.12f
    private var zoomT = 0f
    private var zoomAt = 0L
    private var zoomed = false

    /**
     * Camera.calculateFov's result, narrowed by up to [zoom] while you're on the plate looking at
     * the board (with a margin, wider once zoomed so the edge doesn't flicker). Eased in and out over [ZOOM_SECONDS].
     */
    @JvmStatic fun zoomFov(fov: Float): Float {
        // Not on a teammate's POV feed; the restore pass after the feeds is your camera again and keeps the zoom.
        if (com.engineerclient.pov.PovCapture.capturing && mc.cameraEntity !== mc.player) return fov
        val now = System.nanoTime()
        val dt = if (zoomAt == 0L) 0f else ((now - zoomAt) / 1e9f).coerceAtMost(0.1f)
        zoomAt = now
        zoomed = enabled && autoZoom && atBoardCached() && lookingAtBoard(if (zoomed) 1.5 else 0.75)
        zoomT = (zoomT + (if (zoomed) dt else -dt) / ZOOM_SECONDS).coerceIn(0f, 1f)
        if (zoomT <= 0f) return fov
        val k = 1.0 + (zoom - 1.0) * zoomT * zoomT * (3 - 2 * zoomT)
        return Math.toDegrees(2 * atan(tan(Math.toRadians(fov / 2.0)) / k)).toFloat()
    }

    /** Your crosshair's ray meets the board's face within [margin] blocks of the cells (x 64-69, y 126-131). */
    private fun lookingAtBoard(margin: Double): Boolean {
        val p = mc.player ?: return false
        val eye = p.eyePosition
        val look = p.lookAngle
        if (look.z <= 1e-3) return false
        val t = (I4Geometry.FACE - eye.z) / look.z
        val x = eye.x + look.x * t; val y = eye.y + look.y * t
        return x in 64.0 - margin..69.0 + margin && y in 126.0 - margin..131.0 + margin
    }
}
