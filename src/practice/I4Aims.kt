package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.practice.I4Geometry.Board
import com.engineerclient.practice.I4Geometry.Lattice
import com.engineerclient.practice.I4Geometry.Shooter
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * i4 (the Arrows Device): on the board, every crosshair spot your mouse can reach from where you stand that lands
 * every arrow of an aim on its cell, for the bow you hold and your Terror (Hydra Strike) stacks ([I4Geometry]).
 *
 * - The lit target's aim (green): the spots that hit it and as many other unhit cells as can be (one of them is
 *   the next target, taken in the same moment 85% of the time). A Terminator's side arrows have no aim noise, so a
 *   pair's spots are certain; the main arrow and Hydra Strike's carry 1.8's noise, so a Hydra row's (main on the
 *   middle cell, all 3 arrows on cells, 10 stacks) are a chance, ~98% at best. Spots are shaded by it, and only
 *   those at Min Hit Chance or better are drawn.
 * - The guess aims (orange, red): while no target is lit, or the lit one is already taken by a volley in the air,
 *   the spots covering the most unhit cells: the next target is one of them, and a hit on it before it lights counts.
 * - A white outline marks each aim's best spot: the surest, then the one with the most room around it.
 * - Landing Preview: where each arrow of a volley fired now would cross the board (white on an unhit cell).
 *
 * The server aims a shortbow shot with the newest look it has when the shot fires (up to a ping after your click,
 * or when the cooldown ends for a click inside it), so hold the spot until the arrows are out.
 * Odin's own aim cubes are hidden while this is on (ArrowsDeviceAimMixin); its target highlight stays.
 */
