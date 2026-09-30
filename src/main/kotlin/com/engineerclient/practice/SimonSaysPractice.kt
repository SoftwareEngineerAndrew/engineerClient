package com.engineerclient.practice

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.mixin.MinecraftAccessor
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.KeybindSetting
import com.odtheking.odin.events.LevelEvent
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
 * The blocks are the real device, taken from Better PF's capture of the F7 arena (x 106-112,
 * y 117-126, z 86-100), placed so that you stand where healers stand to do it (108, 120, 94 in
 * the arena, facing +x) and turned to face whichever way you look. Blocks below your feet are
 * only placed over ground that is already solid, so nothing new appears to stand on.
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
    private val summonKey by KeybindSetting("Summon Keybind", GLFW.GLFW_KEY_UNKNOWN, "Summons the device in front of you, and takes it away again.").onPress { summonOrRemove() }
    private val roundTimes by BooleanSetting("Round Times", true, desc = "After each completion, how long each round's clicking took, next to the fastest healers' medians from Better PF runs.")

    // ------------------------------------------------------------------ the real device

    /** Where you stand in the arena to do it (your feet), facing +x. */
    private const val AX = 108; private const val AY = 120; private const val AZ = 94
    private val START = BlockPos(110, 121, 91)
    /** Cell 0-15: row from the top (y 123 down), column from z 92. */
    private fun buttonAt(cell: Int) = BlockPos(110, 123 - cell / 4, 92 + cell % 4)
    private fun lampAt(cell: Int) = BlockPos(111, 123 - cell / 4, 92 + cell % 4)

    /** Fastest healers' medians in Better PF runs (Inplse, Joeher47, 12heart): first light to done, then each round's clicking. */
    private const val TOP_TOTAL = 11.65
    private val TOP_ROUNDS = doubleArrayOf(1.10, 0.80, 1.05, 1.25)

    private val PALETTE = arrayOf(
        "cracked_stone_bricks",
        "stone_bricks",
        "mossy_stone_bricks",
        "stone",
        "cobblestone_wall[east=tall,north=none,south=tall,up=true,waterlogged=false,west=none]",
        "cobblestone_wall[east=none,north=none,south=tall,up=true,waterlogged=false,west=tall]",
        "piston[extended=false,facing=east]",
        "cobblestone_wall[east=tall,north=tall,south=none,up=true,waterlogged=false,west=none]",
        "cobblestone_wall[east=none,north=tall,south=none,up=true,waterlogged=false,west=tall]",
        "gray_wool",
        "cyan_terracotta",
        "lime_stained_glass_pane[east=true,north=true,south=true,waterlogged=false,west=false]",
        "quartz_stairs[facing=east,half=top,shape=straight,waterlogged=false]",
        "quartz_stairs[facing=west,half=top,shape=straight,waterlogged=false]",
        "chiseled_quartz_block",
        "quartz_stairs[facing=south,half=top,shape=straight,waterlogged=false]",
        "quartz_stairs[facing=north,half=top,shape=straight,waterlogged=false]",
        "quartz_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
        "quartz_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]",
        "quartz_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]",
        "quartz_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
        "wall_torch[facing=south]",
        "polished_andesite",
        "iron_trapdoor[facing=west,half=bottom,open=true,powered=false,waterlogged=false]",
        "sea_lantern",
        "iron_block",
        "emerald_block",
        "obsidian",
        "stone_button[face=wall,facing=west,powered=false]",
        "oak_button[face=wall,facing=west,powered=false]",
        "redstone_wall_torch[facing=west,lit=true]",
        "stone_brick_stairs[facing=north,half=top,shape=straight,waterlogged=false]",
        "redstone_lamp[lit=false]",
    )

    /** "dx,dy,dz,palette;" from where you stand (dx toward the device, dz to your right). */
    private const val CELLS =
        "0,-3,-8,0;1,-3,-8,1;2,-3,-8,2;3,-3,-8,1;4,-3,-8,1;0,-3,-7,1;1,-3,-7,1;2,-3,-7,2;3,-3,-7,2;" +
        "4,-3,-7,3;-2,-3,-6,4;-1,-3,-6,5;3,-3,-6,6;4,-3,-6,1;-2,-3,-5,7;-1,-3,-5,8;3,-3,-5,6;4,-3,-5,1;" +
        "3,-3,-4,9;4,-3,-4,1;3,-3,-3,10;4,-3,-3,1;3,-3,-2,11;4,-3,-2,1;3,-3,-1,9;4,-3,-1,1;3,-3,0,10;" +
        "4,-3,0,1;3,-3,1,6;4,-3,1,1;3,-3,2,6;-1,-3,3,4;0,-3,3,5;3,-3,3,10;-1,-3,4,7;0,-3,4,8;3,-3,4,10;" +
        "3,-3,5,6;3,-3,6,9;-2,-2,-8,12;-1,-2,-8,13;0,-2,-8,3;1,-2,-8,1;2,-2,-8,0;3,-2,-8,1;4,-2,-8,1;" +
        "-2,-2,-7,12;-1,-2,-7,13;0,-2,-7,1;1,-2,-7,0;2,-2,-7,1;3,-2,-7,0;4,-2,-7,1;-2,-2,-6,14;" +
        "-1,-2,-6,14;0,-2,-6,15;1,-2,-6,15;2,-2,-6,15;3,-2,-6,6;4,-2,-6,0;-2,-2,-5,14;-1,-2,-5,14;" +
        "0,-2,-5,16;1,-2,-5,16;2,-2,-5,16;3,-2,-5,6;4,-2,-5,1;3,-2,-4,9;4,-2,-4,2;3,-2,-3,10;4,-2,-3,1;" +
        "3,-2,-2,11;4,-2,-2,1;-1,-2,-1,14;0,-2,-1,14;1,-2,-1,14;2,-2,-1,14;3,-2,-1,9;4,-2,-1,0;-1,-2,0,14;" +
        "0,-2,0,14;1,-2,0,14;2,-2,0,14;3,-2,0,6;4,-2,0,1;-1,-2,1,12;0,-2,1,13;3,-2,1,6;4,-2,1,3;" +
        "-1,-2,2,12;0,-2,2,13;3,-2,2,6;-2,-2,3,15;-1,-2,3,14;0,-2,3,14;3,-2,3,10;-2,-2,4,16;-1,-2,4,14;" +
        "0,-2,4,14;3,-2,4,6;3,-2,5,6;3,-2,6,9;-2,-1,-8,17;-1,-1,-8,18;0,-1,-8,1;1,-1,-8,0;2,-1,-8,1;" +
        "3,-1,-8,1;4,-1,-8,1;-2,-1,-7,17;-1,-1,-7,18;0,-1,-7,1;1,-1,-7,1;2,-1,-7,1;3,-1,-7,1;4,-1,-7,2;" +
        "-2,-1,-6,14;-1,-1,-6,14;0,-1,-6,19;1,-1,-6,19;2,-1,-6,19;3,-1,-6,6;4,-1,-6,3;-2,-1,-5,14;" +
        "-1,-1,-5,14;0,-1,-5,20;1,-1,-5,20;2,-1,-5,20;3,-1,-5,10;4,-1,-5,0;-2,-1,-4,21;3,-1,-4,22;" +
        "4,-1,-4,1;2,-1,-3,23;3,-1,-3,24;4,-1,-3,10;3,-1,-2,25;4,-1,-2,1;-1,-1,-1,14;0,-1,-1,14;" +
        "1,-1,-1,14;2,-1,-1,14;3,-1,-1,26;4,-1,-1,1;-1,-1,0,14;0,-1,0,14;1,-1,0,14;2,-1,0,14;3,-1,0,26;" +
        "4,-1,0,22;-1,-1,1,17;0,-1,1,18;3,-1,1,25;4,-1,1,0;-1,-1,2,17;0,-1,2,18;2,-1,2,23;3,-1,2,24;" +
        "-2,-1,3,19;-1,-1,3,14;0,-1,3,14;3,-1,3,22;-2,-1,4,20;-1,-1,4,14;0,-1,4,14;3,-1,4,6;3,-1,5,6;" +
        "3,-1,6,10;0,0,-8,1;1,0,-8,1;2,0,-8,1;3,0,-8,3;4,0,-8,2;0,0,-7,1;1,0,-7,0;2,0,-7,1;3,0,-7,1;" +
        "4,0,-7,1;3,0,-6,6;4,0,-6,1;3,0,-5,10;4,0,-5,1;3,0,-4,22;4,0,-4,2;3,0,-3,25;4,0,-3,1;3,0,-2,27;" +
        "4,0,-2,1;3,0,-1,27;4,0,-1,1;3,0,0,27;4,0,0,1;3,0,1,27;4,0,1,1;3,0,2,25;3,0,3,22;3,0,4,6;3,0,5,6;" +
        "3,0,6,9;0,1,-8,1;1,1,-8,1;2,1,-8,1;3,1,-8,1;4,1,-8,1;0,1,-7,1;1,1,-7,2;2,1,-7,3;3,1,-7,2;" +
        "4,1,-7,0;3,1,-6,6;4,1,-6,1;3,1,-5,10;4,1,-5,1;3,1,-4,22;4,1,-4,1;2,1,-3,28;3,1,-3,26;4,1,-3,3;" +
        "3,1,-2,27;4,1,-2,1;3,1,-1,27;4,1,-1,1;3,1,0,27;4,1,0,1;3,1,1,27;4,1,1,1;3,1,2,26;3,1,3,22;" +
        "3,1,4,6;3,1,5,6;3,1,6,9;0,2,-8,1;1,2,-8,1;2,2,-8,0;3,2,-8,1;4,2,-8,1;0,2,-7,1;1,2,-7,1;2,2,-7,1;" +
        "3,2,-7,0;4,2,-7,1;3,2,-6,10;4,2,-6,1;3,2,-5,10;4,2,-5,1;3,2,-4,22;4,2,-4,9;3,2,-3,26;4,2,-3,3;" +
        "3,2,-2,27;4,2,-2,2;3,2,-1,27;4,2,-1,1;3,2,0,27;4,2,0,1;3,2,1,27;4,2,1,1;3,2,2,26;3,2,3,22;" +
        "3,2,4,6;3,2,5,6;3,2,6,12;0,3,-8,1;1,3,-8,1;2,3,-8,1;3,3,-8,1;4,3,-8,1;0,3,-7,1;1,3,-7,1;2,3,-7,1;" +
        "3,3,-7,1;4,3,-7,0;3,3,-6,10;4,3,-6,2;3,3,-5,10;4,3,-5,2;3,3,-4,22;4,3,-4,9;3,3,-3,25;4,3,-3,1;" +
        "3,3,-2,27;4,3,-2,1;3,3,-1,27;4,3,-1,1;3,3,0,27;4,3,0,1;3,3,1,27;4,3,1,1;3,3,2,25;3,3,3,22;" +
        "3,3,4,6;3,3,5,6;2,3,6,29;3,3,6,22;0,4,-8,2;1,4,-8,1;2,4,-8,1;3,4,-8,1;4,4,-8,1;0,4,-7,2;1,4,-7,1;" +
        "2,4,-7,0;3,4,-7,1;4,4,-7,1;3,4,-6,6;4,4,-6,1;3,4,-5,10;4,4,-5,1;3,4,-4,22;4,4,-4,0;2,4,-3,23;" +
        "3,4,-3,24;4,4,-3,1;3,4,-2,25;4,4,-2,1;3,4,-1,26;4,4,-1,0;3,4,0,26;4,4,0,1;3,4,1,25;4,4,1,1;" +
        "2,4,2,23;3,4,2,24;3,4,3,22;3,4,4,11;3,4,5,11;2,4,6,30;3,4,6,22;0,5,-8,1;1,5,-8,1;2,5,-8,1;" +
        "3,5,-8,2;4,5,-8,1;0,5,-7,1;1,5,-7,2;2,5,-7,1;3,5,-7,1;4,5,-7,1;3,5,-6,10;4,5,-6,1;3,5,-5,10;" +
        "4,5,-5,2;3,5,-4,22;4,5,-4,2;3,5,-3,22;4,5,-3,1;3,5,-2,22;4,5,-2,1;3,5,-1,22;4,5,-1,2;3,5,0,22;" +
        "4,5,0,3;3,5,1,22;4,5,1,1;3,5,2,22;3,5,3,22;3,5,4,10;3,5,5,10;2,5,6,30;3,5,6,22;-1,6,-8,22;" +
        "0,6,-8,1;1,6,-8,1;2,6,-8,1;3,6,-8,1;4,6,-8,3;-1,6,-7,31;0,6,-7,2;1,6,-7,1;2,6,-7,1;3,6,-7,1;" +
        "4,6,-7,1;3,6,-6,6;4,6,-6,2;3,6,-5,10;4,6,-5,1;3,6,-4,10;4,6,-4,1;3,6,-3,11;4,6,-3,32;3,6,-2,10;" +
        "4,6,-2,32;3,6,-1,10;4,6,-1,32;3,6,0,11;4,6,0,32;3,6,1,10;4,6,1,1;3,6,2,10;3,6,3,10;3,6,4,10;" +
        "3,6,5,10;3,6,6,17;"

    private class Block3(val dx: Int, val dy: Int, val dz: Int, val state: BlockState)

    private val structure: List<Block3> by lazy {
        val states = PALETTE.map { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:$it", false).blockState() }
        CELLS.split(';').filter { it.isNotEmpty() }.map { e ->
            val (dx, dy, dz, i) = e.split(',').map(String::toInt)
            Block3(dx, dy, dz, states[i])
        }
    }

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
            for (b in structure) {
                val pos = p.at(b.dx, b.dy, b.dz)
                // Below your feet only over ground that is already there: nothing new to stand on.
                if (b.dy < 0 && level.getBlockState(pos).canBeReplaced()) continue
                p.setWorld(pos, b.state)
            }
        }
        placed = p
        reset()
        EngineerClient.msg("§7SS Practice: summoned. Press the start button §8(left of the grid)§7 to begin, 3 times for the skip. The keybind again takes it away.")
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
    private var fails = 0

    /** Everything back to a dark, buttonless device, waiting for the start button. */
    private fun reset() {
        gen++
        jobs.clear()
        phase = Phase.IDLE
        accepting = false
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
        val n = cells.size
        for (i in 0 until n) after(8 * i) {
            if (i > 0) light(cells[i - 1], false)
            light(cells[i], true)
            if (firstLight == 0L) firstLight = tick
        }
        after(8 * n) { light(cells.last(), false) }
        val open = {
            accepting = true; roundUp = tick
        }
        if (stray) {
            after(8 * (n - 1) + 5) { for (c in 0 until 16) if (c != cells.last()) setButton(c, true); open() }
            after(8 * n + 10) { setButton(cells.last(), true) }
        } else {
            after(8 * n + 10) { for (c in 0 until 16) setButton(c, true); open() }
        }
    }

    private fun begin() {
        phase = Phase.RUNNING
        newSequence()
        val s = sequence
        when (startPresses.coerceAtMost(3)) {
            1 -> show(listOf(s[0]), listOf(s[0]), stray = false)
            2 -> show(listOf(stray(s[0]), s[0]), listOf(s[0]), stray = true)
            else -> show(listOf(stray(s[0]), s[0], s[1]), listOf(s[0], s[1]), stray = true)
        }
    }

    private fun pressStart() {
        val p = placed ?: return
        when (phase) {
            Phase.IDLE, Phase.DONE -> {
                reset()
                phase = Phase.STARTING
                startPresses = 1; firstLight = 0L; rounds.clear(); fails = 0
                after(6) { begin() }
            }
            Phase.STARTING -> startPresses++
            Phase.RUNNING -> {}
        }
        // After the reset above, so it doesn't cancel the button coming back up.
        click(p.at(START))
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
        if (!accepting) return
        if (cell == expected[next]) {
            next++
            if (next < expected.size) return
            accepting = false
            rounds += (tick + 6 - roundUp) / 20.0
            val n = expected.size
            if (n == 5) after(6) { for (c in 0 until 16) setButton(c, false); done() }
            else after(6) { val cells = sequence.take(n + 1); show(cells, cells, stray = false) }
        } else {
            accepting = false
            fails++
            after(3) { for (c in 0 until 16) setButton(c, false) }
            after(25) {
                newSequence()
                val s = sequence
                show(listOf(stray(s[0]), s[0], s[1]), listOf(s[0], s[1]), stray = true)
                rounds.clear()
            }
        }
    }

    private fun done() {
        phase = Phase.DONE
        val total = (tick - firstLight) / 20.0
        val colour = if (total <= TOP_TOTAL) "§a" else if (total <= TOP_TOTAL + 1) "§e" else "§c"
        EngineerClient.msg("§7SS Practice: done in $colour${fmt(total)}s§7 (first light to done)" +
            (if (fails > 0) " §8· §c$fails wrong" else "") + " §8· §7top healers ~${fmt(TOP_TOTAL)}s. Start again to go again.")
        if (roundTimes && rounds.size == TOP_ROUNDS.size) {
            val names = arrayOf("first", "r3", "r4", "r5")
            EngineerClient.msg("§7clicking: " + rounds.indices.joinToString(" §8· ") { i ->
                val c = if (rounds[i] <= TOP_ROUNDS[i]) "§a" else if (rounds[i] <= TOP_ROUNDS[i] + 0.3) "§e" else "§c"
                "§8${names[i]} $c${fmt(rounds[i])}§8/${fmt(TOP_ROUNDS[i])}"
            })
        }
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
        EngineerClient.safely("ss practice use") {
            if (pos == p.at(START)) pressStart()
            else (0 until 16).firstOrNull { p.at(buttonAt(it)) == pos }?.let { press(it) }
        }
        mc.player?.swing(InteractionHand.MAIN_HAND, false) // your arm moves; nothing is sent
        (mc as MinecraftAccessor).`ec$setRightClickDelay`(4) // holding right click repeats like the game's own
        return true
    }

    /** Left click on the device: nothing (like the real one), and nothing sent. */
    @JvmStatic
    fun onAttack(): Boolean {
        target() ?: return false
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
        // A new world has none of it: nothing to put back.
        on<LevelEvent.Unload> { placed = null; gen++; jobs.clear(); phase = Phase.IDLE }
    }

    override fun onDisable() {
        super.onDisable()
        remove()
    }
}
