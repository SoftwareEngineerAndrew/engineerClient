package com.engineerclient.p3sim.clear.puzzles

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.pack.rooms.WorldPos
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.api.PuzzleContext
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Ice Fill in the world, as the recorded Hypixel rooms run it. The two reward chests are in the room, behind the gate of iron bars, from the
 * moment a player steps in. Three floors of ice: walking onto a cell of the active floor turns the ice under it to packed ice with a puff of
 * wool-break particles; finishing a floor plays three rising harp notes at the cell. The steps must follow the rules of [IceFillLogic]:
 * a step onto a covered cell ("Oops! You stepped on the wrong block!") or away from the previous cell ("Don't move diagonally! Bad!")
 * collapses the whole floor being filled for [COLLAPSE_TICKS] ticks and the player who did it is told (nobody else).
 *
 * The third floor done starts the opening: the gate's bars (not the three rows above them) turn to barrier at once and to air 26 ticks later,
 * the first chest opens by itself 9 ticks after the last step and the second 3 ticks after that (Odin's signal, the lid sound and the
 * harp jingle each), a pressure-plate click sounds at the gate every 5 ticks, and 15 ticks after the last step the room turns green.
 *
 * UNMEASURED: which chest opens first is taken from one recorded room (the east one, in the room's frame).
 */
class IceFillPuzzle(context: PuzzleContext) : WorldPuzzle(PuzzleKinds.ICE_FILL, context) {
    private var logic: IceFillLogic? = null
    private var chests: List<WorldPos> = emptyList()

    /** All the columns with ice per floor (patch cells and the cells under an obstacle), in the room's frame. */
    private var iceCells: List<Set<IceFillLogic.Cell>> = emptyList()

    /** The obstacles standing on the ice at feet level, as the room has them (read at build). */
    private var obstacles: List<Map<IceFillLogic.Cell, BlockState>> = emptyList()

    /** The column each player stood in when last seen on the active floor, so a step is only counted when they move onto a new one. */
    private val standing = HashMap<UUID, Pair<Int, Int>>()

    /** In a replay: the recording's packed ice in this room by tick, the teammates' steps when none of yours is in it. */
    private val recordedSteps = ArrayDeque<Pair<Int, WorldPos>>()

    /** True from a collapse until the floor comes back. */
    private var collapsed = false

    /** True from the last step of the third floor: the opening is running, nothing more can be stepped. */
    private var opening = false

    override val wakeDelayTicks: Int get() = 0
    override val rewardSignalAtGreen: Boolean get() = false
    override val ticksUnattended: Boolean get() = replayPacked != null

    override fun rewardSpots(): List<WorldPos> = chests

    override fun build() {
        chests = listOf("chest1", "chest2").mapNotNull { context.anchor(it).firstOrNull() }
        val ice = Blocks.ICE
        val isIce = { x: Int, y: Int, z: Int -> stateAt(x, y, z)?.block == ice }
        replayPacked?.invoke()?.let { all ->
            useRecordedPatterns(all.map { it.second }.distinct(), isIce)
            recordedSteps.addAll(all.filter { (_, w) -> local(w.x, w.z)?.let { it.first in 0..30 && it.second in 0..30 } == true }.sortedBy { it.first })
        }
        logic = IceFillLogic((0..2).map { f -> IceFillLogic.scan(f, isIce = isIce, isAir = ::isAirAt) })
        iceCells = (0..2).map { f -> IceFillLogic.scanIce(f, isIce = isIce) }
        obstacles = (0..2).map { f ->
            val patch = logic!!.floors[f]
            iceCells[f].filter { it !in patch }.mapNotNull { c -> stateAt(c.x, c.y, c.z)?.takeIf { !it.isAir }?.let { c to it } }.toMap()
        }
        val chosen = IceFillLogic.patterns(isAir = ::isAirAt)
        if (chosen.any { it < 0 }) org.slf4j.LoggerFactory.getLogger("engineerclient").warn("[ads] Ice Fill in ${context.room.designId}${context.room.variantId?.let { "/$it" } ?: ""}: floors ${chosen.indices.filter { chosen[it] < 0 }} match none of Odin's patterns, so Odin cannot solve them")
    }

    /**
     * A replay's room: each floor is laid out as the recorded one, the one pattern whose patch holds every cell the recording turned to
     * packed ice (its ice columns off the patch get the obstacle, polished andesite, the only one recorded). With no recorded cell on a
     * floor, or several patterns that fit (none exactly) and the room's own among them, the room's floor stays.
     */
    private fun useRecordedPatterns(packed: List<WorldPos>, isIce: (Int, Int, Int) -> Boolean) {
        val air = Blocks.AIR.defaultBlockState()
        val obstacle = Blocks.POLISHED_ANDESITE.defaultBlockState()
        val own = IceFillLogic.patterns(isAir = ::isAirAt)
        for (f in 0..2) {
            val y = IceFillLogic.FLOOR_Y[f]
            val cells = packed.filter { it.y == y - 1 }.mapNotNull { local(it.x, it.z) }.filter { it.first in 0..30 && it.second in 0..30 }.toSet()
            if (cells.isEmpty()) continue
            val fits = OdinTables.iceFloors[f].indices.filter { IceFillLogic.patch(f, it).containsAll(cells) }
            // A completed floor covered its whole patch: the pattern that is exactly it (a smaller patch can lie inside a bigger one).
            val pick = fits.firstOrNull { IceFillLogic.patch(f, it) == cells } ?: if (own[f] in fits) own[f] else fits.firstOrNull() ?: continue
            if (pick == own[f]) continue
            val patch = IceFillLogic.patch(f, pick)
            for (c in IceFillLogic.scanIce(f, isIce = isIce)) setAt(c.x, y, c.z, if ((c.x to c.z) in patch) air else obstacle)
        }
    }

    /** A player steps in: the chests are there behind the gate. */
    override fun wake() { placeChests() }

    private fun placeChests() {
        val state = roomState(CHEST_STATE) ?: return
        chests.forEach { setWorld(it, state) }
    }

    override fun onTick() {
        val l = logic ?: return
        if (status != PuzzleStatus.UNSOLVED || collapsed || opening) return
        val inRoom = playersInRoom()
        for (p in inRoom) {
            val f = l.activeFloor
            if (f < 0) return
            val feetY = Math.floor(p.y + 0.01).toInt()
            if (feetY != IceFillLogic.FLOOR_Y[f]) continue
            val col = local(Math.floor(p.x).toInt(), Math.floor(p.z).toInt()) ?: continue
            // Only a move onto a different column counts: standing still, or jumping on the spot, is not a step.
            if (standing[p.uuid] == col) continue
            standing[p.uuid] = col
            if (step(l, f, col, feetY, p.uuid)) return
        }
        // A replay: with none of yours in the room, the recording's packed ice of the tick is a teammate's step, out of your sight.
        val due = recordedSteps.takeWhile { it.first <= run.t }
        repeat(due.size) { recordedSteps.removeFirst() }
        if (inRoom.isEmpty()) for ((_, w) in due) {
            val f = l.activeFloor
            if (f < 0 || w.y + 1 != IceFillLogic.FLOOR_Y[f]) continue
            if (step(l, f, local(w.x, w.z) ?: continue, w.y + 1, TEAMMATE)) return
        }
    }

    /** A step onto [col] of floor [f] by [by]: covers it, or collapses the floor. True when the tick is over for the room (a collapse, the solve). */
    private fun step(l: IceFillLogic, f: Int, col: Pair<Int, Int>, feetY: Int, by: UUID): Boolean {
        val r = l.step(col.first, feetY, col.second)
        when (r.outcome) {
            IceFillLogic.Outcome.NOTHING -> {}
            IceFillLogic.Outcome.COVERED -> {
                val c = r.cell!!
                setAt(c.x, c.y - 1, c.z, Blocks.PACKED_ICE.defaultBlockState())
                world(c.x, c.y - 1, c.z)?.let { w -> Sim.level.levelEvent(2001, BlockPos(w.x, w.y, w.z), Block.getId(Blocks.WOOL.white().defaultBlockState())) }
                if (r.floorDone) floorDone(f, c, by)
                if (l.solved) return true
            }
            IceFillLogic.Outcome.WRONG_BLOCK -> { sayTo(by, PuzzleLines.ICE_WRONG_BLOCK); collapse(f); return true }
            IceFillLogic.Outcome.DIAGONAL -> { sayTo(by, PuzzleLines.ICE_DIAGONAL); collapse(f); return true }
        }
        return false
    }

    // ---- a floor done -----------------------------------------------------------------------------------------------------

    /** A floor is done: three rising harp notes at the cell (and on the third floor, the opening). */
    private fun floorDone(f: Int, at: IceFillLogic.Cell, by: UUID) {
        val w = world(at.x, at.y - 1, at.z)
        if (w != null) {
            val pos = Vec3(w.x + 0.5, w.y + 0.5, w.z + 0.5)
            FLOOR_HARP[f].forEachIndexed { i, pitch -> after(5 * i) { Sim.sound(SoundEvents.NOTE_BLOCK_HARP, 1.0f, pitch, pos, SoundSource.BLOCKS) } }
        }
        if (logic?.solved == true) startOpening(by)
    }

    /** The third floor is done at tick T: the gate, the chests, the jingle, and the green mark at T + 15. */
    private fun startOpening(by: UUID) {
        opening = true
        val gate = gateCells()
        val barrier = Blocks.BARRIER.defaultBlockState()
        gate.forEach { setWorld(it, barrier) }
        after(GATE_AIR_TICKS) { gate.forEach { setWorld(it, Blocks.AIR.defaultBlockState()) } }
        gate.firstOrNull()?.let { g ->
            val at = Vec3(g.x + 0.5, g.y + 0.5, g.z + 0.5)
            for (i in 0 until GATE_AIR_TICKS / 5 + 1) after(5 * i) { Sim.sound(SoundEvents.WOODEN_PRESSURE_PLATE_CLICK_ON, 2.0f, 0.4920635f, at) }
        }
        // The east chest of the room opens first, the other 3 ticks after.
        val order = chests.sortedByDescending { canonicalX(it) }
        order.forEachIndexed { i, c -> after(FIRST_CHEST_TICKS + CHEST_GAP_TICKS * i) { openChest(c) } }
        after(GREEN_TICKS) { solve(by) }
    }

    /** The canonical x of a world position (to tell the east chest from the west one whatever way the room is turned). */
    private fun canonicalX(p: WorldPos): Int = local(p.x, p.z)?.first ?: 0

    /** The bars of the gate in the way of the chests (x 13 to 17, y 74 to 79, z 27 in the room's frame); the three rows above them stay. */
    private fun gateCells(): List<WorldPos> = buildList {
        for (y in GATE_Y) for (x in GATE_X) world(x, y, GATE_Z)?.let { add(it) }
    }

    /** A chest opens by itself: Odin's signal, the lid sound and the five harp notes. */
    private fun openChest(c: WorldPos) {
        lidSignal(c)
        val at = Vec3(c.x + 0.5, c.y + 0.5, c.z + 0.5)
        Sim.sound(SoundEvents.CHEST_OPEN, 0.5f, 0.92f, at, SoundSource.BLOCKS)
        ChestJingle.PITCHES.forEachIndexed { i, pitch -> after(ChestJingle.FIRST_NOTE + ChestJingle.NOTE_GAP * i) { Sim.sound(SoundEvents.NOTE_BLOCK_HARP, 1.0f, pitch, at) } }
    }

    // ---- a wrong step -----------------------------------------------------------------------------------------------------

    /**
     * Floor [f] falls: every ice and packed-ice block of the floor, and the obstacles standing on it, become air in one go (a glass-break puff
     * per ice cell, a falling block per obstacle); [COLLAPSE_TICKS] ticks later they all come back and the floor starts again.
     */
    private fun collapse(f: Int) {
        val l = logic ?: return
        collapsed = true
        l.collapse()
        standing.clear()
        val iceState = Blocks.ICE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val level = Sim.level
        for (c in iceCells[f]) {
            setAt(c.x, c.y - 1, c.z, air)
            world(c.x, c.y - 1, c.z)?.let { w -> level.levelEvent(2001, BlockPos(w.x, w.y, w.z), Block.getId(iceState)) }
        }
        for ((c, state) in obstacles[f]) {
            val w = world(c.x, c.y, c.z) ?: continue
            val pos = BlockPos(w.x, w.y, w.z)
            val falling = FallingBlockEntity.fall(level, pos, state)
            falling.disableDrop()
            track(falling)
            falling.addTag(Sim.TAG)
            after(FALL_TICKS) { forget(falling) }
        }
        after(COLLAPSE_TICKS) {
            for (c in iceCells[f]) setAt(c.x, c.y - 1, c.z, iceState)
            for ((c, state) in obstacles[f]) setAt(c.x, c.y, c.z, state)
            collapsed = false
        }
    }

    // ---- solved and reset -------------------------------------------------------------------------------------------------

    /** The room as it stands once solved (called when a savepoint restores a solved room): the gate open, the chests in place. */
    override fun applySolved() {
        for (c in gateCells()) setWorld(c, Blocks.AIR.defaultBlockState())
        placeChests()
    }

    override fun onReset() { logic?.reset() }

    companion object {
        /** In a replay: where and when (tick) the recording turned ice to packed ice, so the floors are built as Hypixel had them. */
        @Volatile var replayPacked: (() -> List<Pair<Int, WorldPos>>)? = null

        /** Who a recorded step out of your sight is by: nobody ADS knows. */
        private val TEAMMATE = UUID(0L, 0L)

        /** A collapsed floor comes back this long after it fell (measured: 59 to 61 ticks in 36 of 36). */
        const val COLLAPSE_TICKS = 60

        /** The falling blocks of the obstacles are removed this long after the collapse (measured: 17). */
        const val FALL_TICKS = 17

        /** The gate's bars are barrier at the last step and air this many ticks later. */
        const val GATE_AIR_TICKS = 26

        /** The first chest opens this long after the last step (median 9) and the second [CHEST_GAP_TICKS] after the first (median 3). */
        const val FIRST_CHEST_TICKS = 9
        const val CHEST_GAP_TICKS = 3

        /** The room turns green this long after the last step (median 15). */
        const val GREEN_TICKS = 15

        /** The three harp notes that play at the cell when floor 1, 2 and 3 is done. */
        val FLOOR_HARP = listOf(
            floatArrayOf(1.1904762f, 1.2857143f, 1.3968254f),
            floatArrayOf(1.4920635f, 1.5873016f, 1.6984127f),
            floatArrayOf(1.7936508f, 1.8888889f, 2.0f),
        )

        /** The gate in the room's frame: x 13 to 17, y 74 to 79 (the rows above stay bars), z 27. */
        val GATE_X = 13..17
        val GATE_Y = 74..79
        const val GATE_Z = 27

        /** The chests face the gate, which is toward canonical north. */
        private const val CHEST_STATE = "minecraft:chest[facing=north,type=single,waterlogged=false]"
    }
}
