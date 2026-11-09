package com.engineerclient.waterboard

import com.engineerclient.EngineerClient
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.UseItemOnPostEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.puzzlesolvers.PuzzleSolvers
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.ServerUtils
import com.odtheking.odin.utils.render.drawLine
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.drawTracer
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Puzzle
import com.odtheking.odin.utils.skyblock.dungeon.PuzzleStatus
import com.odtheking.odin.utils.toFixed
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.random.Random

/**
 * Water Board solver that plans from what is really on the board, on Odin's Puzzle Solvers: a flip late, early, wrong, extra
 * or missed, a server freeze, water already flowing when you walk in, and the plan is worked out again from there.
 *
 * The board is read from the room's blocks ([Geometry]); the server's block packets keep a simulation of it in step with
 * the server, tick for tick ([Tracker], on [WaterSim], Hypixel's 1.8 water); a planner ([Planner]) searches lever flips on
 * that simulation for the soonest all-gates-open, spaced so a person can do them, in the background, again whenever what
 * happened isn't what the plan said. A solve counts once every gate stays open [Planner.HOLD] ticks (the chest needs them
 * open a while). Drawn as Odin's own Water Board solver draws (each lever's coming flips over it, "CLICK ME!" when due, its
 * tracer to the next lever and on to the one after, with its "Water Tracer" and tracer colours), instead of it
 * (WaterSolverRenderMixin) once this has a plan or a lever has been flipped.
 */
object WaterBoardSolver {
    private val dropdown = DropdownSetting("Dynamic Water Board", desc = "Settings for the dynamic Water Board solver. Its tracer and colours are the Water Board ones above. Added by engineerClient.")
    private val on = BooleanSetting("Dynamic Water Solver", true,
        desc = "Works the lever timings out from the board as it is, and again whenever a flip goes differently (late, early, wrong, missed) or the server lags. Draws instead of Odin's Water Board solver, the same way. Added by engineerClient.").withDependency { dropdown.value }
    private val lead = NumberSetting("Reaction Time", 400, 150..1000, 50, desc = "How long you need from seeing a time to clicking: nothing is planned sooner than this.", unit = "ms").withDependency { dropdown.value && on.value }
    private val showSim = BooleanSetting("Show Simulated Water", false, desc = "Draws the water the solver thinks is on the board, and where the server disagreed lately. For checking it.").withDependency { dropdown.value && on.value }

    private const val ROOM = "Water Board"

    // ---- what the network thread hands over ---------------------------------------------------------------------------------
    private class Ev(val ms: Double, val kind: Int, val a: Int, val b: Int)
    private const val CELL = 0; private const val LEVER = 1; private const val GATE = 2
    private val inbox = ConcurrentLinkedQueue<Ev>()
    @Volatile private var frame: RoomFrame? = null
    private val leverPowered = arrayOfNulls<Boolean>(7)

    // ---- the client thread's ------------------------------------------------------------------------------------------------
    private var room: DungeonRoom? = null
    private var geo: Geometry? = null
    private var tracker: Tracker? = null
    private var todo: MutableList<Flip> = ArrayList()
    private var solveAt = WaterSim.NONE
    /** Per planned flip, how early/late (ticks) it can be and still solve about as soon. */
    private var windowOf: Map<Flip, IntRange> = emptyMap()
    /** Lever packets when the running job was started: if more came, its base is out of date. */
    private var jobArrivals = 0
    private var lastGates = -1
    private var seenFlips = 0
    private var job: Future<Plan>? = null
    private var jobBase: WaterSim? = null
    private var jobCommitted: List<Flip> = emptyList()
    private var dirty = true
    private var lastPlanMs = 0.0
    /** Ticks from a click to its lever packet coming back with a server tick's packets (measured on our own clicks). */
    private var clickTicks = -1.0
    private val clickMs = DoubleArray(7) { Double.NaN }
    private val spacing = Planner.Spacing()
    private val planner = Planner(spacing)
    private val coordinator = Executors.newSingleThreadExecutor { r -> Thread(r, "ec-waterboard").apply { isDaemon = true } }

