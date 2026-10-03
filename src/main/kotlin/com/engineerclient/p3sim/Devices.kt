package com.engineerclient.p3sim

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.RedstoneLampBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.random.Random
import net.minecraft.world.level.block.Blocks as B

/**
 * P3's four devices, server side, as Hypixel runs them: Simon Says (S1), Lights (S2), Arrow Align
 * (S3) and the target (S4). Each completes its [Station] when done; a bot doing one just marks it
 * done (the blocks show it done, as they would after a teammate).
 */
class Devices(val phase: GoldorPhase) {
    val ss = SimonSays()
    val lights = Lights()
    val arrows = Arrows()
    val target = Target()

    fun start() { ss.place(); lights.place(); arrows.place(); target.place() }
    fun stop() { arrows.remove() }
    fun tick() { ss.tick(); target.tick() }

    private fun station(label: String) = phase.stations.first { it.kind == Station.Kind.DEVICE && it.label == label }

    /** A bot (or a start past it) did [label]: show it done. */
    fun shownDone(label: String) = when (label) {
        "SS" -> ss.clear()
        "Lights" -> lights.solve()
        "Arrows" -> arrows.solve()
        "Target" -> target.clear()
        else -> Unit
    }

    /** A right click on a block: true if a device took it. */
    fun use(pos: BlockPos): Boolean = ss.use(pos) || lights.use(pos)

    // ------------------------------------------------------------------ Simon Says

    /**
     * Simon Says (S1), as measured in docs/mechanics/simon-says.md (the same timings as SS
     * Practice): the start button; lights one every 8 ticks; the buttons back 10 ticks after the
     * last light goes out (5 after it comes on after a stray light, the lit one 18); a press stays
     * down 3 ticks; the next round 6 ticks after a round's last press; five rounds. A wrong press:
     * buttons gone 3 ticks later and a new sequence shown, the skip's way, 25 ticks after it.
     */
    inner class SimonSays {
        private val START = BlockPos(110, 121, 91)
        private fun buttonAt(cell: Int) = BlockPos(110, 123 - cell / 4, 92 + cell % 4)
        private fun lampAt(cell: Int) = BlockPos(111, 123 - cell / 4, 92 + cell % 4)
        private val BUTTON: BlockState get() = B.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.WEST)

        private var sequence = listOf<Int>()
        private var expected = listOf<Int>()
        private var next = 0
        private var accepting = false
        private val up = BooleanArray(16)
        private val downUntil = IntArray(16)
        private var gen = 0
        private var startPresses = 0
        private var starting = false
        private var startedAt = -100
        private var running = false


        private val jobs = ArrayList<Job>()
        private fun after(ticks: Int, run: () -> Unit) { jobs += Job(phase.t + ticks, gen, run) }

        private val done get() = station("SS").done

        fun place() {
            Blocks.set(START, BUTTON)
            for (c in 0 until 16) { light(c, false); button(c, false) }
        }

        fun clear() {
            gen++; jobs.clear(); running = false; accepting = false
            for (c in 0 until 16) { light(c, false); button(c, false) }
        }

        fun tick() {
            if (jobs.isEmpty()) return
            val due = jobs.filter { it.at <= phase.t }
            jobs.removeAll(due.toSet())
            due.forEach { if (it.gen == gen) it.run() }
        }

        private fun light(cell: Int, on: Boolean) = Blocks.set(lampAt(cell), if (on) B.SEA_LANTERN.defaultBlockState() else B.OBSIDIAN.defaultBlockState())
        private fun button(cell: Int, show: Boolean, pressed: Boolean = false) {
            up[cell] = show
            Blocks.set(buttonAt(cell), if (show) BUTTON.setValue(ButtonBlock.POWERED, pressed) else B.AIR.defaultBlockState())
        }

        fun use(pos: BlockPos): Boolean {
            if (pos == START) { Fight.afterPing("ss start") { pressStart() }; return true }
            val cell = (0 until 16).firstOrNull { buttonAt(it) == pos } ?: return false
            Fight.afterPing("ss press") { press(cell) }
            return true
        }

        private fun pressStart() {
            Blocks.set(START, BUTTON.setValue(ButtonBlock.POWERED, true))
            Fight.later(2, "ss start up") { if (phase === Fight.phase) Blocks.set(START, BUTTON) }
            Sim.sound(SoundEvents.STONE_BUTTON_CLICK_ON, 0.3f, 0.6f, Vec3.atCenterOf(START))
            if (done || phase.section != 1) return
            if (starting) { startPresses++; return }
            if (running && phase.t - startedAt < 20) return
            // A press mid-run starts it over, as from idle.
            clear()
            starting = true; startPresses = 1; startedAt = phase.t
            after(6) { starting = false; begin() }
        }

