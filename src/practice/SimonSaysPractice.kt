package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.mixin.MinecraftAccessor
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.BlockInteractEvent
import com.odtheking.odin.events.BlockUpdateEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.features.impl.boss.SimonSays
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.createSoundSettings
import com.odtheking.odin.utils.playSoundSettings
import com.odtheking.odin.utils.render.BoxStyle
import com.odtheking.odin.utils.render.drawStyledBox
import net.minecraft.world.phys.AABB
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
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
 * How it plays, as measured from recordings of real P3 runs:
 *  - The start button is left of the grid. Presses in the 6 ticks after the first decide the
 *    first show: 1 press shows round 1; 2 or 3 show one stray light first, then the first 1 or 2
 *    of the sequence (the "skip": 3 presses, press 2, then rounds of 3 and 4).
 *  - Lights: one every 8 ticks. Buttons: all 16 come back 10 ticks after the last light goes out;
 *    after a show that started with a stray light they come 5 ticks after the last light comes on,
 *    except that light's own button, which waits until 10 ticks after it goes out.
 *  - A pressed button stays down 3 ticks (pressing it again meanwhile does nothing).
 *  - The next round starts 6 ticks after the round's last correct press; after round 4 (Hypixel's
 *    Oct 2026 update removed round 5) that is the device done. A wrong press: buttons gone 3 ticks later, and 25 ticks
 *    after it a new (in the game) sequence, shown the way the skip shows it. Practice: a wrong press
 *    restarts the run at once.
 */