    fun install() {
        for (s in listOf(dropdown, on, lead, showSim)) PuzzleSolvers.registerSetting(s)
        on<LevelEvent.Load> { EngineerClient.safely("water board reset") { reset() } }
        onReceive<ClientboundBlockUpdatePacket> { val f = frame ?: return@onReceive; packet(f, pos, blockState) }
        onReceive<ClientboundSectionBlocksUpdatePacket> { val f = frame ?: return@onReceive; runUpdates { p, s -> packet(f, p, s) } }
        on<UseItemOnPostEvent> { EngineerClient.safely("water board click") { clicked(hitResult.blockPos) } }
        on<TickEvent.End> { EngineerClient.safely("water board tick") { tick() } }
        on<RenderExtractEvent> { EngineerClient.safely("water board render") { render(this) } }
        EventBus.subscribe(this)
    }

    /**
     * Odin's Water Board solver stays quiet while this draws: once it has a plan, and from the first flip on (Odin's table no
     * longer fits the board then, and would show its leftover flips once this has none left).
     */
    @JvmStatic fun drawsInstead(): Boolean = active() && tracker.let { it != null && (todo.isNotEmpty() || it.arrivals.isNotEmpty()) }

    /** Odin's own Water Board settings, so the tracer is drawn as its solver draws it (Odin's "Water Tracer" and colours). */
    private fun odinBool(name: String, def: Boolean): Boolean = (PuzzleSolvers.settings[name] as? BooleanSetting)?.value ?: def
    private fun odinColor(name: String, def: Color): Color = (PuzzleSolvers.settings[name] as? ColorSetting)?.value ?: def

    private fun active(): Boolean = on.value && PuzzleSolvers.enabled && DungeonUtils.inDungeons && Puzzle.WATER_BOARD.status != PuzzleStatus.Completed

    private fun nowMs(): Double = System.nanoTime() / 1e6

    private fun reset() {
        frame = null; room = null; geo = null; tracker = null; todo = ArrayList(); solveAt = WaterSim.NONE; windowOf = emptyMap()
        seenFlips = 0; job?.cancel(true); job = null; jobBase = null; dirty = true; inbox.clear(); leverPowered.fill(null)
        clickMs.fill(Double.NaN)
    }

    // ---- network thread -----------------------------------------------------------------------------------------------------

    private fun packet(f: RoomFrame, pos: BlockPos, state: BlockState) {
        val r = f.toRoom(pos)
        val ms = nowMs()
        if (r[2] == Geometry.PLANE_Z && r[0] in Geometry.BX0..Geometry.BX1 && r[1] in Geometry.BY0..82) {
            inbox.add(Ev(ms, CELL, f.cell(r[0], r[1]), view(state)))
            return
        }
        for (i in Geometry.LEVER_POS.indices) {
            val p = Geometry.LEVER_POS[i]
            if (r[0] == p[0] && r[1] == p[1] && r[2] == p[2] && state.block == Blocks.LEVER) {
                val pw = state.getValue(LeverBlock.POWERED)
                if (leverPowered[i] != null && leverPowered[i] != pw) inbox.add(Ev(ms, LEVER, i, 0))
                leverPowered[i] = pw
                return
            }
        }
        if (r[0] == 15 && r[1] == 56) {
            val o = Geometry.GATE_Z.indexOf(r[2])
            if (o >= 0) inbox.add(Ev(ms, GATE, o, if (state.isAir) 0 else 1))
        }
    }

    private fun view(s: BlockState): Int = when {
        s.block == Blocks.WATER -> s.getValue(LiquidBlock.LEVEL)
        s.isAir -> WaterSim.AIR
        else -> WaterSim.SOLID
    }

    // ---- client thread ------------------------------------------------------------------------------------------------------

    private fun tick() {
        if (!active()) { if (tracker != null) reset(); return }
        // the room we're in, or the one we were following (packets keep coming while you stand in its corridor)
        val r = DungeonUtils.currentRoom?.takeIf { it.data?.name == ROOM }
        if (r != null && r !== room) { reset(); room = r }
        val rm = room ?: return
        if (frame == null) frame = RoomFrame.of(rm)
        val f = frame ?: return
        val level = mc.level ?: return
        if (tracker == null) {
            // the lever states the packets will be compared with
            for (i in Geometry.LEVER_POS.indices) {
                val st = level.getBlockState(f.toWorld(Geometry.LEVER_POS[i]))
                leverPowered[i] = if (st.block == Blocks.LEVER) st.getValue(LeverBlock.POWERED) else null
            }
            val read = readBoard(f) ?: return
            geo = read.first
            tracker = Tracker(read.first, read.second, nowMs())
            inbox.clear(); dirty = true
            EngineerClient.logger.info("[ec] water board: ${read.first.slotCell.size} lever blocks, gates closed ${Integer.toBinaryString(read.second.closed)}, ${if (read.second.cells.any { it >= 0 }) "water on it" else "dry"}")
        }
        val tr = tracker!!
        while (true) {
            val e = inbox.poll() ?: break
            when (e.kind) {
                CELL -> tr.cell(e.ms, e.a, e.b)
                LEVER -> { leverPacket(tr, e); dirty = true }
                GATE -> { /* gate wool: applied below from the world, once its piston has moved */ }
            }
        }
        val now = nowMs()
        tr.advance(now)
        val gates = readGates(f)
        if (gates != lastGates) { lastGates = gates; dirty = true }
        tr.gates(now, gates)
        managePlan(tr, now, f)
    }

