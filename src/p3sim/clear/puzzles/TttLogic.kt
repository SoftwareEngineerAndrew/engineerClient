package com.engineerclient.p3sim.clear.puzzles

import kotlin.random.Random

/**
 * Tic Tac Toe against the room, as the recorded rooms play it. The ROOM plays X and moves first, the party plays O and can only tie: a tie
 * solves the puzzle ("tied Tic Tac Toe!"), a mistake that lets the room complete a line fails it ("lost Tic Tac Toe!"). Measured: 109 ties
 * and 4 losses in 113 recorded games, and the room's move was the best one in all 15 moves of the three games timed closely.
 *
 * The room's first mark is one of the cells by the odds seen in 62 recorded games (the centre 39 %, each corner about 15 %, an edge now
 * and then); its later moves are minimax-best, a random one of the equally good cells. UNMEASURED: how the real room picks among equally
 * good cells after the first move. Cells are 0..8, row by row from the top left. Pure: no Minecraft.
 */
class TttLogic(private val random: Random = Random.Default) {
    /** [X] is the room's mark, [O] the party's. */
    enum class Mark { EMPTY, X, O }

    /** From the party's side: [LOST] when the room has a line, [TIE] when the board is full, [WON] when the party has one (the room never allows it). */
    enum class Result { PLAYING, TIE, LOST, WON }

    private val board = Array(9) { Mark.EMPTY }

    var result = Result.PLAYING
        private set

    /** True while the room is to move (it moves first, and after each accepted press of the party). */
    var roomToMove = true
        private set

    fun mark(cell: Int): Mark = board[cell]

    /** How many cells are marked. */
    val marks: Int get() = board.count { it != Mark.EMPTY }

    /**
     * The room places its first mark: on [at] when that is given (tests), else on a cell drawn by the measured odds. Returns the cell, or -1
     * when the board is not empty or it is not the room's move.
     */
    fun roomOpens(at: Int = -1): Int {
        if (result != Result.PLAYING || !roomToMove || marks != 0) return -1
        val cell = if (at in 0..8) at else pick(OPENING_ODDS)
        place(cell, Mark.X)
        return cell
    }

    /** The party presses [cell]: accepted only on an empty cell while it is the party's move. The O goes there at once; the room answers with [roomReplies]. */
    fun press(cell: Int): Boolean {
        if (result != Result.PLAYING || roomToMove || cell !in 0..8 || board[cell] != Mark.EMPTY) return false
        place(cell, Mark.O)
        return true
    }

    /** The room answers with a best move (a random one of the equally good). Returns the cell, or -1 when it is not the room's move or the game is over. */
    fun roomReplies(): Int {
        if (result != Result.PLAYING || !roomToMove) return -1
        val best = bestMoves(board.copyOf())
        if (best.isEmpty()) return -1
        val cell = best[random.nextInt(best.size)]
        place(cell, Mark.X)
        return cell
    }

    private fun place(cell: Int, m: Mark) {
        board[cell] = m
        roomToMove = m == Mark.O
        result = when {
            wins(board, Mark.X) -> Result.LOST
            wins(board, Mark.O) -> Result.WON
            board.none { it == Mark.EMPTY } -> Result.TIE
            else -> Result.PLAYING
        }
    }

    private fun pick(odds: DoubleArray): Int {
        var roll = random.nextDouble() * odds.sum()
        for (i in odds.indices) { roll -= odds[i]; if (roll < 0) return i }
        return odds.lastIndex
    }

    fun reset() { board.fill(Mark.EMPTY); result = Result.PLAYING; roomToMove = true }

