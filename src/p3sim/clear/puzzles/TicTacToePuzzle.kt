package com.engineerclient.p3sim.clear.puzzles

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.pack.rooms.WorldPos
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.api.PuzzleContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import net.minecraft.world.phys.Vec3
import java.util.Optional
import java.util.UUID

/**
 * Tic Tac Toe in the world, as the recorded Hypixel rooms play it. The board is the wall of nine cells (rows at y = 72, 71, 70 from the top,
 * columns at z = 15, 16, 17 in the room's frame, a stone button in each cell at x = 8 on the iron wall at x = 7). The ROOM plays first: 24
 * ticks after a player steps in it puts its X in a cell. Pressing a button on the party's turn removes it, hangs an O there and pings
 * (a loud note-block pling at the player); the room answers exactly 60 ticks later with a best move, and presses in the meantime only click
 * the button. Every mark is an item frame holding a map (the room's red X, the party's red O), the thing mods read the board from.
 *
 * The room's ninth mark ends a tied game, at once: `PUZZLE SOLVED!`, a chest appears behind the wall (east-facing, at (2, 70, 16)), the room
 * turns green 2 ticks later and counts as Completed at 13. The marks go after 29 ticks, the iron wall turns to barrier at 30 and to air at 56
 * (a pressure-plate click every 5 ticks in between) and the chest opens by itself at 62 with Odin's signal and the harp jingle. A line for the
 * room is `PUZZLE FAIL!` at once; the marks stay until the Architect's First Draft is used, one tick later they go and the room opens again.
 */
class TicTacToePuzzle(context: PuzzleContext) : WorldPuzzle(PuzzleKinds.TTT, context) {
    private var logic: TttLogic? = null
    private val frames = HashMap<Int, ItemFrame>()
    private var presser: UUID? = null

    override val wakeDelayTicks: Int get() = TttLogic.OPENING_TICKS
    override val rewardSignalAtGreen: Boolean get() = false

    override fun solvedPhrase(): String = "tied Tic Tac Toe!"

    /** The line and the check mark come at the last mark: green 2 ticks later, Completed 13 ticks later. */
    override fun solveTiming(): SolveTiming = SolveTiming(chat = 0, green = GREEN_TICKS, complete = COMPLETE_TICKS)

    override fun rewardSpots(): List<WorldPos> = listOfNotNull(world(CHEST_X, CHEST_Y, CHEST_Z))

    override fun build() {
        logic = TttLogic(run.subRandom("ttt:${context.room.index}"))
    }

    /** A stone button on every cell of the board (the captured room can come with the room's first mark already taken). */
    private fun placeButtons() {
        val button = roomState(BUTTON_STATE) ?: return
        for (c in 0..8) setAt(BOARD_X, rowY(c), colZ(c), button)
    }

    private fun rowY(cell: Int) = TOP_Y - cell / 3
    private fun colZ(cell: Int) = LEFT_Z + cell % 3

    /** A player stepped in 24 ticks ago: the room makes its first mark. */
    override fun wake() {
        if (status != PuzzleStatus.UNSOLVED) return
        placeButtons()
        openGame()
    }

    private fun openGame() {
        val l = logic ?: return
        val cell = l.roomOpens()
        if (cell >= 0) mark(cell, TttLogic.Mark.X)
    }

    // ---- marks ------------------------------------------------------------------------------------------------------------

    /** A mark on [cell]: the button goes and an item frame with the map of the mark takes its place. */
    private fun mark(cell: Int, m: TttLogic.Mark) {
        setAt(BOARD_X, rowY(cell), colZ(cell), Blocks.AIR.defaultBlockState())
        frames.remove(cell)?.let { forget(it) }
        val w = world(BOARD_X, rowY(cell), colZ(cell)) ?: return
        val facing = PuzzleBlocks.rotation(placement?.quarterTurns ?: 0).rotate(Direction.EAST)
        val frame = ItemFrame(EntityTypes.ITEM_FRAME, Sim.level, BlockPos(w.x, w.y, w.z), facing)
        frame.isPermanentlyInvulnerable = true
        val isX = m == TttLogic.Mark.X
        ensureMaps()
        frame.setItem(mapStack(if (isX) TttArt.X_MAP_ID else TttArt.O_MAP_ID), false)
        track(frame)
        Sim.spawn(frame)
        frames[cell] = frame
    }