    private fun leverPacket(tr: Tracker, e: Ev) {
        tr.lever(e.ms, e.a)
        // our own click came back: how long that takes, in server ticks
        val c = clickMs[e.a]
        if (!c.isNaN() && e.ms - c in 0.0..1500.0) {
            val d = tr.now(e.ms) - tr.now(c).toDouble()
            clickTicks = if (clickTicks < 0) d else 0.7 * clickTicks + 0.3 * d
            clickMs[e.a] = Double.NaN
        }
    }

    private fun clicked(pos: BlockPos) {
        val f = frame ?: return
        val r = f.toRoom(pos)
        for (i in Geometry.LEVER_POS.indices) if (Geometry.LEVER_POS[i].contentEquals(r)) clickMs[i] = nowMs()
    }

    /** Ticks from clicking to the server's tick it lands on: measured, else from the ping. */
    private fun latencyTicks(): Double = if (clickTicks >= 0) clickTicks else ServerUtils.averagePing / 50.0 + 1.0

    private fun managePlan(tr: Tracker, now: Double, f: RoomFrame) {
        // flips that happened: the plan's matching ones are done; anything else means re-planning
        val arr = tr.arrivals
        if (arr.size != seenFlips) {
            for (i in seenFlips until arr.size) {
                val (t, lv) = arr[i]
                val k = todo.indexOfFirst { it.lever == lv && kotlin.math.abs(it.tick - t) <= 40 }
                if (k >= 0) todo.removeAt(k)
            }
            seenFlips = arr.size; dirty = true
        }
        val j = job
        if (j != null && j.isDone) {
            job = null
            val p = runCatching { j.get() }.getOrNull()
            val base = jobBase
            // a lever moved while it planned: its board is out of date (and a new plan is already due)
            if (p != null && base != null && jobArrivals == arr.size) adopt(p, base)
        }
        val tNow = tr.now(now)
        // flips whose time has gone without them (their packet would be in by now): missed, plan again
        val gone = todo.filter { it.tick + (windowOf[it]?.last ?: 0) + 6 < tNow }
        if (gone.isNotEmpty()) { todo.removeAll(gone.toSet()); dirty = true }
        // and keep at it while nothing solves
        if (solveAt == WaterSim.NONE && (todo.isNotEmpty() || tr.head.sourceOn || arr.isNotEmpty())) dirty = true
        // every gate open with nothing moving: solved, or the puzzle hasn't closed its gates yet. Open with water still moving,
        // it plans on: a gate about to close again is put right (and one staying open plans nothing)
        if (tr.head.closed == 0 && !tr.head.moving()) { todo.clear(); return }
        if (job == null && (dirty || now - lastPlanMs > 3000)) {
            val leadTicks = maxOf(Tracker.AHEAD, Math.ceil(lead.value.toDouble() / 50.0 + latencyTicks()).toInt())
            // from the server's board a reaction time from now, with the plan's flips before that as good as done
            val target = tNow + leadTicks
            val committed = todo.filter { it.tick <= target }
            val base = tr.head.copy()
            val bits = HashMap<Int, Int>()
            for (fl in committed) if (fl.tick > base.time) bits.merge(fl.tick, 1 shl fl.lever) { a, b -> a xor b }
            while (base.time < target) base.step(bits[base.time + 1] ?: 0)
            val rest = todo.filter { it.tick > target }
            val seeds = listOf(rest, rest.map { Flip(maxOf(it.tick, target + 1), it.lever) })
            jobCommitted = committed
            val fresh = !base.sourceOn && base.cells.none { it >= 0 } && arr.isEmpty()
            jobArrivals = arr.size
            val budget = if (fresh) 2500L else if (solveAt == WaterSim.NONE) 1000L else 350L
            jobBase = base; dirty = false; lastPlanMs = now
            // no lever sooner than you can get to it from where you stand
            val first = mc.player?.let { pl -> val r = f.toRoom(pl.blockPosition()); IntArray(7) { lv -> maxOf(0, spacing.from(r[0] + 0.5, r[2] + 0.5, lv) - leadTicks) } }
            job = coordinator.submit<Plan> { planner.plan(base, seeds, budget, Random.Default, first) }
        }
    }