        private fun begin() {
            running = true
            val r = Random
            sequence = List(5) { r.nextInt(16) }
            val s = sequence
            when (startPresses.coerceAtMost(3)) {
                1 -> show(listOf(s[0]), listOf(s[0]), stray = false)
                2 -> show(listOf(stray(s[0]), s[0]), listOf(s[0]), stray = true)
                else -> show(listOf(stray(s[0]), s[0], s[1]), listOf(s[0], s[1]), stray = true)
            }
        }

        private fun stray(not: Int): Int { var c: Int; do c = Random.nextInt(16) while (c == not); return c }

        private fun show(cells: List<Int>, expect: List<Int>, stray: Boolean) {
            accepting = false
            for (c in 0 until 16) button(c, false)
            expected = expect; next = 0
            val n = cells.size
            for (i in 0 until n) after(8 * i) { if (i > 0) light(cells[i - 1], false); light(cells[i], true) }
            after(8 * n) { light(cells[n - 1], false) }
            if (stray) {
                after(8 * (n - 1) + 5) { for (c in 0 until 16) if (c != cells.last()) button(c, true); accepting = true }
                after(8 * (n - 1) + 18) { button(cells.last(), true) }
            } else {
                after(8 * n + 10) { for (c in 0 until 16) button(c, true); accepting = true }
            }
        }