    companion object {
        private val LINES = listOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8), intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )

        /**
         * The room's first cell, as weights over the nine cells: the centre 39 %, the four corners 14.85 % each, the four edges 0.4 % each
         * (62 recorded games: centre 24, corners 9 / 9 / 9 / 10, edges 1 in all).
         */
        val OPENING_ODDS = doubleArrayOf(0.1485, 0.004, 0.1485, 0.004, 0.39, 0.004, 0.1485, 0.004, 0.1485)

        /** The room waits this long after the party's press before it answers (measured: 60, 61, 60, 60, 59, 60, 60). */
        const val THINK_TICKS = 60

        /** The room's first mark appears this long after a player steps into the room (median of 11 games; 17 to 42). */
        const val OPENING_TICKS = 24

        fun wins(b: Array<Mark>, m: Mark) = LINES.any { l -> l.all { b[it] == m } }

        /**
         * Every cell the room (X) may play that keeps the best result for it, scanning 0..8. A win now beats a win later (and a loss later beats a
         * loss now), so the room takes a line the moment it can; among moves that are equally good the room's pick is random.
         * Score: 10 - moves played for an X win, 0 for a tie, the reverse for an O win.
         */
        fun bestMoves(b: Array<Mark>): List<Int> {
            var best = -100
            val cells = ArrayList<Int>()
            val played = b.count { it != Mark.EMPTY }
            for (i in 0..8) if (b[i] == Mark.EMPTY) {
                b[i] = Mark.X
                val s = score(b, oToMove = true, played = played + 1)
                b[i] = Mark.EMPTY
                if (s > best) { best = s; cells.clear() }
                if (s == best) cells += i
            }
            return cells
        }

        /** Every cell the party (O) may play that keeps the best result for it (a tie, against this room). Same scoring as [bestMoves]. */
        fun bestPartyMoves(b: Array<Mark>): List<Int> {
            var best = 100
            val cells = ArrayList<Int>()
            val played = b.count { it != Mark.EMPTY }
            for (i in 0..8) if (b[i] == Mark.EMPTY) {
                b[i] = Mark.O
                val s = score(b, oToMove = false, played = played + 1)
                b[i] = Mark.EMPTY
                if (s < best) { best = s; cells.clear() }
                if (s == best) cells += i
            }
            return cells
        }

        private fun score(b: Array<Mark>, oToMove: Boolean, played: Int): Int {
            if (wins(b, Mark.X)) return 10 - played
            if (wins(b, Mark.O)) return played - 10
            if (b.none { it == Mark.EMPTY }) return 0
            val m = if (oToMove) Mark.O else Mark.X
            var best = if (oToMove) 100 else -100
            for (i in 0..8) if (b[i] == Mark.EMPTY) {
                b[i] = m
                val s = score(b, !oToMove, played + 1)
                b[i] = Mark.EMPTY
                best = if (oToMove) minOf(best, s) else maxOf(best, s)
            }
            return best
        }
    }
}

/**
 * The picture on the two maps of Tic Tac Toe: every mark is an item frame holding map 30876 (the room's red X) or 30877 (the party's red
 * O). A 128 x 128 map of colour ids: 14 outside the inner area (x 8 to 120, y 9 to 118), 33 inside it, 114 for the stroke. The shapes are
 * approximations drawn from a few numbers (the O is a ring about 54 across and 31 inside the middle, the X two diagonal bars); the
 * recorded art is not shipped. Pure: no Minecraft.
 */
object TttArt {
    const val X_MAP_ID = 30876
    const val O_MAP_ID = 30877
    const val SIZE = 128
    const val BORDER: Byte = 14
    const val BACKGROUND: Byte = 33
    const val STROKE: Byte = 114

    /** The map's colour bytes, row by row. */
    fun pixels(x: Boolean): ByteArray {
        val out = ByteArray(SIZE * SIZE) { BORDER }
        for (y in 9..118) for (px in 8..120) {
            val on = if (x) onX(px, y) else onO(px, y)
            out[y * SIZE + px] = if (on) STROKE else BACKGROUND
        }
        return out
    }

    private fun onO(px: Int, py: Int): Boolean {
        val dx = px - 62.8; val dy = py - 64.2
        val outer = (dx / 53.8) * (dx / 53.8) + (dy / 55.7) * (dy / 55.7)
        val inner = (dx / 31.0) * (dx / 31.0) + (dy / 31.8) * (dy / 31.8)
        return outer <= 1.0 && inner > 1.0
    }

    private fun onX(px: Int, py: Int): Boolean {
        if (px < 17 || px > 106 || py < 24 || py > 111) return false
        val t = py - 60.9
        return Math.abs(px - (64.1 + 1.2 * t)) <= 14.0 || Math.abs(px - (64.1 - 1.2 * t)) <= 14.0
    }
}