object SimonSaysPractice : Module(
    name = "SS Practice",
    category = Category.custom("Engineer Client", 860, 10),
    description = "Summons F7's first device (Simon Says) in front of you to practice it anywhere. Client side only: the blocks and your clicks never reach the server.",
    key = null,
) {
    private val summonKey by KeybindSetting("Summon Keybind", GLFW.GLFW_KEY_UNKNOWN, "Summons the device in front of you, and takes it away again. With Infinileap in your hand: /termsim inf instead, the numbers that never ends.").onPress { if (!LeapNumbersSim.open()) summonOrRemove() }
    private val solver by BooleanSetting("Solver", true, desc = "Odin's Simon Says solution on the practice device: the button to press next green, the one after gold, the rest red. Each appears as its light goes out.")
    private val showSpeed by NumberSetting("Show Speed", 1.0, 1.0..3.0, 0.25, desc = "How fast the lights are shown (1x = the game's 8 ticks each). Only the lights: the buttons still come back 10 ticks after the last light goes out (5 after it comes on, on a skip), as in the game.").withDependency { !instantShow }
    private val instantShow by BooleanSetting("Instant Show", false, desc = "The whole sequence at once, no lights shown (the solver still marks it). Only the show: the buttons still come back 10 ticks after (5 on a skip, the lit one 18), as in the game. Times are as at 1x.")
    private val clickSounds by BooleanSetting("Click Sounds", true, desc = "Odin's Simon Says click sounds: one for a right press (and the start button), another for a wrong one. Here and on the real device in P3 (turns Odin's own Custom Click Sounds off).")
    private val soundsDropdown by DropdownSetting("Click Sounds Dropdown", desc = "").withDependency { clickSounds }
    private val correctSound = createSoundSettings("Correct Sound", "entity.experience_orb.pickup") { clickSounds && soundsDropdown }
    private val wrongSound = createSoundSettings("Wrong Sound", "entity.blaze.hurt") { clickSounds && soundsDropdown }
    private val roundTimes by BooleanSetting("Round Times", true, desc = "After each completion, a line per round: how long its clicking took (next to the fastest healers' medians from Better PF runs), and each press's time from the one before, the first from when its button came up.")
    private val realTimes by BooleanSetting("Real Device Times", true, desc = "The same total and round by round press times for F7's real Simon Says in P3, when you do it: your presses timed as you click, the device's lights and buttons as they arrive. Marked (real).")
    private val triggerBot by BooleanSetting("Trigger Bot", false, desc = "Practice device only (never the real one): presses the button under your crosshair the moment it's the one to press next.")

    // ------------------------------------------------------------------ the real device

    /** Where you stand in the arena to do it (your feet), facing +x. */
    private const val AX = 108; private const val AY = 120; private const val AZ = 94
    private val START = BlockPos(110, 121, 91)
    /** The Inf mode button, just below the start one. */
    private val EXTRA get() = BlockPos(110, 120, 91)
    /** The buttons above the start one: start on round [n] (3, 4), on repeat. */
    private fun fromButton(n: Int) = BlockPos(110, 119 + n, 91)
    /** Cell 0-15: row from the top (y 123 down), column from z 92. */
    private fun buttonAt(cell: Int) = BlockPos(110, 123 - cell / 4, 92 + cell % 4)
    private fun lampAt(cell: Int) = BlockPos(111, 123 - cell / 4, 92 + cell % 4)

    /** Reference times for each round's clicking (skip, r3, r4): the fastest healers' medians from Better PF runs. */
    private val TOP_ROUNDS = doubleArrayOf(1.10, 0.80, 1.05)

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

    /** The practice device is out (its blocks are client-side, and not the real one's). */
    val practicing: Boolean get() = placed != null

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
                p.set(BlockPos(111, y, z), if (grid) Blocks.OBSIDIAN.defaultBlockState() else Blocks.WOOL.black().defaultBlockState())
            }
            p.set(START, BUTTON)
            p.set(EXTRA, BUTTON)
            for (n in 3..4) p.set(fromButton(n), BUTTON)
            // A sign on the side of each button's block (its north face, left of the button).
            sign(p, EXTRA, "Inf", "")
            sign(p, START, "Start", "3x for the skip")
            for (n in 3..4) sign(p, fromButton(n), "Start on r$n", "on repeat")
            // Right of the grid: how tight the patterns are.
            for (level in -1..2) {
                p.set(rngButton(level), BUTTON)
                val (a, b) = when (level) { -1 -> "Harder" to "spread out"; 0 -> "Normal RNG" to "random"; 1 -> "Easier" to "tighter"; else -> "Easiest" to "tightest" }
                sign(p, rngButton(level), a, b, Direction.SOUTH)
            }
            // On top, above the grid's left edge: the Full Block toggle (grey when on).
            p.set(FULL_BUTTON, BUTTON)
            topSign(p, FULL_BUTTON, "Full Block", "hitboxes")
        }
        placed = p
        gridCells = (0 until 16).map { p.at(buttonAt(it)) }.toSet()
        reset()
        markMode(0)
        markRng()
        markToggle()
        EngineerClient.msg("§7SS Practice: summoned. Press the start button §8(left of the grid)§7 to begin, 3 times for the skip. The others are signed. The keybind again takes it away.")
    }

    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

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
    private var sequence = IntArray(FINAL_ROUND)
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
    /** Ticks Show Speed took off this run's shows: added back, the times are as at 1x. */
    private var shownFaster = 0L
    /** The solver's list: this round's cells, each added as its light goes out (a stray light never). */
    private val revealed = ArrayList<Int>()
    private var fails = 0

    /** Everything back to a dark, buttonless device, waiting for the start button. */
    private fun reset() {
        stopInf()
        fromRound = 0
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

    /**
     * [FINAL_ROUND] different cells. Normal RNG: any order. Otherwise each after the first is picked with a
     * weight by its distance (in cells) from the one before: tighter levels favour near cells
     * (Easier e^-d, Easiest e^-2d), Harder far ones (e^d).
     */
    private fun newSequence() {
        if (rngLevel == 0) {
            val cells = (0 until 16).shuffled(rng)
            sequence = IntArray(FINAL_ROUND) { cells[it] }
            return
        }
        val k = when (rngLevel) { -1 -> -1.0; 1 -> 1.0; else -> 2.0 }
        val out = ArrayList<Int>()
        out += rng.nextInt(16)
        while (out.size < FINAL_ROUND) {
            val last = out.last()
            val left = (0 until 16).filter { it !in out }
            val w = left.map { c -> Math.exp(-k * Math.hypot((c / 4 - last / 4).toDouble(), (c % 4 - last % 4).toDouble())) }
            var r = rng.nextDouble() * w.sum()
            var pick = left.last()
            for (i in left.indices) { r -= w[i]; if (r <= 0) { pick = left[i]; break } }
            out += pick
        }
        sequence = out.toIntArray()
    }

    /** Pattern tightness: -1 Harder (spread), 0 Normal RNG, 1 Easier, 2 Easiest. Kept across runs. */
    private var rngLevel = 0

    /** The RNG buttons, right of the grid (on the wall's wool at z 96): Normal RNG level with the start one. */
    private fun rngButton(level: Int) = BlockPos(110, 121 + level, 96)

    /** The Full Block toggle, on top of the wall above the grid's left edge. */
    private val FULL_BUTTON = BlockPos(110, 124, 92)

    /** Full Block: the grid's buttons click as the whole face of their block (still a button's depth). */
    private var fullBlock = false
    /** The grid buttons' world positions while placed, for [fullBlockShape]. */
    private var gridCells: Set<BlockPos> = emptySet()

    /** The last round of a run: 4 since Hypixel's Oct 2026 update removed round 5. */
    private const val FINAL_ROUND = 4

    /** Light grey wool behind the Full Block toggle while it's on, black when off. */
    private fun markToggle() {
        val p = placed ?: return
        p.set(FULL_BUTTON.east(), if (fullBlock) Blocks.WOOL.lightGray().defaultBlockState() else Blocks.WOOL.black().defaultBlockState())
    }

    /** Flips Full Block (at once). */
    private fun pressToggle() {
        val p = placed ?: return
        fullBlock = !fullBlock
        markToggle()
        click(p.at(FULL_BUTTON))
        if (clickSounds) playSoundSettings(correctSound())
        p.set(FULL_BUTTON, BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(FULL_BUTTON, BUTTON) }
    }

    /** Odin's solver box for a cell (in front of its lamp): the button's size, or the whole face with Full Block. */
    private fun solverBox(p: Placement, lamp: BlockPos): AABB =
        if (fullBlock) p.box(lamp.x - 0.15, lamp.y + 0.0, lamp.z + 0.0, lamp.x + 0.05, lamp.y + 1.0, lamp.z + 1.0)
        else p.box(lamp.x - 0.15, lamp.y + 0.37, lamp.z + 0.3, lamp.x + 0.05, lamp.y + 0.63, lamp.z + 0.7)

    /**
     * For ButtonBlock.getShape: with Full Block on, a practice grid button is the whole face of its
     * block (16x16) at a button's depth (2 px, 1 pressed), against the block it's on. Else null.
     */
    @JvmStatic
    fun fullBlockShape(state: BlockState, pos: BlockPos): net.minecraft.world.phys.shapes.VoxelShape? {
        if (!fullBlock || placed == null || pos !in gridCells) return null
        val d = if (state.getValue(ButtonBlock.POWERED)) 1.0 else 2.0
        return when (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction.WEST -> Block.box(16 - d, 0.0, 0.0, 16.0, 16.0, 16.0)
            Direction.EAST -> Block.box(0.0, 0.0, 0.0, d, 16.0, 16.0)
            Direction.NORTH -> Block.box(0.0, 0.0, 16 - d, 16.0, 16.0, 16.0)
            else -> Block.box(0.0, 0.0, 0.0, 16.0, 16.0, d)
        }
    }

    /** A standing sign on top of the wool block [button] is on, facing you. */
    private fun topSign(p: Placement, button: BlockPos, line1: String, line2: String) {
        val real = button.east().above()
        p.set(real, Blocks.OAK_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION, 4))
        val be = p.level.getBlockEntity(p.at(real)) as? net.minecraft.world.level.block.entity.SignBlockEntity ?: return
        be.setText(net.minecraft.world.level.block.entity.SignText()
            .setMessage(1, net.minecraft.network.chat.Component.literal(line1))
            .setMessage(2, net.minecraft.network.chat.Component.literal(line2)), true)
    }

    /** Light grey wool behind the selected RNG button, black behind the others. */
    private fun markRng() {
        val p = placed ?: return
        for (level in -1..2) p.set(rngButton(level).east(), if (level == rngLevel) Blocks.WOOL.lightGray().defaultBlockState() else Blocks.WOOL.black().defaultBlockState())
    }

    /** Picks a pattern tightness; it counts from the next sequence on. */
    private fun pressRng(level: Int) {
        val p = placed ?: return
        rngLevel = level
        markRng()
        click(p.at(rngButton(level)))
        if (clickSounds) playSoundSettings(correctSound())
        p.set(rngButton(level), BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(rngButton(level), BUTTON) }
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
        val at = { i: Int -> if (instantShow) 0 else Math.round(8 * i / showSpeed).toInt() }
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

    private fun restart(sound: Boolean = true) {
        if (placed == null) return
        if (Inf.on) { startInf(); return }
        if (fromRound > 0) { startFrom(fromRound, sound); return }
        reset()
        phase = Phase.STARTING
        startPresses = lastStartPresses; firstLight = 0L; shownFaster = 0L; rounds.clear(); splits.clear(); fails = 0
        if (sound && clickSounds) playSoundSettings(correctSound())
        after(6) { begin() }
    }

    // ------------------------------------------------------------------ Inf mode

    /**
     * Inf mode (the button below the start one): every button up, no lights, and always three to
     * press, highlighted green, gold, red like the solver. The order comes in bags of all 16, so each
     * button comes up once before any again; a new bag starts with [Inf.FRESH] buttons that
     * aren't highlighted (nor just pressed). Its own object, so its state is set up when first used.
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
        markMode(1)
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

    /** Light grey wool behind the button of the mode you're in (0 start, 1 Inf, 3-4 start on rN), black behind the others. */
    private fun markMode(mode: Int) {
        val p = placed ?: return
        val grey = Blocks.WOOL.lightGray().defaultBlockState(); val black = Blocks.WOOL.black().defaultBlockState()
        p.set(START.east(), if (mode == 0) grey else black)
        p.set(EXTRA.east(), if (mode == 1) grey else black)
        for (n in 3..4) p.set(fromButton(n).east(), if (mode == n) grey else black)
    }

    /** A wall sign on the [side] (north: left, south: right) of the wool block [button] is on. */
    private fun sign(p: Placement, button: BlockPos, line1: String, line2: String, side: Direction = Direction.NORTH) {
        val real = button.east().relative(side)
        p.set(real, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.WallSignBlock.FACING, side))
        val be = p.level.getBlockEntity(p.at(real)) as? net.minecraft.world.level.block.entity.SignBlockEntity ?: return
        be.setText(net.minecraft.world.level.block.entity.SignText()
            .setMessage(1, net.minecraft.network.chat.Component.literal(line1))
            .setMessage(2, net.minecraft.network.chat.Component.literal(line2)), true)
    }

    // ------------------------------------------------------------------ Start on r3 / r4

    /**
     * Start-on-round mode (the buttons above the start one): rounds [fromRound] to 4, over and
     * over, a new sequence each time; round n shows the sequence's first n lights, the buttons back
     * 10 ticks after the last goes out, as in the game. The next run starts 6 ticks after your last
     * press (a wrong press: at once). Each run's rounds go in chat. 0: not in this mode.
     */
    private var fromRound = 0

    private fun startFrom(n: Int, sound: Boolean = true) {
        reset()
        fromRound = n
        markMode(n)
        phase = Phase.RUNNING
        if (sound && clickSounds) playSoundSettings(correctSound())
        after(6) { fromRun() }
    }

    private fun fromRun() {
        newSequence()
        firstLight = 0L; shownFaster = 0L; rounds.clear(); splits.clear(); fails = 0
        val cells = sequence.take(fromRound)
        show(cells, cells, stray = false)
    }

    /** One finished run in start-on-round mode: a line a round, vs the top healers' medians. */
    private fun fromDone() {
        for (i in rounds.indices) {
            val r = fromRound + i
            val t = rounds[i]
            val top = TOP_ROUNDS.getOrNull(r - 2) ?: continue
            val c = if (t <= top) "§a" else if (t <= top + 0.3) "§e" else "§c"
            val presses = splits.getOrNull(i).orEmpty()
            EngineerClient.msg("§8 r$r   $c${fmt(t)}§8/${fmt(top)} §8| " + presses.withIndex().joinToString(" §8› ") { (j, x) -> pressColour(false, j, x) + fmt(x) })
        }
        if (rounds.size > 1) EngineerClient.msg("§7 r$fromRound-r$FINAL_ROUND clicking: §f${fmt(rounds.sum())}s")
    }

    private fun pressFrom(n: Int) {
        val p = placed ?: return
        startFrom(n)
        // After the reset in startFrom, so it doesn't cancel the button coming back up.
        click(p.at(fromButton(n)))
        p.set(fromButton(n), BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(fromButton(n), BUTTON) }
    }

    private fun pressExtra() {
        val p = placed ?: return
        startInf()
        // After the reset in startInf, so it doesn't cancel the button coming back up.
        click(p.at(EXTRA))
        p.set(EXTRA, BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(EXTRA, BUTTON) }
    }

    /** The tick of a run's first start press; presses within [LATE_START_TICKS] of it never restart. */
    private var startedAt = 0L
    private val LATE_START_TICKS get() = 20

    private fun pressStart() {
        val p = placed ?: return
        when {
            phase == Phase.STARTING -> startPresses++
            // A late press of the start spam (after the 6 ticks that count, as in the game): nothing.
            phase == Phase.RUNNING && tick - startedAt < LATE_START_TICKS -> {}
            // Mid-run it's a restart: the lights and buttons go and it starts over, as from idle.
            else -> {
                reset()
                phase = Phase.STARTING
                startedAt = tick
                startPresses = 1; firstLight = 0L; shownFaster = 0L; rounds.clear(); splits.clear(); fails = 0
                after(6) { begin() }
            }
        }
        // After the reset above, so it doesn't cancel the button coming back up.
        markMode(0)
        click(p.at(START))
        if (clickSounds) playSoundSettings(correctSound())
        p.set(START, BUTTON.setValue(ButtonBlock.POWERED, true))
        after(2) { placed?.set(START, BUTTON) }
    }

    /** Trigger Bot: the button under the crosshair, pressed as soon as it's the next one. Only the practice device. */
    private fun trigger() {
        val p = placed ?: return
        if (mc.gui.screen() != null) return
        val pos = target() ?: return
        val cell = (0 until 16).firstOrNull { p.at(buttonAt(it)) == pos } ?: return
        if (!buttonUp[cell] || downUntil[cell] > tick) return
        val due = if (Inf.on) Inf.queue.firstOrNull() else if (accepting) expected.getOrNull(next) else null
        if (cell != due) return
        press(cell)
        mc.player?.swing(InteractionHand.MAIN_HAND, false)
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
            val n = expected.size
            if (fromRound > 0 && n == FINAL_ROUND) { fromDone(); after(6) { fromRun() }; return }
            if (n == FINAL_ROUND) after(6) { for (c in 0 until 16) setButton(c, false); revealed.clear(); done() }
            else after(6) { val cells = sequence.take(n + 1); show(cells, cells, stray = false) }
        } else {
            if (clickSounds) playSoundSettings(wrongSound())
            accepting = false
            fails++
            revealed.clear()
            after(3) { for (c in 0 until 16) setButton(c, false) }
            // A wrong press: the run starts over at once, as the start button would.
            restart(sound = false)
        }
    }

    private fun done() {
        phase = Phase.DONE
        // As at 1x: the time Show Speed saved added back.
        val total = (tick + shownFaster - firstLight) / 20.0
        val speed = if (instantShow) " §8(instant, as at 1x)" else if (showSpeed != 1.0) " §8(${fmt(showSpeed).trimEnd('0').trimEnd('.')}x, as at 1x)" else ""
        report(total, rounds, splits, (if (fails > 0) " §c$fails wrong" else "") + speed)
    }

    /** A run's chat lines (practice or the real device): the total, then a line a round with each press. */
    private fun report(total: Double, rounds: List<Double>, splits: List<List<Double>>, suffix: String) {
        // First light to done: green, dark green (under the 12 s death tick), yellow, red, dark red.
        val colour = when {
            total <= 11.6 -> "§a"
            total <= 11.95 -> "§2"
            total <= 12.6 -> "§e"
            total <= 13.5 -> "§c"
            else -> "§4"
        }
        EngineerClient.msg("§7SS took: $colour${fmt(total)}s$suffix")
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

    // ------------------------------------------------------------------ the real device's sounds

    // Odin's Simon Says solution: the buttons' lamps in order, and how many of them have been
    // pressed. Private in Odin, so read reflectively. Your own press counts as soon as you click:
    // the client presses the button itself before the server answers, and Odin sees that.
    private val odinOrder by lazy { SimonSays::class.java.getDeclaredField("clickInOrder").apply { isAccessible = true } }
    private val odinNeeded by lazy { SimonSays::class.java.getDeclaredField("clickNeeded").apply { isAccessible = true } }

    /**
     * A right click on F7's real Simon Says in P3: the correct sound for the start button and the
     * button Odin's solution has next, the wrong one for any other, and for any press Odin's Block
     * Wrong Clicks stopped ([blocked]: it never reached the device, so it isn't timed either).
     * Odin's own Custom Click Sounds is turned off so the two don't play together.
     * The presses also go into the real device's round times ([Real]).
     */
    private fun realClick(pos: BlockPos, blocked: Boolean) {
        if (placed != null || (!clickSounds && !realTimes)) return
        if (DungeonUtils.getF7Phase() != M7Phases.P3) return
        val isStart = pos == START
        if (!isStart && (pos.x != 110 || pos.y !in 120..123 || pos.z !in 92..95)) return
        val state = mc.level?.getBlockState(pos) ?: return
        if (state.block !is ButtonBlock || state.getValue(BlockStateProperties.POWERED)) return
        if (clickSounds) (SimonSays.settings["Custom Click Sounds"] as? BooleanSetting)?.let { if (it.value) it.value = false }
        if (blocked) { if (clickSounds) playSoundSettings(wrongSound()); return }
        if (isStart) { if (clickSounds) playSoundSettings(correctSound()); return }

        @Suppress("UNCHECKED_CAST")
        val order = odinOrder.get(null) as List<BlockPos>
        val i = odinNeeded.getInt(null)
        val right = order.getOrNull(i) == pos.east()
        if (clickSounds) playSoundSettings(if (right) correctSound() else wrongSound())
        if (realTimes) Real.press(right, last = right && i == order.size - 1, System.currentTimeMillis())
    }

    /**
     * The real device's run, timed like the practice one: first light to done, and per round its
     * clicking and each press (the first from when the round's buttons came up). Your presses are
     * timed when you click; the device's lights and buttons when their block changes arrive.
     */
    private object Real {
        var firstLight = 0L
        var roundUp = 0L
        var buttonsUp = false
        var lastPress = 0L
        var fails = 0
        val rounds = ArrayList<Double>()
        val splits = ArrayList<List<Double>>()
        val presses = ArrayList<Double>()

        fun reset() {
            firstLight = 0L; roundUp = 0L; buttonsUp = false; lastPress = 0L; fails = 0
            rounds.clear(); splits.clear(); presses.clear()
        }

        fun block(pos: BlockPos, old: BlockState, new: BlockState) {
            if (pos.y !in 120..123 || pos.z !in 92..95) return
            val now = System.currentTimeMillis()
            when (pos.x) {
                // The first light of a run (one left over from over 30 s ago: a new run).
                111 -> if (new.block == Blocks.SEA_LANTERN && old.block != Blocks.SEA_LANTERN && (firstLight == 0L || now - firstLight > 30_000)) { reset(); firstLight = now }
                110 -> when {
                    // The round's buttons coming up: its first press is timed from here.
                    new.block is ButtonBlock && old.block !is ButtonBlock && !buttonsUp -> {
                        buttonsUp = true; roundUp = now; lastPress = now; presses.clear()
                    }
                    new.isAir && old.block is ButtonBlock -> buttonsUp = false
                }
            }
        }

        fun press(right: Boolean, last: Boolean, now: Long) {
            if (firstLight == 0L || roundUp == 0L) return
            if (!right) {
                // The device starts the sequence over: so do the rounds.
                fails++; rounds.clear(); splits.clear(); presses.clear()
                return
            }
            presses += (now - lastPress) / 1000.0; lastPress = now
            if (!last) return
            // A round's clicking ends 6 ticks after its last press, when the next one starts.
            rounds += (now - roundUp) / 1000.0 + 0.3
            splits += presses.toList()
            if (presses.size == FINAL_ROUND) {
                report((now + 300 - firstLight) / 1000.0, rounds, splits, suffix = (if (fails > 0) " §c$fails wrong" else "") + " §8(real)")
                reset()
            }
            presses.clear()
        }
    }

    /** Right click. True: it was on the device, handled here, and the game must not use it. */
    @JvmStatic
    fun onUse(): Boolean {
        val pos = target() ?: return false
        val p = placed ?: return false
        val button = mc.level?.getBlockState(pos)?.block is ButtonBlock
        EngineerClient.safely("ss practice use") {
            val from = (3..4).firstOrNull { pos == p.at(fromButton(it)) }
            val rngPick = (-1..2).firstOrNull { pos == p.at(rngButton(it)) }
            if (pos == p.at(START)) pressStart()
            else if (rngPick != null) pressRng(rngPick)
            else if (pos == p.at(FULL_BUTTON)) pressToggle()
            else if (pos == p.at(EXTRA)) pressExtra()
            else if (from != null) pressFrom(from)
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
            if (triggerBot) EngineerClient.safely("ss trigger bot") { trigger() }
            tick++
            if (jobs.isEmpty()) return@on
            val due = jobs.filter { it.at <= tick }
            jobs.removeAll(due.toSet())
            for (j in due) if (j.gen == gen) EngineerClient.safely("ss practice") { j.run() }
        }
        on<RenderExtractEvent> {
            val p = placed ?: return@on
            if (!solver || p.level !== mc.level) return@on
            if (Inf.on) {
                for ((i, cell) in Inf.queue.withIndex()) {
                    val lamp = lampAt(cell)
                    drawStyledBox(solverBox(p, lamp), when (i) { 0 -> FIRST; 1 -> SECOND; else -> THIRD }, BoxStyle.FILLED_OUTLINE, true)
                }
                return@on
            }
            for (i in next until revealed.size) {
                val colour = when (i) { next -> FIRST; next + 1 -> SECOND; else -> THIRD }
                // Odin's box: on the grid's face where the button sits, in the real device's frame.
                val lamp = lampAt(revealed[i])
                drawStyledBox(solverBox(p, lamp), colour, BoxStyle.FILLED_OUTLINE, true)
            }
        }
        // The real device: the same sounds as here, right or wrong, read off Odin's solution. After
        // Odin's own listener (priority -1) and even when it cancelled the press (ignoreCancelled
        // here means "skip cancelled ones"), so a press Block Wrong Clicks stopped still sounds wrong.
        on<BlockInteractEvent>(priority = -1) { EngineerClient.safely("ss real sounds") { realClick(pos, isCancelled) } }
        on<BlockUpdateEvent> {
            if (!realTimes || placed != null || DungeonUtils.getF7Phase() != M7Phases.P3) return@on
            EngineerClient.safely("ss real times") { Real.block(pos, old, updated) }
        }
        on<LevelEvent.Load> { Real.reset() }
        // The terminals start countdown (Storm dying; Goldor's first line if that was missed): the practice device goes away.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val msg = content.string.replace(Regex("§."), "")
            if (msg != STORM_DEAD && msg != GOLDOR_START) return@onReceive
            mc.execute { if (placed != null) EngineerClient.safely("ss practice countdown") { remove(); EngineerClient.msg("§7SS Practice: removed (terminals starting).") } }
        }
        // A new world has none of it: nothing to put back.
        on<LevelEvent.Unload> { placed = null; gen++; jobs.clear(); phase = Phase.IDLE }
    }

    override fun onDisable() {
        super.onDisable()
        remove()
    }
}
