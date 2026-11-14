package com.engineerclient.dungeon

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.puzzlesolvers.PuzzleSolvers
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Puzzle Solvers' Tic Tac Toe: highlights the best button to press. */
object TicTacToeSolver {
    enum class Target(private val label: String) {
        PARTY("Party"), SELF("Self");
        override fun toString() = label
    }

    private val dropdown = DropdownSetting("Tic Tac Toe", desc = "Shows settings for the Tic Tac Toe puzzle solver.")
    private val solver = BooleanSetting("Tic Tac Toe Solver", true, desc = "Highlights the best button to press.").withDependency { dropdown.value }
    private val bestColorSetting = ColorSetting("Tic Tac Toe Best Color", Color(0, 255, 0, 0.6f), true, desc = "The move to make.").withDependency { dropdown.value && solver.value }
    private val showNextSetting = BooleanSetting("Tic Tac Toe Show Next", true, desc = "Shows your next move while the room thinks.").withDependency { dropdown.value && solver.value }
    private val nextColorSetting = ColorSetting("Tic Tac Toe Next Color", Color(255, 255, 0, 0.6f), true, desc = "Used when your next move depends on the room's.").withDependency { dropdown.value && solver.value && showNextSetting.value }
    private val countdownSetting = BooleanSetting("Tic Tac Toe Countdown", false, desc = "Shows the time until the room answers.").withDependency { dropdown.value && solver.value }
    private val sendMessageSetting = BooleanSetting("Tic Tac Toe Send Message", false, desc = "Says \"Tic Tac Toe done\" once the game can't be lost.").withDependency { dropdown.value && solver.value }
    private val messageToSetting = SelectorSetting("Tic Tac Toe Message To", Target.PARTY, desc = "Party chat or only you.").withDependency { dropdown.value && solver.value && sendMessageSetting.value }

    private val bestColor get() = bestColorSetting.value
    private val showNext get() = showNextSetting.value
    private val nextColor get() = nextColorSetting.value
    private val countdown get() = countdownSetting.value
    private val sendMessage get() = sendMessageSetting.value
    private val messageTo get() = messageToSetting.value

    private val mc: Minecraft get() = Minecraft.getInstance()

