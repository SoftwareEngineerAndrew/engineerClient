package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.leap.LeapExtras
import com.engineerclient.mixin.MinecraftAccessor
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.createSoundSettings
import com.odtheking.odin.utils.playSoundSettings
import com.odtheking.odin.utils.render.drawStyledBox
import net.minecraft.world.phys.AABB
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.lwjgl.glfw.GLFW
import java.util.Locale

/**
 * SS Practice: F7's first device, Simon Says, summoned in front of you with a keybind (press it
 * again to take it away), to practice anywhere. Entirely client side: the blocks are set in your
 * own copy of the world only, and clicks on them are handled here and cancelled before the game
 * would send anything (no use, no swing, no mining packets).
 *
 * The device is its 4x4 obsidian grid in a wall of black wool, with the start button on the wool
 * left of the grid, placed where it is from the spot healers stand on to do it (108, 120, 94 in the
 * arena, facing +x) and turned to face whichever way you look. [solver] is Odin's Simon Says
 * solution drawn on it: Odin's own only works on the real one.
 *
 * How it plays, as measured in 208 Better PF recordings of P3 (see docs/mechanics/simon-says.md):
 *  - The start button is left of the grid. Presses in the 6 ticks after the first decide the
 *    first show: 1 press shows round 1; 2 or 3 show one stray light first, then the first 1 or 2
 *    of the sequence (the "skip": 3 presses, press 2, then rounds of 3, 4 and 5).
 *  - Lights: one every 8 ticks. Buttons: all 16 come back 10 ticks after the last light goes out;
 *    after a show that started with a stray light they come 5 ticks after the last light comes on,
 *    except that light's own button, which waits until 10 ticks after it goes out.
 *  - A pressed button stays down 3 ticks (pressing it again meanwhile does nothing).
 *  - The next round starts 6 ticks after the round's last correct press; after round 5 that is
 *    the device done. A wrong press: buttons gone 3 ticks later, and 25 ticks after it a new
 *    sequence, shown the way the skip shows it.
 */