        private fun press(cell: Int) {
            if (!up[cell] || downUntil[cell] > phase.t) return
            button(cell, true, pressed = true)
            downUntil[cell] = phase.t + 3
            Sim.sound(SoundEvents.STONE_BUTTON_CLICK_ON, 0.3f, 0.6f, Vec3.atCenterOf(buttonAt(cell)))
            val g = gen
            Fight.later(3, "ss up") { if (g == gen && up[cell]) button(cell, true) }
            if (!accepting) return
            if (cell == expected[next]) {
                next++
                if (next < expected.size) return
                accepting = false
                val n = expected.size
                if (n == 5) after(6) { clear(); station("SS").complete(Sim.me) }
                else after(6) { val cells = sequence.take(n + 1); show(cells, cells, stray = false) }
            } else {
                accepting = false
                after(3) { for (c in 0 until 16) button(c, false) }
                // A new sequence, shown the skip's way.
                after(25) {
                    sequence = List(5) { Random.nextInt(16) }
                    show(listOf(stray(sequence[0]), sequence[0], sequence[1]), listOf(sequence[0], sequence[1]), stray = true)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Lights

    /**
     * Lights (S2): 20 levers (x58-62, y133-136, z142) over 20 lamps (z143). The six right ones on
     * and the rest off is the device done; each lever lights its own lamp.
     */
    inner class Lights {
        private val right = setOf(58 to 133, 58 to 136, 60 to 134, 60 to 135, 62 to 133, 62 to 136)
        private val levers = (58..62).flatMap { x -> (133..136).map { y -> x to y } }
        private val on = HashSet<Pair<Int, Int>>()

        fun place() {
            on.clear()
            // Some on at random, as Hypixel starts it (never already done).
            do { on.clear(); levers.forEach { if (Random.nextInt(4) == 0) on += it } } while (on == right)
            draw()
        }

        fun solve() { on.clear(); on += right; draw() }

        private fun draw() = levers.forEach { (x, y) ->
            val lever = BlockPos(x, y, 142)
            val st = Blocks.get(lever)
            if (st != null && st.hasProperty(LeverBlock.POWERED)) Blocks.set(lever, st.setValue(LeverBlock.POWERED, (x to y) in on))
            val lamp = BlockPos(x, y, 143)
            Blocks.set(lamp, B.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, (x to y) in on))
        }

        fun use(pos: BlockPos): Boolean {
            if (pos.z != 142 || (pos.x to pos.y) !in levers) return false
            Fight.afterPing("lights lever") {
                val st = station("Lights")
                if (st.done || phase.section != 2) return@afterPing
                val k = pos.x to pos.y
                if (!on.remove(k)) on += k
                draw()
                Sim.sound(SoundEvents.LEVER_CLICK, 0.3f, if (k in on) 0.6f else 0.5f, Vec3.atCenterOf(pos))
                if (on == right) st.complete(Sim.me)
            }
            return true
        }
    }

    // ------------------------------------------------------------------ Arrow Align

    /**
     * Arrow Align (S3): 25 item frames at x=-2, y120-124, z75-79 (index (y-120) + (z-75)*5), the
     * path's arrows in one of Odin's nine layouts, each turned at random; a click turns one 1/8.
     * All pointing their layout's way is the device done.
     */
    inner class Arrows {
        private val frames = HashMap<Int, ItemFrame>()
        private var solution = SOLUTIONS[0]

        fun place() {
            remove()
            solution = SOLUTIONS.random()
            for (i in 0 until 25) {
                val pos = BlockPos(-2, 120 + i % 5, 75 + i / 5)
                val f = ItemFrame(EntityType.ITEM_FRAME, Sim.level, pos, Direction.EAST)
                f.isInvulnerable = true
                if (solution[i] >= 0) {
                    f.setItem(ItemStack(Items.ARROW), false)
                    var r: Int; do r = Random.nextInt(8) while (r == solution[i] && Random.nextInt(3) != 0)
                    f.setRotation(r)
                }
                frames[i] = Sim.spawn(f)
            }
        }

        fun remove() { frames.values.forEach { it.discard() }; frames.clear() }

        fun solve() = frames.forEach { (i, f) -> if (solution[i] >= 0) f.setRotation(solution[i]) }

        /** A click on [frame]: true if it is one of ours. */
        fun use(frame: ItemFrame): Boolean {
            val i = frames.entries.firstOrNull { it.value === frame }?.key ?: return false
            Fight.afterPing("arrow") {
                val st = station("Arrows")
                if (solution[i] < 0 || st.done) return@afterPing
                frame.setRotation((frame.rotation + 1) % 8)
                Sim.sound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 1f, 1f, frame.position())
                if (phase.section == 3 && frames.all { (j, f) -> solution[j] < 0 || f.rotation == solution[j] }) st.complete(Sim.me)
            }
            return true
        }

        fun owns(e: net.minecraft.world.entity.Entity) = frames.values.any { it === e }
    }

    // ------------------------------------------------------------------ the target

    /**
     * The target (S4): stand on the plate (63, 127, 35) and shoot the lit (emerald) block of the 3x3
     * at x64-68, y126-130, z50; each hit lights another, nine is the device done. Off the plate the
     * board goes dark.
     */
    inner class Target {
        val PLATE = BlockPos(63, 127, 35)
        private val blocks = (0 until 9).map { BlockPos(64 + (it % 3) * 2, 126 + (it / 3) * 2, 50) }
        private var lit = -1
        private var hits = 0

        fun place() { lit = -1; hits = 0; blocks.forEach { Blocks.set(it, B.BLUE_TERRACOTTA.defaultBlockState()) } }
        fun clear() { lit = -1; blocks.forEach { Blocks.set(it, B.EMERALD_BLOCK.defaultBlockState()) } }

        fun onPlate(): Boolean {
            val p = Sim.player ?: return false
            return p.blockPosition() == PLATE || p.blockPosition() == PLATE.above()
        }

        fun tick() {
            val st = station("Target")
            if (st.done) return
            if (onPlate() && phase.section >= 4) {
                if (lit < 0) light()
            } else if (lit >= 0) {
                Blocks.set(blocks[lit], B.BLUE_TERRACOTTA.defaultBlockState()); lit = -1
            }
        }

        private fun light() {
            var i: Int; do i = Random.nextInt(9) while (i == lit)
            lit = i
            Blocks.set(blocks[i], B.EMERALD_BLOCK.defaultBlockState())
        }

        /** An arrow (or a bow's shot) hit block [pos]. */
        fun hit(pos: BlockPos) {
            if (lit < 0 || pos != blocks[lit]) return
            val st = station("Target")
            Blocks.set(pos, B.BLUE_TERRACOTTA.defaultBlockState())
            Sim.sound(SoundEvents.ARROW_HIT_PLAYER, 0.5f, 1f)
            hits++
            if (hits >= 9) { lit = -1; clear(); st.complete(Sim.me) } else light()
        }

        val targets get() = blocks
    }

    companion object {
        /** Odin's Arrow Align layouts: each frame's rotation, -1 = no arrow. */
        val SOLUTIONS = listOf(
            listOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1),
            listOf(-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1),
            listOf(7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3),
            listOf(5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1),
            listOf(5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
            listOf(7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1),
            listOf(-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
            listOf(-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1),
            listOf(-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1),
        )
    }
}

/** A delayed step of a device (dropped when [gen] is stale). */
private class Job(val at: Int, val gen: Int, val run: () -> Unit)