    /** A new plan replaces the old one only if the old one no longer works from here or the new one is clearly sooner. */
    private fun adopt(p: Plan, base: WaterSim) {
        // the flips that were due before the plan's start stay; those the player has done since are gone from [todo]
        val committed = jobCommitted.filter { c -> todo.any { it == c } }
        val rest = todo.filter { it.tick > base.time }
        val old = if (rest.isEmpty()) null else planner.score(base, rest)
        val pick = if (old == null || !old.solved || (p.solved && p.solvedAt < old.solvedAt - 5) || (p.solved && old.cost > p.cost + 20)) p else old
        todo = (committed + pick.flips).sortedBy { it.tick }.toMutableList()
        solveAt = pick.solvedAt
        windowOf = committed.associateWith { -2..2 } + pick.flips.zip(pick.window).toMap()
    }

    /** The board's rows from the world (null while a piston is moving or the room isn't loaded), its lever blocks, and its water. */
    private fun readBoard(f: RoomFrame): Pair<Geometry, WaterSim>? {
        val level = mc.level ?: return null
        fun st(x: Int, y: Int, z: Int) = level.getBlockState(f.toWorld(intArrayOf(x, y, z)))
        val rows = ArrayList<String>()
        for (y in Geometry.BY1 downTo Geometry.BY0) {
            val sb = StringBuilder()
            for (x in Geometry.BX0..Geometry.BX1) {
                val a = st(x, y, 26); val b = st(x, y, 27); val pist = st(x, y, 28).block == Blocks.STICKY_PISTON
                if (pist && (a.block == Blocks.MOVING_PISTON || b.block == Blocks.MOVING_PISTON)) return null
                val ta = slotChar(a.block); val tb = slotChar(b.block)
                sb.append(when {
                    pist && ta != null -> ta
                    pist && tb != null && (a.isAir || a.block == Blocks.WATER) -> tb.lowercaseChar()
                    a.isAir || a.block == Blocks.WATER -> '.'
                    else -> '#'
                })
            }
            rows += sb.toString()
        }
        if (rows.sumOf { r -> r.count { it.uppercaseChar() in Geometry.SLOT_CHARS } } < 10) return null
        val (g, inn) = Geometry.parse(rows, f.rotation)
        val water = IntArray(g.w * g.h) { -1 }
        for (c in water.indices) {
            val x = g.x(c); val y = g.y(c)
            if (x !in Geometry.BX0..Geometry.BX1 || y !in Geometry.BY0..82) continue
            val s = st(x, y, 26)
            if (s.block == Blocks.WATER) water[c] = s.getValue(LiquidBlock.LEVEL)
        }
        val closed = readGates(f)
        val src = water[g.source] >= 0
        val sim = if (water.none { it >= 0 }) WaterSim.fresh(g, inn, closed)
            else {
                var wet = 0
                for (o in g.outletCell.indices) if (water[g.outletCell[o]] >= 0) wet = wet or (1 shl o)
                WaterSim.observed(g, inn, closed, water, src, wet, 0)
            }
        return g to sim
    }

    private fun slotChar(b: Block): Char? = when (b) {
        Blocks.QUARTZ_BLOCK -> 'Q'; Blocks.GOLD_BLOCK -> 'G'; Blocks.COAL_BLOCK -> 'C'
        Blocks.DIAMOND_BLOCK -> 'D'; Blocks.EMERALD_BLOCK -> 'E'; Blocks.TERRACOTTA -> 'T'
        else -> null
    }

    /** Gates closed, from their wool (Odin's check: anything in the middle of the corridor at y=56). */
    private fun readGates(f: RoomFrame): Int {
        val level = mc.level ?: return 0
        var c = 0
        for (o in Geometry.GATE_Z.indices) if (!level.getBlockState(f.toWorld(intArrayOf(15, 56, Geometry.GATE_Z[o]))).isAir) c = c or (1 shl o)
        return c
    }

    // ---- drawing ------------------------------------------------------------------------------------------------------------