object SimonSaysPractice : Module(
    name = "SS Practice",
    category = Category.custom("Engineer Client"),
    description = "Summons F7's first device (Simon Says) in front of you to practice it anywhere. Client side only: the blocks and your clicks never reach the server.",
) {
    private val summonKey by KeybindSetting("Summon Keybind", GLFW.GLFW_KEY_UNKNOWN, "Summons the device in front of you, and takes it away again. With Infinileap in your hand: Odin's numbers terminal simulator instead, one after another.").onPress { if (!LeapExtras.openNumbersSim()) summonOrRemove() }
    private val solver by BooleanSetting("Solver", true, desc = "Odin's Simon Says solution on the practice device: the button to press next green, the one after gold, the rest red. Each appears as its light goes out.")
    private val showSpeed by NumberSetting("Show Speed", 1.0, 1.0, 5.0, 0.25, desc = "How fast the lights are shown (1x = the game's 8 ticks each). Only the lights: the buttons still come back 10 ticks after the last light goes out (5 after it comes on, on a skip), as in the game.")
    private val clickSounds by BooleanSetting("Click Sounds", true, desc = "Odin's Simon Says click sounds: one for a right press (and the start button), another for a wrong one.")
    private val soundsDropdown by DropdownSetting("Click Sounds Dropdown").withDependency { clickSounds }
    private val correctSound = createSoundSettings("Correct Sound", "entity.experience_orb.pickup") { clickSounds && soundsDropdown }
    private val wrongSound = createSoundSettings("Wrong Sound", "entity.blaze.hurt") { clickSounds && soundsDropdown }
    private val roundTimes by BooleanSetting("Round Times", true, desc = "After each completion, a line per round: how long its clicking took (next to the fastest healers' medians from Better PF runs), and each press's time from the one before, the first from when its button came up.")

    // ------------------------------------------------------------------ the real device

    /** Where you stand in the arena to do it (your feet), facing +x. */
    private const val AX = 108; private const val AY = 120; private const val AZ = 94
    private val START = BlockPos(110, 121, 91)
    /** The second start button, 2 above the first: starts Inf mode. */
    private val EXTRA get() = BlockPos(110, 123, 91)
    /** Cell 0-15: row from the top (y 123 down), column from z 92. */
    private fun buttonAt(cell: Int) = BlockPos(110, 123 - cell / 4, 92 + cell % 4)
    private fun lampAt(cell: Int) = BlockPos(111, 123 - cell / 4, 92 + cell % 4)

    /**
     * The goal, first light to done: a good legit time, under the death tick at 12 s (some of the
     * fastest runs on Better PF weren't legit). Then each round's clicking: the fastest healers'
     * medians there, which with the fixed 7.4 s and a 0.3 s start add up to the goal.
     */
    private const val GOAL = 11.90
    private const val DEATH_TICK = 12.0
    private val TOP_ROUNDS = doubleArrayOf(1.10, 0.80, 1.05, 1.25)

    // Odin's Simon Says colours.
    private val FIRST = Colors.MINECRAFT_GREEN.withAlpha(0.5f)
    private val SECOND = Colors.MINECRAFT_GOLD.withAlpha(0.5f)
    private val THIRD = Colors.MINECRAFT_RED.withAlpha(0.5f)

    private val BUTTON: BlockState by lazy { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:stone_button[face=wall,facing=west,powered=false]", false).blockState() }

    // ------------------------------------------------------------------ placement

    /** Only ever sets blocks in this client's copy of the world: no shape updates, nothing sent. */
    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

    private class Placement(val level: ClientLevel, val origin: BlockPos, val forward: Direction) {
        val right: Direction = forward.clockWise
        /** The real device faces -x (you look +x); turned to face you. */
        val rotation: Rotation = when (forward) {
            Direction.SOUTH -> Rotation.CLOCKWISE_90
            Direction.WEST -> Rotation.CLOCKWISE_180
            Direction.NORTH -> Rotation.COUNTERCLOCKWISE_90
            else -> Rotation.NONE
        }
        /** The world block for a block of the real device's frame, relative to where you stand. */
        fun at(dx: Int, dy: Int, dz: Int): BlockPos =
            origin.offset(forward.stepX * dx + right.stepX * dz, dy, forward.stepZ * dx + right.stepZ * dz)
        fun at(real: BlockPos) = at(real.x - AX, real.y - AY, real.z - AZ)
        /** What was there before: put back on removal. */
        val saved = LinkedHashMap<BlockPos, BlockState>()
        fun setWorld(pos: BlockPos, state: BlockState) {
            saved.putIfAbsent(pos.immutable(), level.getBlockState(pos))
            level.setBlock(pos, state.rotate(rotation), FLAGS)
        }
        fun set(real: BlockPos, state: BlockState) = setWorld(at(real), state)
        /** A box given in the real device's frame, turned into the world like the blocks are. */
        fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): AABB {
            fun wx(x: Double, z: Double) = origin.x + 0.5 + forward.stepX * (x - AX - 0.5) + right.stepX * (z - AZ - 0.5)
            fun wz(x: Double, z: Double) = origin.z + 0.5 + forward.stepZ * (x - AX - 0.5) + right.stepZ * (z - AZ - 0.5)
            val y = origin.y - AY
            return AABB(wx(x0, z0), y0 + y, wz(x0, z0), wx(x1, z1), y1 + y, wz(x1, z1))
        }
    }

    private var placed: Placement? = null

    private fun summonOrRemove() {
        if (placed != null) { remove(); EngineerClient.msg("§7SS Practice: removed."); return }
        val player = mc.player ?: return
        val level = mc.level ?: return
        val p = Placement(level, player.blockPosition(), player.direction)
        EngineerClient.safely("ss practice summon") {
            // Clear the space between you and the buttons first, so nothing blocks a click.
            for (dx in 0..2) for (dy in 0..3) for (dz in -4..3) p.setWorld(p.at(dx, dy, dz), Blocks.AIR.defaultBlockState())
            // The wall: the grid (x 111, y 120-123, z 92-95) in a ring of black wool, start button on its left.
            for (y in 119..124) for (z in 91..96) {
                val grid = y in 120..123 && z in 92..95
                p.set(BlockPos(111, y, z), if (grid) Blocks.OBSIDIAN.defaultBlockState() else Blocks.BLACK_WOOL.defaultBlockState())
            }
            p.set(START, BUTTON)
            p.set(EXTRA, BUTTON)
        }
        placed = p
        reset()
        markMode(inf = false)
        EngineerClient.msg("§7SS Practice: summoned. Press the start button §8(left of the grid)§7 to begin, 3 times for the skip; the one above it for Inf mode. The keybind again takes it away.")
    }

    private fun remove() {
        val p = placed ?: return
        placed = null
        reset()
        if (p.level !== mc.level) return
        EngineerClient.safely("ss practice remove") { for ((pos, state) in p.saved) p.level.setBlock(pos, state, FLAGS) }
    }

    // ------------------------------------------------------------------ the device

    private enum class Phase { IDLE, STARTING, RUNNING, DONE }

    private class Job(val at: Long, val gen: Int, val run: () -> Unit)

    private var tick = 0L
    private var gen = 0
    private val jobs = ArrayList<Job>()
    private fun after(ticks: Int, run: () -> Unit) { jobs += Job(tick + ticks, gen, run) }

    private var phase = Phase.IDLE
    private val rng = java.util.Random()
    private var sequence = IntArray(5)
    private var expected: List<Int> = emptyList()
    private var next = 0
    private var accepting = false
    private val buttonUp = BooleanArray(16)
    private val downUntil = LongArray(16)
    private var startPresses = 0
    private var firstLight = 0L
    private var roundUp = 0L
    private val rounds = ArrayList<Double>()
    /** Each finished round's presses: seconds from the one before, the first from when its button came up. */
    private val splits = ArrayList<List<Double>>()
    private val roundClicks = ArrayList<Double>()
    private var lastClickMs = 0L
    /** The tick the 4-button round's last press went in (0: not yet this run). */
    private var r4Done = 0L
    /** Ticks Show Speed took off this run's shows: added back, the times are as at 1x. */
    private var shownFaster = 0L
    /** The solver's list: this round's cells, each added as its light goes out (a stray light never). */
    private val revealed = ArrayList<Int>()
    private var fails = 0

    /** Everything back to a dark, buttonless device, waiting for the start button. */
    private fun reset() {
        stopInf()
        gen++
        jobs.clear()
        phase = Phase.IDLE
        accepting = false
        revealed.clear()
        placed?.let { p -> for (c in 0 until 16) { p.set(lampAt(c), Blocks.OBSIDIAN.defaultBlockState()); setButton(c, false) } }
    }

    private fun setButton(cell: Int, up: Boolean, pressed: Boolean = false) {
        val p = placed ?: return
        buttonUp[cell] = up
        p.set(buttonAt(cell), if (up) BUTTON.setValue(ButtonBlock.POWERED, pressed) else Blocks.AIR.defaultBlockState())
    }

    private fun light(cell: Int, on: Boolean) {
        placed?.set(lampAt(cell), if (on) Blocks.SEA_LANTERN.defaultBlockState() else Blocks.OBSIDIAN.defaultBlockState())
    }

    private fun newSequence() {
        val cells = (0 until 16).shuffled(rng)
        sequence = IntArray(5) { cells[it] }
    }

    /** A cell that isn't [not], for the stray first light. */
    private fun stray(not: Int): Int { var c: Int; do c = rng.nextInt(16) while (c == not); return c }

    /**
     * Shows [cells] one every 8 ticks, then brings the buttons back for [expect] to be pressed.
     * [stray]: the show starts with a light that isn't part of it (the skip's way).
     */
    private fun show(cells: List<Int>, expect: List<Int>, stray: Boolean) {
        accepting = false
        for (c in 0 until 16) setButton(c, false)
        expected = expect; next = 0
        revealed.clear()
        val n = cells.size
        val out = { i: Int -> light(cells[i], false); if (!(stray && i == 0)) revealed += cells[i] }
        // Light i comes on at [at] i: 8 ticks apart, divided by Show Speed. Rounded to whole ticks
        // from the start (not per gap), so the pace is right on average; the buttons' timings
        // below count from the last light, never sped up.
        val at = { i: Int -> Math.round(8 * i / showSpeed).toInt() }
        for (i in 0 until n) after(at(i)) {
            if (i > 0) out(i - 1)
            light(cells[i], true)
            if (firstLight == 0L) firstLight = tick
        }
        after(at(n)) { out(n - 1) }
        val open = {
            accepting = true; roundUp = tick
            roundClicks.clear(); lastClickMs = System.currentTimeMillis()
        }
        if (stray) {
            after(at(n - 1) + 5) { for (c in 0 until 16) if (c != cells.last()) setButton(c, true); open() }
            // The lit cell's: 10 ticks after its light would go out at 1x (13 after the rest), so
            // the clicking is the same at any speed.
            after(at(n - 1) + 8 + 10) { setButton(cells.last(), true) }
            shownFaster += 8 * (n - 1) - at(n - 1)
        } else {
            after(at(n) + 10) { for (c in 0 until 16) setButton(c, true); open() }
            shownFaster += 8 * n - at(n)
        }
    }

    /** The last run's start presses, for a restart from the grid (left click) to start the same way. */
    private var lastStartPresses = 3

    private fun begin() {
        phase = Phase.RUNNING
        lastStartPresses = startPresses.coerceAtMost(3)
        newSequence()
        val s = sequence
        when (startPresses.coerceAtMost(3)) {
            1 -> show(listOf(s[0]), listOf(s[0]), stray = false)
            2 -> show(listOf(stray(s[0]), s[0]), listOf(s[0]), stray = true)
            else -> show(listOf(stray(s[0]), s[0], s[1]), listOf(s[0], s[1]), stray = true)
        }
    }

    private fun restart() {
        if (placed == null) return
        if (Inf.on) { startInf(); return }
        reset()
        phase = Phase.STARTING
        startPresses = lastStartPresses; firstLight = 0L; shownFaster = 0L; rounds.clear(); splits.clear(); r4Done = 0L; fails = 0
        if (clickSounds) playSoundSettings(correctSound())
        after(6) { begin() }
    }

    // ------------------------------------------------------------------ Inf mode

    /**
     * Inf mode (the upper start button): every button up, no lights, and always three to press,
     * highlighted green, gold, red like the solver. The order comes in bags of all 16, so each
     * button comes up once before any again; a new bag starts with [Inf.FRESH] buttons that
     * aren't highlighted (nor just pressed). Its own object, so its state is set up when first
     * used (hotswap-friendly too).
     */
    private object Inf {
        const val SHOWN = 3
        const val FRESH = 6
        var on = false
        val queue = ArrayList<Int>()
        val bag = ArrayDeque<Int>()
        var lastPressed = -1
        var lastMs = 0L
        val gaps = ArrayList<Long>()
    }

    private fun startInf() {
        val p = placed ?: return
        reset()
        markMode(inf = true)
        Inf.on = true
        Inf.queue.clear(); Inf.bag.clear(); Inf.gaps.clear(); Inf.lastPressed = -1; Inf.lastMs = 0L
        while (Inf.queue.size < Inf.SHOWN) Inf.queue += infPick()
        for (c in 0 until 16) setButton(c, true)
        if (clickSounds) playSoundSettings(correctSound())
    }

    /** Ends Inf mode (by any other start, a restart, removing the device), with its pace in chat. */
    private fun stopInf() {
        if (!Inf.on) return
        Inf.on = false
        if (Inf.gaps.isEmpty()) return
        val avg = Inf.gaps.average() / 1000.0
        EngineerClient.msg("§7Inf SS: §f${Inf.gaps.size + 1}§7 presses §8· §f${String.format(Locale.ROOT, "%.3f", avg)}s§7 between")
    }

    private fun infPick(): Int {
        if (Inf.bag.isEmpty()) {
            val fresh = (0 until 16).filter { it !in Inf.queue && it != Inf.lastPressed }.shuffled(rng).take(Inf.FRESH)
            Inf.bag += fresh
            Inf.bag += (0 until 16).filter { it !in fresh }.shuffled(rng)
        }
        return Inf.bag.removeFirst()
    }

    private fun infPress(cell: Int) {
        if (cell != Inf.queue.firstOrNull()) { if (clickSounds) playSoundSettings(wrongSound()); return }
        if (clickSounds) playSoundSettings(correctSound())
        val now = System.currentTimeMillis()
        if (Inf.lastMs != 0L) Inf.gaps += now - Inf.lastMs
        Inf.lastMs = now
        Inf.lastPressed = cell
        Inf.queue.removeAt(0)
        Inf.queue += infPick()
    }

    /** Light grey wool behind the start button of the mode you're in, black behind the other. */
    private fun markMode(inf: Boolean) {
        val p = placed ?: return
        val grey = Blocks.LIGHT_GRAY_WOOL.defaultBlockState(); val black = Blocks.BLACK_WOOL.defaultBlockState()
        p.set(START.east(), if (inf) black else grey)
        p.set(EXTRA.east(), if (inf) grey else black)
    }

    private fun pressExtra() {
        val p = placed ?: return
        startInf()
        // After the reset in startInf, so it doesn't cancel the button coming back up.
        click(p.at(EXTRA))
        p.set(EXTRA, BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(EXTRA, BUTTON) }
    }

    private fun pressStart() {
        val p = placed ?: return
        when (phase) {
            // Mid-run it's a restart: the lights and buttons go and it starts over, as from idle.
            Phase.IDLE, Phase.DONE, Phase.RUNNING -> {
                reset()
                phase = Phase.STARTING
                startPresses = 1; firstLight = 0L; shownFaster = 0L; rounds.clear(); splits.clear(); r4Done = 0L; fails = 0
                after(6) { begin() }
            }
            Phase.STARTING -> startPresses++
        }
        // After the reset above, so it doesn't cancel the button coming back up.
        markMode(inf = false)
        click(p.at(START))
        if (clickSounds) playSoundSettings(correctSound())
        p.set(START, BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(START, BUTTON) }
    }

    private fun press(cell: Int) {
        val p = placed ?: return
        if (!buttonUp[cell] || downUntil[cell] > tick) return
        click(p.at(buttonAt(cell)))
        setButton(cell, true, pressed = true)
        downUntil[cell] = tick + 3
        after(3) { if (buttonUp[cell]) setButton(cell, true) }
        if (Inf.on) { infPress(cell); return }
        if (!accepting) return
        if (cell == expected[next]) {
            if (clickSounds) playSoundSettings(correctSound())
            val now = System.currentTimeMillis()
            roundClicks += (now - lastClickMs) / 1000.0; lastClickMs = now
            next++
            if (next < expected.size) return
            accepting = false
            rounds += (tick + 6 - roundUp) / 20.0
            splits += roundClicks.toList()
            if (expected.size == 4) r4Done = tick + shownFaster
            val n = expected.size
            if (n == 5) after(6) { for (c in 0 until 16) setButton(c, false); revealed.clear(); done() }
            else after(6) { val cells = sequence.take(n + 1); show(cells, cells, stray = false) }
        } else {
            if (clickSounds) playSoundSettings(wrongSound())
            accepting = false
            fails++
            revealed.clear()
            after(3) { for (c in 0 until 16) setButton(c, false) }
            after(25) {
                newSequence()
                val s = sequence
                show(listOf(stray(s[0]), s[0], s[1]), listOf(s[0], s[1]), stray = true)
                rounds.clear(); splits.clear(); r4Done = 0L
            }
        }
    }

    private fun done() {
        phase = Phase.DONE
        // As at 1x: the time Show Speed saved added back.
        val total = (tick + shownFaster - firstLight) / 20.0
        // Green: the goal. Yellow: still before the death tick. Red: after it.
        val colour = if (total <= GOAL) "§a" else if (total < DEATH_TICK) "§e" else "§c"
        // In brackets, first light to r4's last press.
        val r4 = if (r4Done != 0L) " §7(${fmt((r4Done - firstLight) / 20.0)}s)" else ""
        val speed = if (showSpeed != 1.0) " §8(${fmt(showSpeed).trimEnd('0').trimEnd('.')}x, as at 1x)" else ""
        EngineerClient.msg("§7SS took: $colour${fmt(total)}s$r4" + (if (fails > 0) " §c$fails wrong" else "") + speed)
        if (!roundTimes) return
        // One line a round: its clicking time (vs the top healers' median, with the skip start),
        // then each press, the first from when its button came up, the rest from the press before.
        val vsTop = rounds.size == TOP_ROUNDS.size
        for (i in rounds.indices) {
            val presses = splits.getOrNull(i).orEmpty()
            val name = if (vsTop && i == 0) "skip" else "r${presses.size}"
            val c = if (!vsTop) "§f" else if (rounds[i] <= TOP_ROUNDS[i]) "§a" else if (rounds[i] <= TOP_ROUNDS[i] + 0.3) "§e" else "§c"
            val top = if (vsTop) "§8/${fmt(TOP_ROUNDS[i])}" else ""
            EngineerClient.msg("§8 ${name.padEnd(4)} $c${fmt(rounds[i])}$top §8| " + presses.withIndex().joinToString(" §8› ") { (j, t) -> pressColour(name == "skip", j, t) + fmt(t) })
        }
    }

    /**
     * A press's colour: the first of a round (from its button coming up) by reaction, the rest by
     * the move between buttons. The skip round's stay grey: when its buttons come up is too random.
     */
    private fun pressColour(skip: Boolean, index: Int, t: Double) = when {
        skip -> "§7"
        index == 0 -> if (t <= 0.10) "§a" else if (t <= 0.20) "§e" else "§c"
        else -> if (t <= 0.25) "§2" else if (t <= 0.35) "§e" else "§c"
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    /** The click you'd hear from the real button, in your own ears only. */
    private fun click(pos: BlockPos) {
        mc.level?.playLocalSound(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.3f, 0.6f, false)
    }

    // ------------------------------------------------------------------ input (from the mixin)

    /** The block you're looking at, if it's part of the practice device. */
    private fun target(): BlockPos? {
        val p = placed ?: return null
        val hit = mc.hitResult as? BlockHitResult ?: return null
        if (hit.type != HitResult.Type.BLOCK) return null
        return hit.blockPos.takeIf { it in p.saved }
    }

    /** Right click. True: it was on the device, handled here, and the game must not use it. */
    @JvmStatic
    fun onUse(): Boolean {
        val pos = target() ?: return false
        val p = placed ?: return false
        val button = mc.level?.getBlockState(pos)?.block is ButtonBlock
        EngineerClient.safely("ss practice use") {
            if (pos == p.at(START)) pressStart()
            else if (pos == p.at(EXTRA)) pressExtra()
            else (0 until 16).firstOrNull { p.at(buttonAt(it)) == pos }?.let { press(it) }
        }
        // Your arm moves for a button, as in the game; not for the obsidian or wool. Nothing is sent.
        if (button) mc.player?.swing(InteractionHand.MAIN_HAND, false)
        (mc as MinecraftAccessor).`ec$setRightClickDelay`(4) // holding right click repeats like the game's own
        return true
    }

    /** Left click on the device: nothing (like the real one), and nothing sent. */
    @JvmStatic
    fun onAttack(): Boolean {
        val pos = target() ?: return false
        val p = placed ?: return false
        // A left click on the grid (obsidian, lantern or button) restarts: a new run, started as the last one was.
        if ((0 until 16).any { p.at(lampAt(it)) == pos || p.at(buttonAt(it)) == pos }) EngineerClient.safely("ss practice restart") { restart() }
        mc.player?.swing(InteractionHand.MAIN_HAND, false)
        return true
    }

    /** Holding left click on the device: no mining. */
    @JvmStatic
    fun blocksContinueAttack(): Boolean = target() != null

    init {
        on<TickEvent.End> {
            tick++
            if (jobs.isEmpty()) return@on
            val due = jobs.filter { it.at <= tick }
            jobs.removeAll(due.toSet())
            for (j in due) if (j.gen == gen) EngineerClient.safely("ss practice") { j.run() }
        }
        on<RenderEvent.Extract> {
            val p = placed ?: return@on
            if (!solver || p.level !== mc.level) return@on
            if (Inf.on) {
                for ((i, cell) in Inf.queue.withIndex()) {
                    val lamp = lampAt(cell)
                    drawStyledBox(p.box(lamp.x - 0.15, lamp.y + 0.37, lamp.z + 0.3, lamp.x + 0.05, lamp.y + 0.63, lamp.z + 0.7), when (i) { 0 -> FIRST; 1 -> SECOND; else -> THIRD }, 2, true)
                }
                return@on
            }
            for (i in next until revealed.size) {
                val colour = when (i) { next -> FIRST; next + 1 -> SECOND; else -> THIRD }
                // Odin's box: on the grid's face where the button sits, in the real device's frame.
                val lamp = lampAt(revealed[i])
                drawStyledBox(p.box(lamp.x - 0.15, lamp.y + 0.37, lamp.z + 0.3, lamp.x + 0.05, lamp.y + 0.63, lamp.z + 0.7), colour, 2, true)
            }
        }
        // A new world has none of it: nothing to put back.
        on<LevelEvent.Unload> { placed = null; gen++; jobs.clear(); phase = Phase.IDLE }
    }

    override fun onDisable() {
        super.onDisable()
        remove()
    }
}