    /** Takes every mark off the board and puts the buttons back. */
    private fun clearBoard() {
        frames.values.forEach { forget(it) }
        frames.clear()
        placeButtons()
    }

    private fun mapStack(id: Int): ItemStack {
        val s = ItemStack(Items.FILLED_MAP)
        s.set(DataComponents.MAP_ID, MapId(id))
        return s
    }

    /**
     * The two maps exist on the server so every player who sees a frame is sent the picture, and are sent once more to the players here
     * now (the first frame of each kind brings its map data on Hypixel).
     */
    private fun ensureMaps() {
        val level = Sim.level
        for ((id, isX) in listOf(TttArt.X_MAP_ID to true, TttArt.O_MAP_ID to false)) {
            val mid = MapId(id)
            val pixels = TttArt.pixels(isX)
            if (level.getMapData(mid) == null) {
                val data = MapItemSavedData.createFresh(0.0, 0.0, 0.toByte(), false, false, Level.OVERWORLD)
                System.arraycopy(pixels, 0, data.colors, 0, pixels.size)
                level.setMapData(mid, data)
            }
            if (id in sentMaps) continue
            sentMaps += id
            val packet = ClientboundMapItemDataPacket(mid, 0, false, Optional.empty(), Optional.of(MapItemSavedData.MapPatch(0, 0, TttArt.SIZE, TttArt.SIZE, pixels)))
            for (p in Sim.players()) p.connection.send(packet)
        }
    }

    private val sentMaps = HashSet<Int>()

    // ---- the game ---------------------------------------------------------------------------------------------------------

    override fun onInteract(player: UUID, x: Int, y: Int, z: Int): Boolean {
        val l = logic ?: return false
        val loc = local(x, z) ?: return false
        if (loc.first !in BOARD_X - 1..BOARD_X || y !in TOP_Y - 2..TOP_Y || loc.second !in LEFT_Z..LEFT_Z + 2) return false
        if (!awake || status != PuzzleStatus.UNSOLVED) return false
        val cell = (TOP_Y - y) * 3 + (loc.second - LEFT_Z)
        // Out of turn, or on a marked cell: the press only clicks the button.
        if (!l.press(cell)) return false
        presser = player
        mark(cell, TttLogic.Mark.O)
        playerOf(player)?.let { pling(it) }
        if (l.result != TttLogic.Result.PLAYING) { finish(l, player); return true }
        after(TttLogic.THINK_TICKS, TAG_GAME) { reply() }
        return true
    }

    /** The pling: loud, high, at the player's feet. */
    private fun pling(p: ServerPlayer) {
        Sim.sound(SoundEvents.NOTE_BLOCK_PLING, 8.0f, 4.047619f, Vec3(p.x, p.y, p.z), SoundSource.BLOCKS)
    }

    /** The room's answer, 60 ticks after the party's press. */
    private fun reply() {
        val l = logic ?: return
        if (status != PuzzleStatus.UNSOLVED) return
        val cell = l.roomReplies()
        if (cell >= 0) mark(cell, TttLogic.Mark.X)
        if (l.result != TttLogic.Result.PLAYING) finish(l, presser)
    }

    /** The game is over on the tick of the last mark: a tie solves the puzzle, a line for the room fails it. */
    private fun finish(l: TttLogic, by: UUID?) {
        when (l.result) {
            TttLogic.Result.TIE, TttLogic.Result.WON -> solveGame(by)
            TttLogic.Result.LOST -> fail(by, "lost Tic Tac Toe!")
            TttLogic.Result.PLAYING -> {}
        }
    }

    // ---- the solve --------------------------------------------------------------------------------------------------------