    private fun render(event: RenderExtractEvent) {
        if (!active() || DungeonUtils.currentRoomName != ROOM) return
        val f = frame ?: return
        val tr = tracker ?: return
        // the tick a click now would come back on
        val now = tr.now(nowMs()) + latencyTicks()
        if (showSim.value) drawSim(event, f, tr)
        if (todo.isEmpty()) return
        fun center(lever: Int): Vec3 { val p = f.toWorld(Geometry.LEVER_POS[lever]); return Vec3(p.x + 0.5, p.y + 0.5, p.z + 0.5) }
        // as Odin's solver: a tracer to the next lever, and a line on from it to the one after when that is another lever
        if (odinBool("Water Tracer", true)) {
            val first = todo[0].lever
            event.drawTracer(center(first), odinColor("Tracer Color First", Colors.MINECRAFT_GREEN), false)
            if (todo.size > 1 && todo[1].lever != first)
                event.drawLine(listOf(center(first), center(todo[1].lever)), odinColor("Tracer Color Second", Colors.MINECRAFT_GOLD), false)
        }
        // and over each lever its coming flips, the soonest lowest: seconds to go, "CLICK ME!" when due
        for ((lever, flips) in todo.groupBy { it.lever }) {
            val p = f.toWorld(Geometry.LEVER_POS[lever])
            flips.forEachIndexed { i, fl ->
                val ticks = fl.tick - now
                val text = if (ticks <= 0) "§a§lCLICK ME!" else "§e${(ticks / 20.0).toFixed()}s"
                event.drawText(text, Vec3(p.x + 0.5, p.y + 1.5 + 0.5 * i, p.z + 0.5), 1f, true)
            }
        }
        if (solveAt != WaterSim.NONE) {
            val c = f.toWorld(intArrayOf(15, 80, 25))
            event.drawText("§bsolved in ${"%.1f".format(maxOf(0.0, (solveAt - tr.now(nowMs())) / 20.0))}s", Vec3(c.x + 0.5, c.y + 0.5, c.z + 0.5), 1.2f, false)
        }
    }

    private fun drawSim(event: RenderExtractEvent, f: RoomFrame, tr: Tracker) {
        val s = tr.head
        val g = tr.geo
        for (c in s.cells.indices) {
            val v = s.cells[c]
            if (v < 0) continue
            val p = f.toWorld(intArrayOf(g.x(c), g.y(c), 26))
            val h = if (v and 15 >= 8 || v and 15 == 0) 0.9 else 0.9 * (8 - (v and 15)) / 8.0
            event.drawWireFrameBox(AABB(p.x + 0.2, p.y + 0.0, p.z + 0.2, p.x + 0.8, p.y + h, p.z + 0.8), Colors.MINECRAFT_AQUA, 1f, false)
        }
    }

    /** Room coordinates (Odin's frame: rotated about the room's clay block) to the world and back. */
    private class RoomFrame(val rotation: String, private val cx: Int, private val cz: Int) {
        fun toWorld(r: IntArray): BlockPos {
            val (x, y, z) = Triple(r[0], r[1], r[2])
            return when (rotation) {
                "NORTH" -> BlockPos(-x + cx, y, -z + cz)
                "WEST" -> BlockPos(-z + cx, y, x + cz)
                "SOUTH" -> BlockPos(x + cx, y, z + cz)
                else -> BlockPos(z + cx, y, -x + cz)
            }
        }
        fun toRoom(p: BlockPos): IntArray {
            val dx = p.x - cx; val dz = p.z - cz
            return when (rotation) {
                "NORTH" -> intArrayOf(-dx, p.y, -dz)
                "WEST" -> intArrayOf(dz, p.y, -dx)
                "SOUTH" -> intArrayOf(dx, p.y, dz)
                else -> intArrayOf(-dz, p.y, dx)
            }
        }
        fun cell(x: Int, y: Int) = (y - Geometry.Y0) * (Geometry.X1 - Geometry.X0 + 1) + (x - Geometry.X0)

        companion object {
            fun of(room: DungeonRoom): RoomFrame? {
                val rot = room.rotation?.name ?: return null
                val clay = room.clayPos ?: return null
                val f = RoomFrame(rot, clay.x, clay.z)
                // the same as Odin's own transform, or don't trust it
                val probe = room.getRealCoords(BlockPos(15, 60, 5))
                if (probe == BlockPos.ZERO || probe != f.toWorld(intArrayOf(15, 60, 5))) return null
                return f
            }
        }
    }
}
