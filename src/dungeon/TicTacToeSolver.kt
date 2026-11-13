package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

object TicTacToeSolver : Module(
    name = "Tic Tac Toe Solver",
    category = Category.DUNGEON,
    description = "Highlights the best button to press in Tic Tac Toe.",
    key = null,
) {
    enum class Target(private val label: String) {
        PARTY("Party"), SELF("Self");
        override fun toString() = label
    }

    private val bestColor by ColorSetting("Best Color", Color(0, 255, 0, 0.6f), true, desc = "The move to make.")
    private val predictNext by BooleanSetting("Predict Next", false, desc = "Also shows your likely next move.")
    private val nextColor by ColorSetting("Next Color", Color(255, 165, 0, 0.6f), true, desc = "Your likely next move.").withDependency { predictNext }
    private val sendMessage by BooleanSetting("Send Message", false, desc = "Says \"Tic Tac Toe done\" once the game can't be lost.")
    private val messageTo by SelectorSetting("Message To", Target.PARTY, desc = "Party chat or only you.").withDependency { sendMessage }

    private const val ROOM = "Tic Tac Toe"
    private const val EMPTY = ' '
    private val LINES = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
        intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
        intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
    )

    private var best: List<BlockPos> = emptyList()
    private var next: List<BlockPos> = emptyList()
    private var lastBoard = ""
    private var sent = false

    init {
        on<LevelEvent.Load> { reset(); sent = false }
        on<TickEvent.End> {
            if (!DungeonUtils.inClear || DungeonUtils.currentRoomName != ROOM) { reset(); return@on }
            EngineerClient.safely("tic tac toe") { scan() }
        }
        on<RenderExtractEvent> {
            if (predictNext) for (pos in next) if (pos !in best) buttonBox(pos)?.let { drawFilledBox(it, nextColor, depth = true) }
            for (pos in best) buttonBox(pos)?.let { drawFilledBox(it, bestColor, depth = true) }
        }
    }

    private fun reset() { best = emptyList(); next = emptyList(); lastBoard = "" }

    private fun buttonBox(pos: BlockPos): AABB? {
        val level = mc.level ?: return null
        val state = level.getBlockState(pos)
        if (!state.`is`(Blocks.STONE_BUTTON)) return null
        val shape = state.getShape(level, pos)
        return if (shape.isEmpty) null else shape.bounds().move(pos).inflate(0.01)
    }

    private fun scan() {
        val level = mc.level ?: return
        val me = mc.player ?: return
        val cx = Math.floorDiv(me.blockX + 200, 32) * 32 - 185
        val cz = Math.floorDiv(me.blockZ + 200, 32) * 32 - 185
        val frames = level.getEntitiesOfClass(ItemFrame::class.java, AABB(cx - 9.0, 65.0, cz - 9.0, cx + 10.0, 74.0, cz + 10.0)) {
            it.item.item is MapItem && it.item.has(DataComponents.MAP_ID)
        }

        val board = CharArray(9) { EMPTY }
        var corner: BlockPos? = null
        var alongX = true
        var sign = 1
        for (frame in frames) {
            val data = level.getMapData(frame.item.get(DataComponents.MAP_ID) ?: continue) ?: continue
            sign = if (frame.direction == Direction.SOUTH || frame.direction == Direction.WEST) -1 else 1
            val at = frame.blockPosition()
            alongX = frame.x % 0.5 == 0.0
            var column = 0
            for (i in 2 downTo 0) {
                val pos = if (alongX) at.offset(i * sign, 0, 0) else at.offset(0, 0, i * sign)
                val state = level.getBlockState(pos)
                if (state.`is`(Blocks.STONE_BUTTON) || state.isAir) { corner = pos; column = i; break }
            }
            val row = (72 - frame.y.toInt()).takeIf { it in 0..2 } ?: continue
            when (data.colors[8256].toInt() and 0xFF) {
                114 -> board[row * 3 + column] = 'X'
                33 -> board[row * 3 + column] = 'O'
            }
        }

        val key = String(board) + frames.size
        if (key == lastBoard) return
        lastBoard = key

        if (frames.size == 8) {
            if (!won(board, 'X')) done()
            best = emptyList(); next = emptyList()
            return
        }
        val origin = corner
        if (frames.size % 2 == 0 || origin == null) { best = emptyList(); next = emptyList(); return }

        fun toPos(index: Int) = BlockPos(
            if (alongX) origin.x - sign * (index % 3) else origin.x,
            72 - index / 3,
            if (alongX) origin.z else origin.z - sign * (index % 3),
        )

        val moves = bestMoves(board, 'O', 'X')
        best = moves.map(::toPos)
        next = moves.firstOrNull()?.let { mine ->
            val after = board.copyOf().also { it[mine] = 'O' }
            bestMoves(after, 'X', 'O').flatMap { reply ->
                val then = after.copyOf().also { it[reply] = 'X' }
                if (won(then, 'X')) emptyList() else bestMoves(then, 'O', 'X')
            }.distinct().map(::toPos)
        }.orEmpty()
    }

    private fun done() {
        if (sent || !sendMessage) return
        sent = true
        if (messageTo == Target.PARTY) sendCommand("pc Tic Tac Toe done") else EngineerClient.chat("§aTic Tac Toe done")
    }

    private fun won(b: CharArray, p: Char) = LINES.any { (a, c, d) -> b[a] == p && b[c] == p && b[d] == p }

    private fun bestMoves(board: CharArray, me: Char, them: Char): List<Int> {
        val scores = board.indices.filter { board[it] == EMPTY }.associateWith { m ->
            minimax(board.copyOf().also { it[m] = me }, me, them, false, 1)
        }
        val top = scores.values.maxOrNull() ?: return emptyList()
        return scores.filterValues { it >= top - 1e-4 }.keys.toList()
    }

    private fun minimax(b: CharArray, me: Char, them: Char, myTurn: Boolean, depth: Int): Double {
        if (won(b, me)) return 1.0 / depth
        if (won(b, them)) return -1.0 / depth
        val free = b.indices.filter { b[it] == EMPTY }
        if (free.isEmpty()) return 0.0
        val results = free.map { m -> minimax(b.copyOf().also { it[m] = if (myTurn) me else them }, me, them, !myTurn, depth + 1) }
        return if (myTurn) results.max() else results.min()
    }
}