object I4Aims : Module(
    name = "i4 Aim",
    category = Category.custom("Engineer Client"),
    description = "i4: every crosshair spot that lands each arrow of your volley on a cell, for your bow and Hydra stacks.",
    key = null,
) {
    private val minChance by NumberSetting("Min Hit Chance", 90, 50, 100, 1, unit = "%", desc = "Spots are drawn when every arrow of the aim lands on its cell at least this often. A Terminator's side arrows have no aim noise (always 100%); the main arrow and Hydra Strike's carry 1.8's.")
    private val guesses by NumberSetting("Guess Aims", 1, 0, 2, 1, desc = "Aims shown for the next, not yet lit target (each covering the most unhit cells the ones before don't).")
    private val inFlight by BooleanSetting("Count Arrows In Flight", true, desc = "Once a volley in the air will hit the lit target, show the guess aims: your next shot goes to the next target.")
    private val preview by BooleanSetting("Landing Preview", true, desc = "Where each arrow of a volley fired now would cross the board: white on an unhit cell, grey elsewhere.")
    private val screenOverlay by BooleanSetting("Screen Overlay", true, desc = "Draw the spots flat on your screen around the crosshair, above the world but under open screens: nothing in the world tints or hides them. Off draws them on the board.")
    private val autoZoom by BooleanSetting("Auto Zoom", true, desc = "Narrow your FOV while you look at the board from the plate, so each mouse count is a bigger step on screen. Mouse speed in degrees doesn't change.")
    private val zoom by NumberSetting("Zoom", 2.0, 1.25, 4.0, 0.25, unit = "x", desc = "How far Auto Zoom magnifies.").withDependency { autoZoom }
    private val litColor by ColorSetting("Target Color", Color(57, 255, 20, 1f), true, desc = "The lit target's spots.")
    private val guessColor by ColorSetting("Guess Color", Color(255, 230, 0, 1f), true, desc = "The first guess aim's spots.")
    private val guess2Color by ColorSetting("Second Guess Color", Color(255, 0, 230, 1f), true, desc = "The second guess aim's spots.")

    private val BOWS = setOf("TERMINATOR", "MOSQUITO_BOW", "ITEM_SPIRIT_BOW", "JUJU_SHORTBOW", "ARTISANAL_SHORTBOW", "LAST_BREATH")
    private val PLATE = Vec3(63.5, 127.0, 35.5)
    private val STACKS = Regex("(\\d+)⁑")
    private val CODES = Regex("§.")
    /** How often a volley that hits the lit target also takes the next one it covers, in the same moment (recordings: 111 of 129). */
    private const val NEXT_TAKEN = 0.85

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

    private enum class Role { TARGET, GUESS, GUESS2 }
    private class Shown(val role: Role, val aim: I4Geometry.Aim)
    /** A finished pass: what it was worked out for, and its aims. */
    private class Result(val key: String, val eye: Vec3, val lattice: Lattice, val aims: List<Shown>)
    @Volatile private var result: Result? = null
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "ec-i4-aims").apply { isDaemon = true } }

    /** Read by ArrowsDeviceAimMixin: Odin's aim cubes are hidden while this draws its own. */
    @JvmStatic fun replacesOdinAims(): Boolean = enabled

    private fun bow(): String? = mc.player?.mainHandItem?.itemId?.takeIf { it in BOWS }

    private fun reset() { lit = -1; marked.clear(); flying.clear(); result = null }

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
        EngineerClient.logger.info("[ec] i4 board: ${boxes.size} boxes: " + boxes.indices.joinToString(" ") { i ->
            val b = boxes[i]; "${cellOf[i]}:[%.4f %.4f %.4f %.4f %.4f %.4f]".format(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ) })
        return Board(boxes, cellOf.toIntArray())
    }

    /**
     * Which coarse aims get drawn, as the strategy has it. With a target lit and no volley of yours in the air on it:
     * the aim on it worth the most - its chance, plus 85% of the chance that one of its other unhit cells is the next
     * target (recordings: a volley that hits the lit target takes the next one in the same moment 85% of the time it
     * covers it), so a 97.9% Hydra row beats a certain Terminator pair. Then [guesses] aims for the next target, each
     * covering the most unhit cells not already covered.
     */
    private fun pick(all: List<I4Geometry.Coarse>, target: Int, open: Set<Int>, roles: MutableMap<Set<Int>, Role>): List<I4Geometry.Coarse> {
        val out = ArrayList<I4Geometry.Coarse>()
        val rest = open - target
        if (target >= 0) {
            fun worth(c: I4Geometry.Coarse) = c.bestChance * (1 + if (rest.isEmpty()) 0.0 else NEXT_TAKEN * (c.cells intersect rest).size / rest.size)
            all.filter { target in it.cells }
                .maxWithOrNull(compareBy<I4Geometry.Coarse>({ Math.round(worth(it) * 1000) }, { it.good }))
                ?.let { out += it; roles[it.cells] = Role.TARGET }
        }
        val covered = HashSet<Int>()
        repeat(guesses) { n ->
            val next = all.filter { it !in out }
                .maxWithOrNull(compareBy<I4Geometry.Coarse>({ (it.cells intersect rest).count { c -> c !in covered } }, { Math.floor(it.bestChance * 100) }, { it.good }))
                ?: return@repeat
            if ((next.cells intersect rest).none { it !in covered }) return@repeat
            out += next; covered += next.cells; roles[next.cells] = if (n == 0) Role.GUESS else Role.GUESS2
        }
        return out
    }

    private fun step(): Double = I4Geometry.mouseStep(mc.options.sensitivity().get())

    /** Starts a pass for what you hold, where you stand and how the board is, unless the last one was for the same. */
    private fun update() {
        val p = mc.player ?: return
        val b = board ?: return
        val bow = bow() ?: run { result = null; return }
        val step = step()
        val inAir = if (inFlight) flying.values.map { it.first }.filter { it >= 0 }.toSet() else emptySet()
        val target = if (lit >= 0 && lit !in inAir) lit else -1
        val open = I4Geometry.CELLS.indices.filter { it !in marked && it !in inAir }.toSet()
        // Where your look sits on the lattice (whole counts move it, so only a teleport or a new sensitivity changes it).
        val phaseY = Math.floorMod((p.yRot / step * 40).toLong(), 40L)
        val phaseP = Math.floorMod((p.xRot / step * 40).toLong(), 40L)
        val pos = p.position()
        val key = "$bow|$stacks|${p.isShiftKeyDown}|${"%.3f %.3f %.3f".format(pos.x, pos.y, pos.z)}|$step|$phaseY,$phaseP|$target|${open.sorted()}|$minChance|$guesses"
        // One pass at a time; the tick after it ends starts the next if anything changed meanwhile.
        if (key == result?.key || !busy.compareAndSet(false, true)) return
        val shooter = Shooter(bow, pos, p.isShiftKeyDown, stacks)
        val yaw0 = (p.yRot.toDouble() % 360.0 + 360.0) % 360.0 - (if ((p.yRot.toDouble() % 360.0 + 360.0) % 360.0 >= 180.0) 360.0 else 0.0)
        val lattice = Lattice(yaw0, p.xRot.toDouble(), step)
        val min = minChance / 100.0
        worker.execute {
            try {
                val roles = HashMap<Set<Int>, Role>()
                val aims = I4Geometry.aims(b, shooter, lattice, min) { pick(it, target, open, roles) }
                result = Result(key, shooter.eye, lattice, aims.map { Shown(roles[it.cells] ?: Role.GUESS2, it) })
            } catch (t: Throwable) {
                EngineerClient.logger.error("[ec] i4 aims failed", t)
                result = Result(key, shooter.eye, lattice, emptyList())   // not retried every tick
            } finally {
                busy.set(false)
            }
        }
    }

    /** New arrows near you: where each will cross the board, so a volley in the air on the lit target counts as its hit. */
    private fun trackArrows() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        val b = board ?: return
        val eye = p.eyePosition
        flying.entries.removeIf { it.value.second < ticks }
        for (a in level.getEntitiesOfClass(AbstractArrow::class.java, p.boundingBox.inflate(6.0))) {
            if (a.id in flying || a.tickCount > 4 || a.position().distanceTo(eye) > 6.0) continue
            val v = a.deltaMovement
            if (v.length() < 1.0 || v.z <= 0) continue
            // Flown with a gravity step given back, as the board registers Hypixel's arrows (ShotPlan).
            val c = I4Geometry.cross(a.position(), a.position(), v.add(0.0, 0.05, 0.0)) ?: continue
            val cell = I4Geometry.hit(b, c)
            if (cell < 0) { flying[a.id] = -1 to ticks + 12; continue }
            val eta = ((I4Geometry.FACE - a.z) / v.z).toInt() + 4
            flying[a.id] = cell to ticks + eta
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
            if (content.string.replace(CODES, "") == "[BOSS] Goldor: Who dares trespass into my domain?") mc.execute { reset() }
        }

        on<BlockUpdateEvent> {
            val i = I4Geometry.CELLS.indexOf(pos)
            if (i < 0) return@on
            if (updated.`is`(Blocks.EMERALD_BLOCK)) {
                // A cell you'd hit lighting again: the board started over.
                if (i in marked) marked.clear()
                lit = i
            } else if (old.`is`(Blocks.EMERALD_BLOCK) && updated.`is`(Blocks.BLUE_TERRACOTTA)) {
                marked += i
                if (lit == i) lit = -1
                if (marked.size >= 9) marked.clear()
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
            EngineerClient.safely("i4 aims") {
                if (board == null) board = readBoard()
                trackArrows()
                update()
            }
        }

        on<RenderEvent.Extract> {
            val r = result ?: return@on
            if (!atBoardCached()) return@on
            EngineerClient.safely("i4 aims draw") {
                if (!screenOverlay) for (s in r.aims) drawAim(r, s)
                if (preview) drawPreview()
            }
        }

        // Under the crosshair, so it stays on top; the HUD is drawn under any open screen.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath("engineerclient", "i4_aims")) { g, _ ->
            EngineerClient.safely("i4 aims screen") { drawScreen(g) }
        }
    }

    private fun atBoardCached(): Boolean = board != null && (mc.player?.position()?.distanceTo(PLATE) ?: 99.0) <= 3.0

    private fun colorOf(role: Role) = when (role) { Role.TARGET -> litColor; Role.GUESS -> guessColor; Role.GUESS2 -> guess2Color }

    /** Each pitch row of the aim's spots as runs of neighbouring counts, a box each; spots under 95% are drawn darker but still opaque. */
    private fun RenderEvent.Extract.drawAim(r: Result, s: Shown) {
        val base = colorOf(s.role)
        val l = r.lattice
        fun corner(i: Double, j: Double) = I4Geometry.onPlane(r.eye, l.yaw0 + i * l.step, l.pitch0 + j * l.step, I4Geometry.FACE - 0.03)
        fun shade(c: Double) = if (c >= 0.95) 1f else 0.6f
        for ((j, row) in s.aim.pixels.groupBy { it.j }) {
            val sorted = row.sortedBy { it.i }
            var start = 0
            for (k in 1..sorted.size) {
                if (k < sorted.size && sorted[k].i == sorted[k - 1].i + 1 && shade(sorted[k].chance) == shade(sorted[start].chance)) continue
                val a = corner(sorted[start].i - 0.5, j - 0.5) ?: continue
                val b = corner(sorted[k - 1].i + 0.5, j + 0.5) ?: continue
                drawFilledBox(AABB(minOf(a.x, b.x), minOf(a.y, b.y), a.z - 0.005, maxOf(a.x, b.x), maxOf(a.y, b.y), a.z), tint(base, shade(sorted[start].chance)), depth = true)
                start = k
            }
        }
        val best = s.aim.best
        val c = I4Geometry.onPlane(r.eye, l.yaw(best.i), l.pitch(best.j), I4Geometry.FACE - 0.04) ?: return
        val w = maxOf(0.03, l.step * 0.0175 * 15)
        drawWireFrameBox(AABB(c.x - w, c.y - w, c.z - 0.005, c.x + w, c.y + w, c.z), Color(255, 255, 255, 1f), depth = true)
    }

    private fun tint(c: Color, f: Float) = if (f >= 1f) c else Color((c.red * f).toInt(), (c.green * f).toInt(), (c.blue * f).toInt(), c.alphaFloat)

    private const val ZOOM_SECONDS = 0.12f
    private var zoomT = 0f
    private var zoomAt = 0L
    private var zoomed = false

    /**
     * Camera.calculateFov's result, narrowed by up to [zoom] while you're on the plate holding a bow and looking at
     * the board (with a margin, wider once zoomed so the edge doesn't flicker). Eased in and out over [ZOOM_SECONDS].
     */
    @JvmStatic fun zoomFov(fov: Float): Float {
        if (com.engineerclient.pov.PovCapture.capturing) return fov
        val now = System.nanoTime()
        val dt = if (zoomAt == 0L) 0f else ((now - zoomAt) / 1e9f).coerceAtMost(0.1f)
        zoomAt = now
        zoomed = enabled && autoZoom && bow() != null && atBoardCached() && lookingAtBoard(if (zoomed) 1.5 else 0.75)
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

    /**
     * The spots in screen pixels around the crosshair: each is a look direction, so it goes on the screen where that
     * direction projects for your current look and the camera's (zoomed) FOV. View bobbing moves the world, not this.
     */
    private fun drawScreen(g: GuiGraphicsExtractor) {
        if (!enabled || !screenOverlay) return
        val r = result ?: return
        if (!atBoardCached()) return
        val p = mc.player ?: return
        val window = mc.window
        val w = window.width; val h = window.height
        val focal = h / 2.0 / tan(Math.toRadians(mc.gameRenderer.mainCamera.fov / 2.0))
        val cy = Math.toRadians(p.yRot.toDouble()); val cp = Math.toRadians(p.xRot.toDouble())
        val fx = -sin(cy) * cos(cp); val fy = -sin(cp); val fz = cos(cy) * cos(cp)
        val rx = -cos(cy); val rz = -sin(cy)
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
        val l = r.lattice
        // Screen pixel of the look at lattice counts (i, j), or null behind you.
        fun at(i: Double, j: Double): Pair<Int, Int>? {
            val yaw = Math.toRadians(l.yaw0 + i * l.step); val pitch = Math.toRadians(l.pitch0 + j * l.step)
            val dx = -sin(yaw) * cos(pitch); val dy = -sin(pitch); val dz = cos(yaw) * cos(pitch)
            val depth = dx * fx + dy * fy + dz * fz
            if (depth <= 0.1) return null
            return (w / 2.0 + focal * (dx * rx + dz * rz) / depth).roundToInt() to (h / 2.0 - focal * (dx * ux + dy * uy + dz * uz) / depth).roundToInt()
        }
        fun shade(c: Double) = if (c >= 0.95) 1f else 0.6f
        val gs = window.guiScale.toFloat()
        g.pose().pushMatrix()
        g.pose().scale(1f / gs, 1f / gs)
        for (s in r.aims.asReversed()) {
            val base = colorOf(s.role)
            for ((j, row) in s.aim.pixels.groupBy { it.j }) {
                val sorted = row.sortedBy { it.i }
                var start = 0
                for (k in 1..sorted.size) {
                    if (k < sorted.size && sorted[k].i == sorted[k - 1].i + 1 && shade(sorted[k].chance) == shade(sorted[start].chance)) continue
                    val a = at(sorted[start].i - 0.5, j - 0.5); val b = at(sorted[k - 1].i + 0.5, j + 0.5)
                    if (a != null && b != null) {
                        val x0 = minOf(a.first, b.first); val y0 = minOf(a.second, b.second)
                        g.fill(x0, y0, maxOf(a.first, b.first, x0 + 1), maxOf(a.second, b.second, y0 + 1), tint(base, shade(sorted[start].chance)).rgba)
                    }
                    start = k
                }
            }
        }
        for (s in r.aims) {
            val c = at(s.aim.best.i.toDouble(), s.aim.best.j.toDouble()) ?: continue
            g.outline(c.first - 6, c.second - 6, 13, 13, WHITE)
            g.outline(c.first - 5, c.second - 5, 11, 11, WHITE)
        }
        g.pose().popMatrix()
    }

    private const val WHITE = -1

    /** Where each arrow of a volley fired now would cross the board's face. */
    private fun RenderEvent.Extract.drawPreview() {
        val p = mc.player ?: return
        val b = board ?: return
        val bow = bow() ?: return
        val s = Shooter(bow, p.position(), p.isShiftKeyDown, stacks)
        val plan = com.engineerclient.p3sim.ShotPlan.plan(bow, s.pos, p.yRot, p.xRot, s.crouch, stacks, Vec3.ZERO, 4).filter { it.delay == 0 }
        for (a in plan) {
            val c = I4Geometry.cross(a.from, a.at, a.v) ?: continue
            val cell = I4Geometry.hit(b, c)
            val x = c.x + c.sx * 0.5; val y = c.y + c.sy * 0.5
            val color = if (cell >= 0 && cell !in marked) Color(255, 255, 255, 0.95f) else Color(150, 150, 150, 0.8f)
            drawFilledBox(AABB(x - 0.04, y - 0.04, I4Geometry.FACE - 0.06, x + 0.04, y + 0.04, I4Geometry.FACE - 0.05), color, depth = true)
        }
    }
}
