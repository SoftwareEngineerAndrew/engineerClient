package com.engineerclient.p3sim

import com.engineerclient.practice.SimonSaysDevice
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
     * Simon Says (S1): [SimonSaysDevice], the device as Hypixel runs it (the start button a start/reset toggle,
     * round tasks nothing cancels, four rounds, each player's ~50 ms limiter), drawn here in the arena's
     * blocks. A press reaches it after the presser's simulated ping. A press stays down 3 ticks; the next round
     * 6 ticks after a round's last press. A wrong press: buttons gone 3 ticks later and a new pattern shown
     * 26-34 ticks after that.
     */
    inner class SimonSays {
        private val START = BlockPos(110, 121, 91)
        private fun buttonAt(cell: Int) = BlockPos(110, 123 - cell / 4, 92 + cell % 4)
        private fun lampAt(cell: Int) = BlockPos(111, 123 - cell / 4, 92 + cell % 4)
        private val BUTTON: BlockState get() = B.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL).setValue(ButtonBlock.FACING, Direction.WEST)

        /** Each presser by name, to complete the device as them (their own section times and lines). */
        private val pressers = HashMap<String, net.minecraft.server.level.ServerPlayer?>()

        private val device = SimonSaysDevice(object : SimonSaysDevice.Io {
            override fun newSequence() = this@SimonSays.newSequence().toIntArray()
            override fun lamp(cell: Int, on: Boolean) = light(cell, on)
            override fun button(cell: Int, up: Boolean, pressed: Boolean) =
                Blocks.set(buttonAt(cell), if (up) BUTTON.setValue(ButtonBlock.POWERED, pressed) else B.AIR.defaultBlockState())
            override fun completed(by: String) {
                Sim.asPlayer(pressers[by]) { station("SS").complete(by) }
            }
        })

        private val done get() = station("SS").done

        fun place() {
            Blocks.set(START, BUTTON)
            clear()
        }

        fun clear() {
            device.clear()
            pressers.clear()
            for (c in 0 until 16) { light(c, false); Blocks.set(buttonAt(c), B.AIR.defaultBlockState()) }
        }

        fun tick() = device.tick()

        private fun light(cell: Int, on: Boolean) = Blocks.set(lampAt(cell), if (on) B.SEA_LANTERN.defaultBlockState() else B.OBSIDIAN.defaultBlockState())

        fun use(pos: BlockPos): Boolean {
            if (pos == START) {
                // The limiter goes by when presses reach the server: sent now, there a ping later.
                val ping = Fight.pingMs()
                val at = System.nanoTime() / 1e6 + ping
                Fight.afterMs(ping, "ss start") { pressStart(at) }
                return true
            }
            val cell = (0 until 16).firstOrNull { buttonAt(it) == pos } ?: return false
            Fight.afterPing("ss press") { press(cell) }
            return true
        }

        private fun pressStart(ms: Double) {
            Blocks.set(START, BUTTON.setValue(ButtonBlock.POWERED, true))
            Fight.later(2, "ss start up") { if (phase === Fight.phase) Blocks.set(START, BUTTON) }
            if (done || phase.section != 1) return
            val who = Sim.me
            pressers[who] = Sim.acting
            // Hypixel sends no button click. A reset plays entity.enderman.teleport (vol 8, pitch 0) at the presser:
            // in the runs looked at it came with exactly the presses that were resets (one per burst).
            if (device.start(who, ms) == SimonSaysDevice.Start.RESET) Sim.sound(SoundEvents.ENDERMAN_TELEPORT, 8f, 0f)
        }

        /**
         * 4 cells, no repeats, new each time. SS Luck: of 3000^|luck| draws (1 at 0, about 55 at 50%), the one with the
         * shortest path lamp to lamp (4 neighbours in a line at 100%); bad luck the longest (corner to corner at -100%).
         * Even all the way across: the path's 6.4 blocks on average at 0, 4.7 at 20%, 3.5 at 50%.
         */
        private fun newSequence(): List<Int> {
            val luck = P3Sim.ssLuck
            val x = Math.pow(3000.0, Math.abs(luck))
            val n = (x.toInt() + if (Random.nextDouble() < x - x.toInt()) 1 else 0).coerceAtLeast(1)
            val draws = List(n) { (0 until 16).shuffled().take(4) }
            return if (luck > 0) draws.minBy { path(it) } else draws.maxBy { path(it) }
        }

        /** Lamp to lamp, in blocks. */
        private fun path(s: List<Int>) = s.zipWithNext().sumOf { (a, b) -> Math.hypot((a % 4 - b % 4).toDouble(), (a / 4 - b / 4).toDouble()) }

        private fun press(cell: Int) {
            val who = Sim.me
            pressers[who] = Sim.acting
            val r = device.press(cell, who)
            if (r == SimonSaysDevice.Press.NONE) return
            // Each press: note_block.pling (vol 8, pitch 4.05 as sent) at the presser, never a button click.
            Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8f, 4.047619f)
            if (r != SimonSaysDevice.Press.WRONG) return
            // A new show 26 or 34 ticks after the buttons vanish, in the two shapes seen: [a, b] plain (26), or a
            // stray lamp then [a, b] (34, the stray 4-8 ticks before, median 6): a round 1 show, then a round 2
            // one with a new pattern, as a skip shows it. The old buttons come back 1-4 ticks into each show
            // (stale: they press, nothing counts), and a lamp lighting takes its own.
            // SS Luck: the quick one (26, no stray) more often, always at 100%; bad luck the slow one.
            val plain = Random.nextDouble() < 0.5 + 0.5 * P3Sim.ssLuck
            val stale = { device.after(1 + Random.nextInt(4)) { device.buttonsBack() } }
            if (plain) device.after(3 + 26) { device.showRound(2); stale() }
            else {
                val gap = listOf(4, 5, 5, 6, 6, 6, 6, 7, 7, 8).random()
                device.after(3 + 34 - gap) { device.showRound(1); stale() }
                device.after(3 + 34) { device.showRound(2); stale() }
            }
        }
    }

    // ------------------------------------------------------------------ Lights

    /**
     * Lights: 20 levers (x58-62, y133-136, z142) over 20 lamps (z143), all off at the start.
     * A lamp is lit while any lever in its plus (itself and the four next to it)
     * is on: an OR, not a toggle. Levers flick any time (players pre-do it in Maxor); a click in S2
     * with all 20 lamps lit is the device done, even one that turns a lever off. Odin's six are
     * the solution from all off.
     */
    inner class Lights {
        private val right = setOf(58 to 133, 58 to 136, 60 to 134, 60 to 135, 62 to 133, 62 to 136)
        private val levers = (58..62).flatMap { x -> (133..136).map { y -> x to y } }
        private val on = HashSet<Pair<Int, Int>>()

        /** Pre-done (as in Maxor): every lamp lit, one click in S2 finishes it. */
        fun place() { on.clear(); on += right; draw() }

        fun solve() { on.clear(); on += right; draw() }

        private fun lamp(x: Int, y: Int) = (x to y) in on || (x - 1 to y) in on || (x + 1 to y) in on || (x to y - 1) in on || (x to y + 1) in on
        private fun allLit() = levers.all { (x, y) -> lamp(x, y) }

        private fun draw(lampOffDelay: Int = 0) = levers.forEach { (x, y) ->
            val lever = BlockPos(x, y, 142)
            val st = Blocks.get(lever)
            if (st != null && st.hasProperty(LeverBlock.POWERED)) Blocks.set(lever, st.setValue(LeverBlock.POWERED, (x to y) in on))
            val lampPos = BlockPos(x, y, 143)
            val lit = lamp(x, y)
            // A lamp lights in the lever's tick and goes dark 3 (usually) or 4 ticks after it.
            if (lit || lampOffDelay <= 0) Blocks.set(lampPos, B.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, lit))
            else Fight.later(lampOffDelay, "lamp off") {
                if (phase === Fight.phase && !lamp(x, y)) Blocks.set(lampPos, B.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, false))
            }
        }

        fun isLever(pos: BlockPos) = pos.z == 142 && (pos.x to pos.y) in levers

        /**
         * A click on a lever, 1 tick after the click the lever changes (sound with it), the credit line 2 after it.
         * A LEFT click toggles nothing but completes the device the same way.
         */
        fun use(pos: BlockPos, left: Boolean = false): Boolean {
            if (!isLever(pos)) return false
            Fight.afterPing("lights lever") {
                val st = station("Lights")
                if (st.done) return@afterPing
                var credit = false
                Fight.later(1, "lights lever") {
                    if (phase !== Fight.phase || st.done) return@later
                    // Checked before the toggle: a completing click that turns a lever off still counts (the lamp lags).
                    val wasLit = allLit()
                    val k = pos.x to pos.y
                    if (!left) {
                        if (!on.remove(k)) on += k
                        draw(if (Random.nextInt(6) == 0) 4 else 3)
                        Sim.sound(SoundEvents.LEVER_CLICK, 0.3f, if (k in on) 0.5873016f else 0.4920635f, Vec3.atCenterOf(pos), net.minecraft.sounds.SoundSource.BLOCKS)
                    }
                    // In S2, or pre-done from S1 (as the bots do it).
                    credit = phase.section in 1..2 && (wasLit || (!left && allLit()))
                }
                // A left click is processed twice on Hypixel: the completion line goes out twice in one tick, counted once.
                Fight.later(2, "lights credit") { if (credit && phase === Fight.phase && !st.done) st.complete(Sim.me, twice = left) }
            }
            return true
        }
    }

    /** A LEFT click on a lever (SimItems.clientHitBlock): true if it is one of the P3 levers. */
    fun leftClick(pos: BlockPos): Boolean {
        phase.leverAt(pos)?.let { st -> Fight.afterPing("lever") { phase.pullLever(st, Sim.me, left = true) }; return true }
        return lights.use(pos, left = true)
    }

    // ------------------------------------------------------------------ Arrow Align

    /**
     * Arrow Align (S3): item frames at x=-2, y120-124, z75-79 (index (y-120) + (z-75)*5), only on
     * one of Odin's nine layouts' arrow cells plus its few extra (non-arrow) frames, never on the
     * other cells. It starts solved but for the arrow nearest the bottom left; a click turns one +1. Frames turn
     * any time (pre-dev); the device line comes in the same tick as the solving click, before S3 too.
     */
    inner class Arrows {
        private val frames = HashMap<Int, ItemFrame>()
        private var solution = SOLUTIONS[0]

        /** The board as it stood before terms ([showIdle]: frames turned since stay turned), else a new one. */
        fun place() {
            remove()
            val b = idleBoard?.takeIf { it.frames.values.none { f -> f.isRemoved } } ?: newBoard()
            idleBoard = null
            solution = SOLUTIONS[b.layout]
            frames.putAll(b.frames)
        }

        fun remove() { frames.values.forEach { it.discard() }; frames.clear() }

        /** The device is done (a click of yours, or a teammate): every frame cell of the layout lights. */
        private fun lightWall() {
            for (i in frames.keys) Blocks.set(-3, 120 + i % 5, 75 + i / 5, B.SEA_LANTERN.defaultBlockState())
        }

        /** A teammate's clicks, one frame per tick, not every frame in one tick. */
        fun solve() {
            lightWall()
            var delay = 0
            for ((i, f) in frames.entries.sortedBy { it.key }.map { it.key to it.value }) {
                if (solution[i] < 0 || f.rotation == solution[i]) continue
                Fight.later(delay++, "arrows solve") {
                    if (phase !== Fight.phase || !frames.containsValue(f)) return@later
                    f.setRotation(solution[i])
                    Sim.sound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 1f, 1f, f.position(), net.minecraft.sounds.SoundSource.PLAYERS)
                }
            }
        }

        /** A click on [frame]: true if it is one of ours. */
        fun use(frame: ItemFrame): Boolean {
            val i = frames.entries.firstOrNull { it.value === frame }?.key ?: return false
            Fight.afterPing("arrow") {
                val st = station("Arrows")
                if (st.done) return@afterPing
                // An extra (Start/End) frame turns as well; it never counts toward the solution.
                if (solution[i] < 0) {
                    frame.setRotation((frame.rotation + 1) % 8)
                    Sim.sound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 1f, 1f, frame.position(), net.minecraft.sounds.SoundSource.PLAYERS)
                    return@afterPing
                }
                frame.setRotation((frame.rotation + 1) % 8)
                Sim.sound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 1f, 1f, frame.position(), net.minecraft.sounds.SoundSource.PLAYERS)
                if (phase.section in 1..3 && frames.all { (j, f) -> solution[j] < 0 || f.rotation == solution[j] }) { lightWall(); st.complete(Sim.me) }
            }
            return true
        }

        fun owns(e: net.minecraft.world.entity.Entity) = frames.values.any { it === e }
    }

    // ------------------------------------------------------------------ the target

    /**
     * The target ("i4"): stand on the plate (63, 127, 35) and shoot the target block of the 3x3 at x64-68, y126-130,
     * z50. Live from P3's start, not only in S4 (an early finish counts in S4 via
     * GoldorPhase.complete). Each run is a random order of the 9 cells. As on Hypixel's main server:
     * the next cell is the target the moment the last one is hit, and a later
     * arrow in that same tick can hit it too (arrows tick in spawn order, so only a later one); but it only shows
     * (emerald) on a per-attempt 10-tick grid while someone is on the plate, 1-10 ticks after. A hit on the shown one
     * turns it blue at once. A target hit before it showed counts and
     * changes no block, so the board (and Odin's solver, which only sees blocks) never lights it: 7-8 lights for 9
     * hits is usual. Off the plate, on the grid: the lit one goes blue and progress resets. Done, the board stays
     * all blue terracotta (all emerald would read as 9 new targets to Odin).
     */
    inner class Target {
        val PLATE = BlockPos(63, 127, 35)
        private val blocks = (0 until 9).map { BlockPos(64 + (it % 3) * 2, 126 + (it / 3) * 2, 50) }
        /** The cell showing emerald, or -1. */
        private var lit = -1
        private var hits = 0
        private var order = (0 until 9).shuffled()
        /**
         * This attempt's grid phase: lights land on t ≡ grid (mod 10) server ticks, a new phase each attempt (Hypixel
         * recordings: 98% of lights on their attempt's phase, 5 of 8 back-to-back attempts on a different one).
         */
        private var grid = Random.nextInt(10)
        /**
         * This attempt's first emerald has shown. Before it, a strike on the (hidden) first target does not count, as
         * on Hypixel (4 of 22 recorded first targets struck early stayed unlit, 4.0 expected if ignored).
         */
        private var shownOnce = false

        fun place() { reset(); blocks.forEach { Blocks.set(it, B.BLUE_TERRACOTTA.defaultBlockState()) } }
        fun clear() { lit = -1; blocks.forEach { Blocks.set(it, B.BLUE_TERRACOTTA.defaultBlockState()) } }

        private fun reset() { lit = -1; hits = 0; order = (0 until 9).shuffled(); grid = Random.nextInt(10); shownOnce = false }

        /** The cell that is the target now (shown or not), or -1 when done. */
        private val current get() = if (hits < 9) order[hits] else -1

        private var held = false
        private var offAt = -1

        /** The plate's power: it releases 1-10 ticks after you step off (0-10 measured). */
        fun onPlate(): Boolean {
            if (rawOnPlate()) { held = true; offAt = -1 }
            else if (held) {
                if (offAt < 0) offAt = phase.t + 1 + Random.nextInt(10)
                if (phase.t >= offAt) { held = false; offAt = -1 }
            }
            return held
        }

        /** Who last stood on the plate: the device is theirs when it's done. */
        private var plateBy: String? = null

        /** The i4 bot on the plate (its name), doing it: the board runs for it as for a player. Set by Party each tick. */
        var botOn: String? = null

        /** The cell showing emerald now (to shoot at), or null. */
        val shown: BlockPos? get() = if (lit >= 0) blocks[lit] else null

        private fun rawOnPlate(): Boolean {
            botOn?.let { plateBy = it; return true }
            // A pressure plate: pressed while someone's box overlaps its block (feet within its lower quarter).
            val p = Sim.players().firstOrNull { pl ->
                val b = pl.boundingBox
                b.maxX > PLATE.x && b.minX < PLATE.x + 1 && b.maxZ > PLATE.z && b.minZ < PLATE.z + 1 && b.minY >= PLATE.y - 0.01 && b.minY < PLATE.y + 0.25
            } ?: return false
            plateBy = p.gameProfile.name
            return true
        }

        /**
         * End of each tick, for the next one: Hypixel lights before the arrows move, so a target drawn by a hit shows
         * 1-10 ticks later, never in the hit's own tick (a hit on a grid tick waits the full 10). The device itself is
         * never late: a hit counts on the current target whether it shows yet or not ([hit]), and a shown one goes
         * blue at once.
         */
        fun tick() {
            val st = station("Target")
            onPlate()
            if (st.done || phase.section > 4 || (phase.t + 1 - grid).mod(10) != 0) return
            if (onPlate()) {
                if (current >= 0 && lit != current) light()
            } else if (lit >= 0 || hits > 0) {
                if (lit >= 0) Blocks.set(blocks[lit], B.BLUE_TERRACOTTA.defaultBlockState())
                reset()
            }
        }

        private fun light() {
            lit = current
            shownOnce = true
            Blocks.set(blocks[lit], B.EMERALD_BLOCK.defaultBlockState())
        }

        /**
         * An arrow (or a bow's shot) hit block [pos]: if it is the target - shown or not yet - it counts, once the
         * attempt's first emerald has shown. Only a shown one goes back to blue. The arrow's own vanilla
         * `entity.arrow.hit` is the only sound.
         */
        fun hit(pos: BlockPos) {
            val st = station("Target")
            if (st.done || current < 0 || pos != blocks[current]) return
            // Nothing counts before the attempt's first emerald.
            if (!shownOnce) return
            // The board runs while someone is on the plate (or a target is up or under way).
            if (lit < 0 && hits == 0 && !onPlate()) return
            if (lit == current) { Blocks.set(pos, B.BLUE_TERRACOTTA.defaultBlockState()); lit = -1 }
            hits++
            if (hits >= 9) { clear(); st.complete(plateBy ?: Sim.me) }
        }

        val targets get() = blocks
    }

    companion object {
        /** An Arrow Align board up: its layout and frames (by index (y-120) + (z-75)*5). */
        class Board(val layout: Int, val frames: Map<Int, ItemFrame>)

        /** The board shown before terms ([showIdle]), for the next P3's Arrow Align to take over. */
        private var idleBoard: Board? = null

        /**
         * The 2nd and 3rd devices as a run has them before terms start (Lights pre-done, Arrow Align up but for one
         * arrow), at every Stop and start: not blank until Goldor's line (some do them right as terms start).
         */
        fun showIdle() {
            idleBoard?.frames?.values?.forEach { it.discard() }
            idleBoard = null
            val right = setOf(58 to 133, 58 to 136, 60 to 134, 60 to 135, 62 to 133, 62 to 136)
            for (x in 58..62) for (y in 133..136) {
                val lever = BlockPos(x, y, 142)
                val st = Blocks.get(lever)
                if (st != null && st.hasProperty(LeverBlock.POWERED)) Blocks.set(lever, st.setValue(LeverBlock.POWERED, (x to y) in right))
                val lit = listOf(x to y, x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { it in right }
                Blocks.set(BlockPos(x, y, 143), B.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, lit))
            }
            idleBoard = newBoard()
        }

        /**
         * A new Arrow Align board: frames only on one of Odin's nine layouts' arrow cells plus its few extra (non-arrow)
         * frames, all solved but the arrow nearest the bottom left; and its back wall (x -3, the board's own y/z): sea
         * lanterns on the layout's frame cells, blue terracotta elsewhere. At the start every wool cell is lit, an arrow
         * cell only about one in eight (in practice one already turned right), no other cell ever; it does not follow
         * clicks. On completion every frame cell (6-15 of them) goes lantern in one tick.
         */
        private fun newBoard(): Board {
            // Layouts 1-8 come about equally often; Odin's layout 0 has not been seen on Hypixel.
            val layout = (1 until SOLUTIONS.size).random()
            val solution = SOLUTIONS[layout]
            val frames = HashMap<Int, ItemFrame>()
            for (i in 0 until 25) {
                val wool = EXTRAS[layout][i]
                if (solution[i] < 0 && wool == null) continue
                val pos = BlockPos(-2, 120 + i % 5, 75 + i / 5)
                val f = ItemFrame(EntityType.ITEM_FRAME, Sim.level, pos, Direction.EAST)
                f.isInvulnerable = true
                if (solution[i] >= 0) {
                    f.setItem(ItemStack(Items.ARROW), false)
                    f.setRotation(solution[i])
                } else f.setItem(
                    // Hypixel names them: "Start" (green) on the lime wool, "End" (red) on the red, non-italic.
                    if (wool == true) Terminals.named(Items.LIME_WOOL, "Start", color = net.minecraft.ChatFormatting.GREEN)
                    else Terminals.named(Items.RED_WOOL, "End", color = net.minecraft.ChatFormatting.RED), false)
                frames[i] = Sim.spawn(f)
            }
            for (i in 0 until 25) {
                val lit = i in frames && (solution[i] < 0 || Random.nextInt(8) == 0)
                Blocks.set(-3, 120 + i % 5, 75 + i / 5, (if (lit) B.SEA_LANTERN else B.BLUE_TERRACOTTA).defaultBlockState())
            }
            // All solved but one: the arrow nearest the bottom left as you face it (y 120, z 79), one click off.
            frames.entries.filter { solution[it.key] >= 0 }.minByOrNull { (j, _) -> val dy = j % 5; val dz = 4 - j / 5; dy * dy + dz * dz }
                ?.let { (j, f) -> f.setRotation((solution[j] + 7) % 8) }
            return Board(layout, frames)
        }

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

        /**
         * Each layout's extra (non-arrow) frames, Odin index to their item: true = lime wool (a
         * path's start), false = red wool (its end). The same cells and wool in every run of a layout
         * Layout 0 has not been seen on Hypixel; it keeps layout 2's, whose shape it shares.
         */
        val EXTRAS: List<Map<Int, Boolean>> = listOf(
            mapOf(2 to false, 22 to false),
            mapOf(5 to true, 15 to true, 14 to false),
            mapOf(12 to true, 2 to false, 22 to false),
            mapOf(4 to true, 24 to true, 12 to false),
            mapOf(20 to true, 12 to false),
            mapOf(20 to true, 4 to false),
            mapOf(20 to true, 22 to true, 24 to true, 0 to false, 2 to false, 4 to false),
            mapOf(20 to true, 0 to false),
            mapOf(20 to true, 22 to true, 24 to true, 1 to false, 3 to false),
        )
    }
}