    private fun wallCells(): List<WorldPos> = buildList {
        for (z in LEFT_Z..LEFT_Z + 2) {
            for (y in TOP_Y - 2..TOP_Y) world(WALL_X, y, z)?.let { add(it) }
            world(WALL_X, TOP_Y - 3, z)?.let { add(it) }
        }
    }

    private fun placeChest() {
        val chest = rewardSpots().firstOrNull() ?: return
        roomState(CHEST_STATE)?.let { setWorld(chest, it) }
    }

    /** T: the chest appears and the marks, the wall and the chest follow their schedule. */
    private fun solveGame(by: UUID?) {
        placeChest()
        solve(by)
        after(FRAMES_TICKS) { frames.values.forEach { forget(it) }; frames.clear() }
        val wall = wallCells()
        after(WALL_BARRIER_TICKS) { wall.forEach { setWorld(it, Blocks.BARRIER.defaultBlockState()) } }
        world(WALL_X, TOP_Y - 1, LEFT_Z + 1)?.let { g ->
            val at = Vec3(g.x + 0.5, g.y + 0.5, g.z + 0.5)
            var t = WALL_BARRIER_TICKS
            while (t < WALL_OPEN_TICKS) { after(t) { Sim.sound(SoundEvents.WOODEN_PRESSURE_PLATE_CLICK_ON, 2.0f, 0.4920635f, at) }; t += 5 }
        }
        after(WALL_OPEN_TICKS) { wall.forEach { setWorld(it, Blocks.AIR.defaultBlockState()) } }
        after(CHEST_OPEN_TICKS) { openChest() }
    }

    /** The chest opens by itself: Odin's signal, the lid sound, the harp jingle. */
    private fun openChest() {
        val c = rewardSpots().firstOrNull() ?: return
        lidSignal(c)
        val at = Vec3(c.x + 0.5, c.y + 0.5, c.z + 0.5)
        Sim.sound(SoundEvents.CHEST_OPEN, 0.5f, 0.92f, at, SoundSource.BLOCKS)
        ChestJingle.PITCHES.forEachIndexed { i, pitch -> after(ChestJingle.FIRST_NOTE + ChestJingle.NOTE_GAP * i) { Sim.sound(SoundEvents.NOTE_BLOCK_HARP, 1.0f, pitch, at) } }
    }

    /** The room as it stands once solved (called when a savepoint restores a solved room): the chest there, the wall open, no marks. */
    override fun applySolved() {
        placeChest()
        wallCells().forEach { setWorld(it, Blocks.AIR.defaultBlockState()) }
    }

    /** The draft is used: the marks go one tick later and the room opens a new game. */
    override fun onReset() {
        cancelTimers(TAG_GAME)
        logic?.reset()
        after(RESET_TICKS) {
            clearBoard()
            after(TttLogic.OPENING_TICKS, TAG_GAME) { openGame() }
        }
    }

    companion object {
        const val BOARD_X = 8
        const val WALL_X = 7
        const val TOP_Y = 72
        const val LEFT_Z = 15

        /** The reward chest appears here at the solve, in the room's frame, facing east. */
        const val CHEST_X = 2
        const val CHEST_Y = 70
        const val CHEST_Z = 16
        private const val CHEST_STATE = "minecraft:chest[facing=east,type=single,waterlogged=false]"
        private const val BUTTON_STATE = "minecraft:stone_button[face=wall,facing=east,powered=false]"

        /** After the last mark: the room turns green, the puzzle counts as Completed (the tab then shows it), the marks go, the wall turns to barrier, then to air, the chest opens. */
        const val GREEN_TICKS = 2
        const val COMPLETE_TICKS = 13
        const val FRAMES_TICKS = 29
        const val WALL_BARRIER_TICKS = 30
        const val WALL_OPEN_TICKS = 56
        const val CHEST_OPEN_TICKS = 62

        /** The marks go this long after the reset item is used. */
        const val RESET_TICKS = 1

        private const val TAG_GAME = "game"
    }
}