    private const val ROOM = "Tic Tac Toe"
    private const val EMPTY = ' '
    private const val REPLY_TICKS = 59
    private val LINES = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
        intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
        intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
    )

    private var best: List<BlockPos> = emptyList()
    private var next: List<BlockPos> = emptyList()
    private var planned: Map<String, Int> = emptyMap()
    private var above: Vec3? = null
    private var lastBoard = ""
    private var sent = false
    private var serverTicks = 0L
    private var replyAt = 0L

    private val values = HashMap<String, Int>()
    private val chances = HashMap<String, Double>()
    private val plans = HashMap<String, Pair<Double, Int>>()

    fun install() {
        for (s in listOf(dropdown, solver, bestColorSetting, showNextSetting, nextColorSetting, countdownSetting, sendMessageSetting, messageToSetting))
            PuzzleSolvers.registerSetting(s)
        on<LevelEvent.Load> { reset(); sent = false }
        on<TickEvent.End> {
            if (!PuzzleSolvers.enabled || !solver.value || !DungeonUtils.inClear || DungeonUtils.currentRoomName != ROOM) { reset(); return@on }
            EngineerClient.safely("tic tac toe") { scan() }
        }
        on<TickEvent.Server> { serverTicks++ }
        on<RenderExtractEvent> {
            if (!PuzzleSolvers.enabled || !solver.value) return@on
            if (showNext) for (pos in next) buttonBox(pos)?.let { drawFilledBox(it, if (next.size == 1) bestColor else nextColor, depth = true) }
            for (pos in best) buttonBox(pos)?.let { drawFilledBox(it, bestColor, depth = true) }
            val left = replyAt - serverTicks
            if (countdown && left > 0) above?.let { drawText("%.1f".format(left / 20.0), it, 1.5f, false) }
        }
        EventBus.subscribe(this)
    }

    private fun reset() { clear(); planned = emptyMap(); lastBoard = "" }

    private fun clear() { best = emptyList(); next = emptyList(); above = null; replyAt = 0 }

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

        if (board.count { it != EMPTY } != frames.size) return
        val now = String(board)
        if (now == lastBoard) return
        lastBoard = now

        if (frames.size == 8) {
            if (!won(now, 'X')) done()
            clear()
            return
        }
        val origin = corner
        if (origin == null || frames.isEmpty() || won(now, 'X') || won(now, 'O')) { clear(); return }

        fun toPos(index: Int) = BlockPos(
            if (alongX) origin.x - sign * (index % 3) else origin.x,
            72 - index / 3,
            if (alongX) origin.z else origin.z - sign * (index % 3),
        )

        if (frames.size % 2 == 0) {
            planned = answers(now)
            best = emptyList()
            next = planned.values.distinct().map(::toPos)
            above = toPos(1).let { Vec3(it.x + 0.5, it.y + 1.5, it.z + 0.5) }
            replyAt = serverTicks + REPLY_TICKS
            return
        }
        best = (planned[now]?.let(::listOf) ?: moves(now)).map(::toPos)
        next = emptyList()
        replyAt = 0
    }

    private fun done() {
        if (sent || !sendMessage) return
        sent = true
        if (messageTo == Target.PARTY) sendCommand("pc Tic Tac Toe done") else EngineerClient.chat("§aTic Tac Toe done")
    }

    private fun won(b: String, p: Char) = LINES.any { (a, c, d) -> b[a] == p && b[c] == p && b[d] == p }

    private fun free(b: String) = b.indices.filter { b[it] == EMPTY }

    private fun put(b: String, i: Int, p: Char) = b.replaceRange(i, i + 1, p.toString())

    // 1 the room wins, 0 a tie, -1 the player wins.
    private fun value(b: String, turn: Char): Int = values.getOrPut(b) {
        when {
            won(b, 'X') -> 1
            won(b, 'O') -> -1
            else -> {
                val results = free(b).map { value(put(b, it, turn), if (turn == 'X') 'O' else 'X') }
                if (results.isEmpty()) 0 else if (turn == 'X') results.max() else results.min()
            }
        }
    }

    // The room plays perfectly and picks evenly among its equally good moves.
    private fun replies(b: String): List<Int> {
        val scores = free(b).associateWith { value(put(b, it, 'X'), 'O') }
        val top = scores.values.maxOrNull() ?: return emptyList()
        return scores.filterValues { it == top }.keys.toList()
    }

    private fun holds(b: String): List<Int> {
        val scores = free(b).associateWith { value(put(b, it, 'O'), 'X') }
        val low = scores.values.minOrNull() ?: return emptyList()
        return scores.filterValues { it == low }.keys.toList()
    }

    private fun moves(b: String): List<Int> {
        val scores = holds(b).associateWith { plan(put(b, it, 'O')).first }
        val top = scores.values.maxOrNull() ?: return emptyList()
        return scores.filterValues { it >= top - 1e-9 }.keys.toList()
    }

    // The player's move after each reply the room can make, reusing a button wherever one works for several replies.
    private fun answers(b: String): Map<String, Int> {
        val spam = plan(b).second
        val picked = LinkedHashMap<String, Int>()
        for (reply in replies(b)) {
            val after = put(b, reply, 'X')
            if (won(after, 'X')) continue
            val options = moves(after)
            picked[after] = when {
                spam != reply && spam in holds(after) -> spam
                else -> options.firstOrNull { it in picked.values } ?: options.firstOrNull() ?: continue
            }
        }
        return picked
    }

    // How many of the player's remaining moves can be spammed ahead, on average, with the player to move.
    private fun chance(b: String): Double = chances.getOrPut(b) {
        if (won(b, 'X') || won(b, 'O')) 0.0 else holds(b).maxOfOrNull { plan(put(b, it, 'O')).first } ?: 0.0
    }

    // With the room to move: the same count, and the button to spam (-1 for none). A button qualifies when it
    // keeps the tie after every reply; the room taking it first costs nothing, since that click is ignored.
    private fun plan(b: String): Pair<Double, Int> = plans.getOrPut(b) {
        val replies = replies(b)
        if (won(b, 'O') || free(b).size < 2) return@getOrPut 0.0 to -1
        var top = replies.sumOf { chance(put(b, it, 'X')) } / replies.size to -1
        for (cell in free(b)) {
            val safe = replies.all { reply -> reply == cell || put(b, reply, 'X').let { !won(it, 'X') && cell in holds(it) } }
            if (!safe) continue
            val score = replies.sumOf { reply ->
                val after = put(b, reply, 'X')
                if (reply == cell) chance(after) else 1 + plan(put(after, cell, 'O')).first
            } / replies.size
            if (score > top.first + 1e-9) top = score to cell
        }
        top
    }
}
