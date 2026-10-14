package com.devgineerclient.practice

import com.devgineerclient.DevgineerClient
import com.devgineerclient.DevgineerClient.mc
import com.devgineerclient.practice.I4Geometry.Board
import com.devgineerclient.practice.I4Geometry.Lattice
import com.devgineerclient.practice.I4Geometry.Shooter
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
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
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * i4 (the Arrows Device): paints the screen pixels where putting your crosshair lands every arrow of the aim to fire
 * NOW (#00FF00), and the aim to move to NEXT (cyan, a pre-aim), for your bow (held, else the first in your hotbar) and
 * Terror (Hydra Strike) stacks ([I4Geometry]). Each pixel is lit when the nearest spot your mouse can reach from there
 * is one.
 *
 * How the board works (Hypixel recordings, ~/Cluade/maxor-work/i4lag): the device counts a hit the tick the arrow
 * lands and draws the next target then, uniformly from the cells not yet counted; only its emerald waits, for the
 * attempt's 10-server-tick grid (1-10 ticks). A hit on that not-yet-shown target counts too. A hit on the shown one
 * goes blue 1 tick later. Arrows tick in spawn order (main, then the side arrow at +x, then -x).
 *
 * The policy (simulated against your recordings, ~/Cluade/maxor-work/i4sim: 4.1 s from the first emerald, against
 * 5.4 s today) keeps, per cell, the chance it is still uncounted ([u]) and the chance it is the target now ([pi]):
 * - Before the first emerald: the middle row (strikes don't count yet, but you're on the board and in rhythm).
 * - A shown target none of your arrows is on its way to: its best aim, one volley (a Hydra row on its row).
 * - Otherwise (your volley is on its way to it, or nothing is shown): the aim that covers the most chance of being the
 *   target, a little in favour of the row you're on and the middle row. Your own arrows lower the chance of each cell
 *   they strike, so the guesses move on.
 * Never hold fire: NEXT is where to go while the cooldown runs. Spots under Min Hit Chance (1.8's aim noise on the
 * main and Hydra arrows) aren't painted.
 *
 * Drawn in the HUD under the crosshair: above the world, under any open screen. Auto Zoom narrows the FOV while you
 * look at the board. The server aims a shortbow shot with the newest look it has when the shot fires, so hold the
 * spot until the arrows are out. Odin's own aim cubes are hidden while this is on (ArrowsDeviceAimMixin).
 */
object I4Aims : Module(
    name = "i4 Aim",
    category = Category.custom("Devgineer Client", 860, 10),
    description = "i4: paints where to aim now (green) and next (cyan), from a simulated best strategy.",
    key = null,
) {
    private val minChance by NumberSetting("Min Hit Chance", 90, 50..100, 1, unit = "%", desc = "Spots are drawn when every arrow of the aim lands on its cell at least this often. A Terminator's side arrows have no aim noise (always 100%); the main arrow and Hydra Strike's carry 1.8's.")
    private val showNext by BooleanSetting("Show Next Aim", true, desc = "Also paint (cyan) where to move while the cooldown runs: the aim after the one you fire now.")
    private val autoZoom by BooleanSetting("Auto Zoom", true, desc = "Narrow your FOV while you look at the board from the plate, so each mouse count is a bigger step on screen. Mouse speed in degrees doesn't change.")
    private val zoom by NumberSetting("Zoom", 2.0, 1.25..4.0, 0.25, unit = "x", desc = "How far Auto Zoom magnifies.").withDependency { autoZoom }

    private val BOWS = setOf("TERMINATOR", "MOSQUITO_BOW", "ITEM_SPIRIT_BOW", "JUJU_SHORTBOW", "ARTISANAL_SHORTBOW", "LAST_BREATH")
    private val PLATE = Vec3(63.5, 127.0, 35.5)
    private val STACKS = Regex("(\\d+)⁑")
    private val CODES = Regex("§.")
    private val DEVICE_LINE = Regex("^(.{1,16}) completed a device! \\(\\d/\\d\\)$")

    /** Hydra Strike stacks, from the action bar (Hypixel's and P3 Sim's: `N⁑`, nothing at 0). */
    @Volatile private var stacks = 0
    /** The lit cell (index), or -1. */
    private var lit = -1
    /** Cells hit since the board started (gone emerald to blue). A hit on a target before it lit leaves no trace. */
    private val marked = HashSet<Int>()
    /** Arrows of yours in the air: entity id to (cell, the client tick it's gone by). */
    private val flying = HashMap<Int, Pair<Int, Int>>()
    private var ticks = 0
    private var awayTicks = 0
    private var board: Board? = null
    /** Your "completed a device!" came while at the board: nothing left to aim at until a target lights again. */
    private var complete = false

    /** An emerald has shown this attempt: before it, strikes don't count. */
    private var started = false
    /** Per cell: the chance it is still uncounted, and the chance it is the target now. */
    private val u = DoubleArray(9) { 1.0 }
    private val pi = DoubleArray(9)
    /** Your arrow is on its way to the shown cell: if it's still shown by this tick, the arrow missed. */
    private var confirmCell = -1
    private var confirmBy = 0
    /** Per-cell strike rates of a row volley, R/C/L column (x 64/66/68; recordings), for volleys not yet in the air. */
    private val ROW_Q = doubleArrayOf(0.67, 0.73, 0.54)

    /** What is painted: what it was worked out for, and its aims. */
    private class Result(val key: String, val eye: Vec3, val lattice: Lattice, val now: I4Geometry.Aim?, val next: I4Geometry.Aim?) {
        val aims get() = listOfNotNull(now, next)
    }
    @Volatile private var result: Result? = null

    /**
     * Everything that depends only on where you stand and what you hold: the coarse scan. Each aim's spots depend on
     * the mouse lattice too ([Grid]): worked out once per lattice, so a new target or a hit only picks among them.
     */
    private class Scan(val key: String, val board: Board, val shooter: Shooter, val min: Double, val coarse: List<I4Geometry.Coarse>) {
        @Volatile var grid: Grid? = null
    }

    /** One mouse lattice's aims, by their cells. */
    private class Grid(val sc: Scan, val lattice: Lattice) {
        val fine = java.util.concurrent.ConcurrentHashMap<Set<Int>, java.util.Optional<I4Geometry.Aim>>()
        // Not computeIfAbsent: fine() runs its own parallel stream, so a pool worker waiting on it can steal a sibling
        // aim() and re-enter the map on the same bin ("Recursive update"). Two threads may work out one aim; both agree.
        fun aim(c: I4Geometry.Coarse): I4Geometry.Aim? {
            fine[c.cells]?.let { return it.orElse(null) }
            val worked: java.util.Optional<I4Geometry.Aim> = java.util.Optional.ofNullable(I4Geometry.fine(sc.board, sc.shooter, lattice, sc.min, c))
            return (fine.putIfAbsent(c.cells, worked) ?: worked).orElse(null)
        }
        fun cached(c: I4Geometry.Coarse) = fine.containsKey(c.cells)
    }
    @Volatile private var scan: Scan? = null
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ec-i4-aims").apply { isDaemon = true } }
    /** The solver's own few threads (its parallel streams run here, not on every core), so the game keeps its frames. */
    private val pool = java.util.concurrent.ForkJoinPool(3)
    private fun <T> inPool(f: () -> T): T = pool.submit(java.util.concurrent.Callable { f() }).get()

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

    private fun reset() {
        lit = -1; marked.clear(); flying.clear(); result = null; scan = null; complete = false; drawn = null
        started = false; u.fill(1.0); pi.fill(0.0); confirmCell = -1
    }

    /** A strike on [c] that counts with [q] if [c] is the target: the target is then redrawn from the uncounted cells. */
    private fun strike(u: DoubleArray, pi: DoubleArray, c: Int, q: Double) {
        val h = q * pi[c]
        if (h <= 0.0) return
        pi[c] -= h; u[c] -= h
        val rest = (0 until 9).filter { it != c }.sumOf { u[it] }
        if (rest > 0) for (o in 0 until 9) if (o != c) pi[o] += h * u[o] / rest
        val sum = u.sum()
        if (sum in 1e-9..1.0) for (o in 0 until 9) u[o] /= sum
    }

    /** The target is redrawn uniformly from the uncounted cells. */
    private fun redraw() {
        val sum = u.sum()
        if (sum < 1.0 && sum > 1e-9) for (o in 0 until 9) u[o] /= sum
        val s2 = u.sum()
        for (o in 0 until 9) pi[o] = if (s2 > 0) u[o] / s2 else 0.0
    }

    /** You're at the plate with the board there (all 9 cells blue terracotta or emerald). */
    private fun atBoard(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        if (p.position().distanceTo(PLATE) > 3.0) return false
        return I4Geometry.CELLS.all { val s = level.getBlockState(it); s.`is`(Blocks.DYED_TERRACOTTA.blue()) || s.`is`(Blocks.EMERALD_BLOCK) }
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

    private fun rowOf(cells: Set<Int>): Int = cells.map { it / 3 }.distinct().singleOrNull() ?: -1

    /** The row your crosshair is on (0 bottom to 2 top), or -1 off the board. */
    private fun crosshairRow(): Int {
        val p = mc.player ?: return -1
        val eye = p.eyePosition; val look = p.lookAngle
        if (look.z <= 1e-3) return -1
        val t = (I4Geometry.FACE - eye.z) / look.z
        val x = eye.x + look.x * t; val y = eye.y + look.y * t
        if (x !in 63.0..70.0 || y !in 125.0..132.0) return -1
        return ((y - 125.0) / 2).toInt().coerceIn(0, 2)
    }

    /**
     * The aims to choose from. With Hydra Strike (a whole row in one volley) only the three row aims (the vertical
     * strips on the middle column), unless every row has at most one cell that can still be uncounted: then any aim,
     * to hit those cells directly.
     */
    private fun candidates(all: List<I4Geometry.Coarse>, u: DoubleArray): List<I4Geometry.Coarse> {
        val rows = all.filter { it.cells.size == 3 && rowOf(it.cells) >= 0 }
        if (rows.size < 3) return all
        val onePerRow = (0 until 3).all { r -> (0 until 3).count { c -> u[r * 3 + c] > 0.05 } <= 1 }
        return if (onePerRow) all else rows
    }

    /** The aim covering the most chance of being the target, +0.03 on row [row] and +0.03 on the middle row. */
    private fun guess(all: List<I4Geometry.Coarse>, pi: DoubleArray, row: Int): I4Geometry.Coarse? =
        all.maxWithOrNull(compareBy({ c ->
            val r = rowOf(c.cells)
            Math.round(1000 * (c.bestChance * c.cells.sumOf { pi[it] } + (if (r >= 0 && r == row) 0.03 else 0.0) + (if (r == 1) 0.03 else 0.0)))
        }, { it.good }))?.takeIf { c -> c.cells.sumOf { pi[it] } > 1e-6 }

    /**
     * NOW and NEXT. Before the first emerald: the middle row. A shown target none of your arrows is on its way to: its
     * aim worth the most (its chance, plus each uncounted cell a LATER arrow covers, the new target with chance
     * 1/(cells left)). Otherwise [guess]. NEXT is [guess] after NOW's volley strikes at a row volley's rates.
     */
    private fun plan(scanned: List<I4Geometry.Coarse>): Pair<I4Geometry.Coarse?, I4Geometry.Coarse?> {
        if (complete) return null to null
        val all = candidates(scanned, u)
        if (!started) return all.maxWithOrNull(compareBy({ Math.round(1000 * it.bestChance * it.cells.count { c -> c / 3 == 1 }) }, { it.good })) to null
        val row = crosshairRow()
        val now = if (lit >= 0 && pi[lit] > 0.5) {
            val rest = (0 until 9).filter { it != lit && u[it] > 0.5 }
            fun expected(cells: List<Int>): Double { var e = 0.0; for (k in cells.indices) if (rest.size - k > 0) e += 1.0 / (rest.size - k); return e }
            all.filter { lit in it.cells }.maxWithOrNull(compareBy({ Math.round(1000 * it.bestChance * (1 + expected(it.order.dropWhile { c -> c != lit }.drop(1).filter { c -> c in rest }))) }, { it.good }))
        } else guess(all, pi, row)
        if (now == null || !showNext) return now to null
        val u2 = u.copyOf(); val pi2 = pi.copyOf()
        for (c in now.order) strike(u2, pi2, c, ROW_Q[c % 3])
        val next = guess(candidates(scanned, u2), pi2, rowOf(now.cells))
        return now to next?.takeIf { it.cells != now.cells }
    }

    private fun step(): Double = I4Geometry.mouseStep(mc.options.sensitivity().get())

    /** How far (in counts, 0-0.5) your look sits off [l]: whole mouse counts keep it on. */
    private fun offLattice(l: Lattice, yaw: Float, pitch: Float): Double {
        fun off(d: Double) = Math.abs(d - Math.rint(d))
        return maxOf(off(Mth.wrapDegrees(yaw - l.yaw0) / l.step), off((pitch - l.pitch0) / l.step))
    }

    /** Picks NOW and NEXT for how the board is; scans first when where you stand or what you hold changed. */
    private fun update() {
        val p = mc.player ?: return
        val b = board ?: return
        val bow = bow()
        val step = step()
        val pos = p.position()
        val legacy = !com.engineerclient.p3sim.P3Sim.inSim
        val geo = "$bow|$stacks|${p.isShiftKeyDown}|${"%.3f %.3f %.3f".format(pos.x, pos.y, pos.z)}|$step|$minChance|$legacy"
        val sc = scan
        if (sc == null || sc.key != geo) {
            // One scan at a time; the tick after it ends starts the next if anything changed meanwhile.
            if (!busy.compareAndSet(false, true)) return
            val shooter = Shooter(bow, pos, p.isShiftKeyDown, stacks, legacy)
            val min = minChance / 100.0
            worker.execute {
                try {
                    val t0 = System.nanoTime()
                    scan = Scan(geo, b, shooter, min, inPool { I4Geometry.scan(b, shooter, min) })
                    DevgineerClient.logger.info("[dc] i4 scan ${(System.nanoTime() - t0) / 1_000_000}ms for $geo")
                } catch (t: Throwable) {
                    DevgineerClient.logger.error("[dc] i4 aims failed", t)
                    scan = Scan(geo, b, shooter, min, emptyList())   // not retried every tick
                } finally {
                    busy.set(false)
                }
            }
            return
        }
        // The mouse lattice: kept while your look stays on it (whole counts), moved when it drifts off.
        var grid = sc.grid
        if (grid == null || offLattice(grid.lattice, p.yRot, p.xRot) > 0.3) {
            grid = Grid(sc, Lattice(Mth.wrapDegrees(p.yRot.toDouble()), p.xRot.toDouble(), step))
            sc.grid = grid
        }
        val (now, next) = plan(sc.coarse)
        val key = "$geo|${System.identityHashCode(grid)}|${now?.cells?.sorted()}|${next?.cells?.sorted()}"
        if (key == result?.key) return
        val picked = if (now == null) emptyList() else listOfNotNull(now, next)
        // Already worked out: paint it now.
        if (picked.all { grid.cached(it) }) { show(grid, key, picked, 0); return }
        if (!busy.compareAndSet(false, true)) return
        val g = grid
        worker.execute {
            try {
                val t0 = System.nanoTime()
                inPool { picked.parallelStream().forEach { g.aim(it) } }
                show(g, key, picked, (System.nanoTime() - t0) / 1_000_000)
                // The rest of this lattice's candidates, so the next pick paints at once.
                inPool { candidates(sc.coarse, DoubleArray(9) { 1.0 }).filter { !g.cached(it) }.parallelStream().forEach { g.aim(it) } }
            } catch (t: Throwable) {
                DevgineerClient.logger.error("[dc] i4 aims failed", t); result = Result(key, sc.shooter.eye, g.lattice, null, null)
            } finally { busy.set(false) }
        }
    }

    private fun show(grid: Grid, key: String, picked: List<I4Geometry.Coarse>, ms: Long) {
        val was = result?.key
        val r = Result(key, grid.sc.shooter.eye, grid.lattice, picked.getOrNull(0)?.let { grid.aim(it) }, picked.getOrNull(1)?.let { grid.aim(it) })
        result = r
        logPass(was, key, r, ms)
    }

    private val KEY_PARTS = listOf("bow", "stacks", "sneak", "pos", "step", "min", "legacy", "lattice", "now", "next")

    /** One log line per pass: what changed since the last one and what it paints, to trace jumps in what's drawn. */
    private fun logPass(was: String?, key: String, r: Result, ms: Long) {
        val old = was?.split('|'); val now = key.split('|')
        val changed = now.indices.filter { old == null || old.getOrNull(it) != now[it] }.joinToString(",") { KEY_PARTS.getOrElse(it) { "?" } + "=" + now[it] }
        DevgineerClient.logger.info("[dc] i4 paint ${ms}ms t=$ticks lit=$lit changed[$changed] spots=${r.aims.map { it.pixels.size }} " +
            "pi=${pi.joinToString(",") { "%.2f".format(it) }} u=${u.joinToString(",") { "%.2f".format(it) }}")
    }

    /** New arrows near you: where each will cross the board, so a volley in the air on the lit target counts as its hit. */
    private fun trackArrows() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        val b = board ?: return
        val eye = p.eyePosition
        flying.entries.removeIf { it.value.second < ticks }
        for (a in level.getEntitiesOfClass(AbstractArrow::class.java, p.boundingBox.inflate(6.0)).sortedBy { it.id }) {
            if (a.id in flying || a.tickCount > 4 || a.position().distanceTo(eye) > 6.0) continue
            val v = a.deltaMovement
            if (v.length() < 1.0 || v.z <= 0) continue
            // Flown with a gravity step given back, as the board registers Hypixel's arrows (ShotPlan).
            val c = I4Geometry.cross(a.position(), a.position(), v.add(0.0, 0.05, 0.0)) ?: continue
            val cell = I4Geometry.hit(b, c)
            if (cell < 0) { flying[a.id] = -1 to ticks + 12; continue }
            val eta = ((I4Geometry.FACE - a.z) / v.z).toInt() + 4
            flying[a.id] = cell to ticks + eta
            // As if landed: the tracker moves on now (main, then +x, then -x: spawn order is tick order).
            if (started && !complete) {
                if (cell == lit && pi[lit] > 0.5) { confirmCell = cell; confirmBy = ticks + eta + 3 }
                strike(u, pi, cell, 1.0)
            }
            DevgineerClient.logger.info("[dc] i4 arrow t=$ticks id=${a.id} age=${a.tickCount} -> cell $cell by t=${ticks + eta} (lit=$lit)")
        }
    }

    init {
        on<LevelEvent.Load> { reset(); board = null }

        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (!overlay) return@onReceive
            stacks = STACKS.find(content.string.replace(CODES, ""))?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 10) ?: 0
        }

        onReceive<ClientboundSystemChatPacket>(ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(CODES, "")
            if (msg == "[BOSS] Goldor: Who dares trespass into my domain?") mc.execute { reset() }
            DEVICE_LINE.matchEntire(msg)?.let { m -> mc.execute { if (m.groupValues[1] == mc.player?.gameProfile?.name && atBoard()) complete = true } }
        }

        on<BlockUpdateEvent> {
            val i = I4Geometry.CELLS.indexOf(pos)
            if (i < 0) return@on
            DevgineerClient.logger.info("[dc] i4 block t=$ticks cell $i ${if (updated.`is`(Blocks.EMERALD_BLOCK)) "lit" else "blue"}")
            if (updated.`is`(Blocks.EMERALD_BLOCK)) {
                // The first emerald, or a cell you'd hit lighting again (the board started over).
                if (!started || i in marked) { marked.clear(); u.fill(1.0); started = true }
                lit = i; complete = false; confirmCell = -1
                pi.fill(0.0); pi[i] = 1.0; u[i] = 1.0
            } else if (old.`is`(Blocks.EMERALD_BLOCK) && updated.`is`(Blocks.DYED_TERRACOTTA.blue())) {
                marked += i
                if (lit == i) lit = -1
                if (confirmCell == i) confirmCell = -1
                if (marked.size >= 9) marked.clear()
                u[i] = 0.0; redraw()
            }
        }

        on<TickEvent.End> {
            ticks++
            if (!atBoard()) {
                if (++awayTicks > 40 && (lit >= 0 || marked.isNotEmpty() || board != null)) { reset(); board = null }
                result = null
                return@on
            }
            awayTicks = 0
            DevgineerClient.safely("i4 aims") {
                if (board == null) board = readBoard()
                // Your arrow on the shown cell missed after all: it's still the target.
                if (confirmCell >= 0 && ticks > confirmBy) {
                    if (lit == confirmCell) { pi.fill(0.0); pi[lit] = 1.0; u[lit] = 1.0 }
                    confirmCell = -1
                }
                trackArrows()
                update()
            }
        }

        // Under the crosshair, so it stays on top; the HUD is drawn under any open screen.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath("devgineerclient", "i4_aims")) { g, _ ->
            DevgineerClient.safely("i4 aims screen") { drawScreen(g) }
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

    private const val GREEN = 0xFF00FF00.toInt()
    private const val CYAN = 0xFF00FFFF.toInt()

    /** The last frame's painted runs (x0, x1, y, colour), reused while the camera and the aims haven't changed. */
    private class Drawn(val result: Result, val yaw: Float, val pitch: Float, val fov: Float, val w: Int, val h: Int, val runs: IntArray)
    private var drawn: Drawn? = null

    /**
     * Green on each screen pixel whose look (through its centre) rounds to a spot of NOW (the nearest whole mouse
     * count), cyan for NEXT, for the camera's look and (zoomed) FOV this frame. Each aim is worked out over its own
     * screen box only, as runs along each pixel row, and the runs are kept until the camera or the aims change. Not
     * drawn while a screen (menu, chat) is open.
     */
    private fun drawScreen(g: GuiGraphicsExtractor) {
        if (!enabled || mc.gui.screen() != null) return
        val r = result ?: return
        if (r.aims.isEmpty() || !atBoardCached()) return
        val camera = mc.gameRenderer.mainCamera()
        val window = mc.window
        val w = window.width; val h = window.height
        val d = drawn
        val runs = if (d != null && d.result === r && d.yaw == camera.yRot() && d.pitch == camera.xRot() && d.fov == camera.fov && d.w == w && d.h == h) d.runs
        else IntArrayList().also { out ->
            // NEXT first, so NOW's runs are painted over it where they meet.
            r.next?.let { trace(it, r.lattice, camera.yRot(), camera.xRot(), camera.fov, w, h, CYAN, out) }
            r.now?.let { trace(it, r.lattice, camera.yRot(), camera.xRot(), camera.fov, w, h, GREEN, out) }
        }.toArray().also { drawn = Drawn(r, camera.yRot(), camera.xRot(), camera.fov, w, h, it) }
        val gs = window.guiScale.toFloat()
        g.pose().pushMatrix()
        g.pose().scale(1f / gs, 1f / gs)
        for (k in runs.indices step 4) g.fill(runs[k], runs[k + 2], runs[k + 1], runs[k + 2] + 1, runs[k + 3])
        g.pose().popMatrix()
    }

    private class IntArrayList {
        private var a = IntArray(256); private var n = 0
        fun add(x: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = x }
        fun toArray(): IntArray = a.copyOf(n)
    }

    /** [aim]'s pixels as runs (x0, x1, y, [color]) into [out], over the screen box of its counts. */
    private fun trace(aim: I4Geometry.Aim, l: Lattice, yawDeg: Float, pitchDeg: Float, fov: Float, w: Int, h: Int, color: Int, out: IntArrayList) {
        val spots = aim.pixels
        if (spots.isEmpty()) return
        val i0 = spots.minOf { it.i }; val i1 = spots.maxOf { it.i }
        val j0 = spots.minOf { it.j }; val j1 = spots.maxOf { it.j }
        val cols = i1 - i0 + 1
        val mark = BooleanArray(cols * (j1 - j0 + 1))
        for (s in spots) mark[(s.j - j0) * cols + (s.i - i0)] = true
        val focal = h / 2.0 / tan(Math.toRadians(fov / 2.0))
        val cy = Math.toRadians(yawDeg.toDouble()); val cp = Math.toRadians(pitchDeg.toDouble())
        val fx = -sin(cy) * cos(cp); val fy = -sin(cp); val fz = cos(cy) * cos(cp)
        val rx = -cos(cy); val rz = -sin(cy)
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
        // The screen box of the counts, a count wider each way.
        var xMin = Double.MAX_VALUE; var xMax = -Double.MAX_VALUE; var yMin = Double.MAX_VALUE; var yMax = -Double.MAX_VALUE
        for (ci in listOf(i0 - 1.0, i1 + 1.0)) for (cj in listOf(j0 - 1.0, j1 + 1.0)) {
            val yaw = Math.toRadians(l.yaw0 + ci * l.step); val pitch = Math.toRadians(l.pitch0 + cj * l.step)
            val dx = -sin(yaw) * cos(pitch); val dy = -sin(pitch); val dz = cos(yaw) * cos(pitch)
            val depth = dx * fx + dy * fy + dz * fz
            if (depth <= 0.1) return
            val sx = w / 2.0 + focal * (dx * rx + dz * rz) / depth; val sy = h / 2.0 - focal * (dx * ux + dy * uy + dz * uz) / depth
            xMin = minOf(xMin, sx); xMax = maxOf(xMax, sx); yMin = minOf(yMin, sy); yMax = maxOf(yMax, sy)
        }
        val px0 = maxOf(0, xMin.toInt()); val px1 = minOf(w - 1, xMax.toInt() + 1)
        val py0 = maxOf(0, yMin.toInt()); val py1 = minOf(h - 1, yMax.toInt() + 1)
        if (px1 < px0 || py1 < py0 || (px1 - px0).toLong() * (py1 - py0) > 1_000_000L) return
        for (py in py0..py1) {
            val sv = -(py + 0.5 - h / 2.0) / focal
            var run = -1
            for (px in px0..px1 + 1) {
                var on = false
                if (px <= px1) {
                    val su = (px + 0.5 - w / 2.0) / focal
                    val dx = fx + su * rx + sv * ux; val dy = fy + sv * uy; val dz = fz + su * rz + sv * uz
                    val i = l.i(l.yaw0 + Mth.wrapDegrees(Math.toDegrees(atan2(-dx, dz)) - l.yaw0))
                    val j = l.j(Math.toDegrees(atan2(-dy, hypot(dx, dz))))
                    on = i in i0..i1 && j in j0..j1 && mark[(j - j0) * cols + (i - i0)]
                }
                if (on && run < 0) run = px
                else if (!on && run >= 0) { out.add(run); out.add(px); out.add(py); out.add(color); run = -1 }
            }
        }
    }
}
