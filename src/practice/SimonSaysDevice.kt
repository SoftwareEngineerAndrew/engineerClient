package com.engineerclient.practice

/**
 * Simon Says (F7 P3's first device) as Hypixel runs it since the October 2026 update, as measured from recorded
 * runs. One device, shared by SS Practice and the P3 Sim: each draws its lamps and buttons its own way ([Io]) and
 * calls [tick] once a server tick, after that tick's presses.
 *
 * The start button is a start/reset toggle:
 *  - an accepted press while stopped starts it: a fresh pattern is drawn at once, and a round task is due
 *    [ROUND_DELAY] ticks later;
 *  - an accepted press while started resets it: round 0, lamps off, buttons gone. A reset doesn't cancel the
 *    round tasks already due, nor the shows they started.
 * A round task adds a round and shows that many lamps, or past [MAX_ROUNDS] completes the device.
 *
 * Each player's presses pass a limiter of about 50 ms ([LIMIT_MS]); a dropped press does nothing at all.
 *
 * A show (round task): every lamp off and every button gone, then lamp j at [Io.lampTick] j (8j in the game)
 * lights the pattern's cell j as it is THEN (a later start redraws it) and takes its button; each lamp goes
 * out 8 ticks after it lit; the buttons come back after the last lamp's tick + 10, except under lit lamps.
 * When several shows are running, the buttons come back on the first one's timer while the last one (the
 * round to put in) may still be lighting.
 */
class SimonSaysDevice(private val io: Io) {

    interface Io {
        /** A new pattern: [MAX_ROUNDS] different cells (0-15: row from the top, column left to right). */
        fun newSequence(): IntArray
        fun lamp(cell: Int, on: Boolean)
        fun button(cell: Int, up: Boolean, pressed: Boolean)
        /** When lamp [j] of a show lights, in ticks from its start: 8 apart in the game. */
        fun lampTick(j: Int): Int = 8 * j
        /** The buttons are back and pressing counts, for [round]. */
        fun opened(round: Int) {}
        /** Done: [by] gets the credit. */
        fun completed(by: String)
    }

    enum class Start { STARTED, RESET, DROPPED, IGNORED }
    enum class Press {
        /** The button isn't there, or is still down from the last press: nothing happens. */
        NONE,
        /** Pressed, but no round is being put in. */
        IDLE,
        RIGHT,
        /** Right, and the round's last: the next round (or done) in [NEXT_ROUND_DELAY] ticks. */
        ROUND,
        WRONG,
    }

    /** The tick a press now is handled in; [tick] runs this tick's tasks and moves on. */
    var now = 0L
        private set

    private class Job(val at: Long, val gen: Int, val order: Long, val run: () -> Unit)
    private val jobs = ArrayList<Job>()
    private var gen = 0
    private var order = 0L

    /** Runs [run] [ticks] ticks from now (0: in this tick's tasks). Only [clear] drops it. */
    fun after(ticks: Int, run: () -> Unit) { jobs += Job(now + ticks.coerceAtLeast(0), gen, order++, run) }

    /** Rounds shown since the last reset: the round being put in. */
    var round = 0
        private set
    /** The toggle: the next accepted start press resets. */
    var started = false
        private set
    var done = false
        private set
    var sequence = IntArray(MAX_ROUNDS)
        private set
    private var lastStarter = ""
    /** The buttons are up for [round] and presses count. */
    var accepting = false
        private set
    /** How many of the round are pressed. */
    var next = 0
        private set

    private val lit = BooleanArray(16)
    private val up = BooleanArray(16)
    private val downUntil = LongArray(16)

    /** The newest show's cells so far, as they light: what a solver shows (an older show's lamps never). */
    val shown = ArrayList<Int>()
    private var showId = 0

    private class Last(val tick: Long, val ms: Double)
    private val lastPress = HashMap<String, Last>()
    private val random = java.util.Random()

    /** The cell to press next, while a round is being put in. */
    fun due(): Int? = if (accepting && next < round) sequence[next] else null

    fun pressable(cell: Int) = up[cell] && downUntil[cell] <= now

    /** Runs this tick's tasks (after its presses), then moves to the next tick. */
    fun tick() {
        while (true) {
            val due = jobs.filter { it.at <= now }
            if (due.isEmpty()) break
            jobs.removeAll(due.toSet())
            for (j in due.sortedBy { it.order }) if (j.gen == gen) j.run()
        }
        now++
    }

    /**
     * A start button press by [who], arriving at [ms] (any ms clock, the same for all their presses).
     * [limit] false: no limiter (a replay of presses already accepted).
     */
    fun start(who: String, ms: Double, limit: Boolean = true): Start {
        if (done) return Start.IGNORED
        if (limit && !limiter(who, ms)) return Start.DROPPED
        if (started) {
            started = false
            reset()
            return Start.RESET
        }
        started = true
        lastStarter = who
        sequence = io.newSequence()
        after(ROUND_DELAY) { nextRound(lastStarter) }
        return Start.STARTED
    }

    private fun limiter(who: String, ms: Double): Boolean {
        // When it reaches the server: a little noise on top, the network's.
        val at = ms + random.nextGaussian() * JITTER_MS
        val last = lastPress[who]
        if (last != null) {
            val ticks = now - last.tick
            if (ticks <= 0 || ticks == 1L && at - last.ms < LIMIT_MS) return false
        }
        lastPress[who] = Last(now, at)
        return true
    }

    private fun reset() {
        round = 0; accepting = false; next = 0
        clearLamps()
        for (c in 0 until 16) setButton(c, false)
    }

    private fun nextRound(by: String) {
        if (done) return
        round++
        if (round > MAX_ROUNDS) { complete(by); return }
        startShow()
    }

    /**
     * A round shown with no start press: a fresh pattern and round [n] at once (after a wrong press, or a
     * practice mode that starts on a round). The toggle is left as it is.
     */
    fun showRound(n: Int) {
        if (done) return
        sequence = io.newSequence()
        round = n - 1
        nextRound(lastStarter)
    }

    private fun startShow() {
        clearLamps()
        for (c in 0 until 16) setButton(c, false)
        accepting = false; next = 0
        val id = ++showId
        shown.clear()
        val n = round
        for (j in 0 until n) {
            val light = {
                val cell = sequence[j]
                setLamp(cell, true)
                setButton(cell, false)
                if (id == showId) shown += cell
                after(io.lampTick(j + 1) - io.lampTick(j)) { setLamp(cell, false) }
            }
            val at = io.lampTick(j)
            if (at <= 0) light() else after(at, light)
        }
        after(io.lampTick(n) + 10) { buttonsBack(accept = true) }
    }

    /**
     * Every button back but under lit lamps. [accept]: presses count from now (with a round shown); else they
     * only press (the stale buttons of a restart after a wrong press).
     */
    fun buttonsBack(accept: Boolean = false) {
        for (c in 0 until 16) if (!lit[c]) setButton(c, true)
        if (accept && round >= 1 && !accepting && !done) {
            accepting = true
            io.opened(round)
        }
    }

    private fun complete(by: String) {
        done = true
        accepting = false
        gen++
        jobs.clear()
        clearLamps()
        for (c in 0 until 16) setButton(c, false)
        shown.clear()
        io.completed(by)
    }

    /** A grid button press by [who]. */
    fun press(cell: Int, who: String): Press {
        if (!pressable(cell)) return Press.NONE
        io.button(cell, true, true)
        downUntil[cell] = now + 3
        after(3) { if (up[cell]) io.button(cell, true, false) }
        if (!accepting || done) return Press.IDLE
        if (cell == sequence[next]) {
            next++
            if (next < round) return Press.RIGHT
            accepting = false
            after(NEXT_ROUND_DELAY) { nextRound(who) }
            return Press.ROUND
        }
        // Wrong: back to round 0 and stopped; the buttons go 3 ticks later.
        accepting = false; next = 0; started = false; round = 0
        after(3) { for (c in 0 until 16) setButton(c, false) }
        return Press.WRONG
    }

    /** Everything off and forgotten, tasks and shows included: a new device. */
    fun clear() {
        gen++
        jobs.clear()
        round = 0; started = false; done = false; accepting = false; next = 0
        shown.clear()
        lastPress.clear()
        clearLamps()
        for (c in 0 until 16) setButton(c, false)
    }

    private fun setLamp(cell: Int, on: Boolean) {
        if (lit[cell] == on) return
        lit[cell] = on
        io.lamp(cell, on)
    }

    private fun clearLamps() { for (c in 0 until 16) setLamp(c, false) }

    private fun setButton(cell: Int, show: Boolean) {
        if (up[cell] == show) return
        up[cell] = show
        io.button(cell, show, false)
    }

    companion object {
        /** Rounds since Hypixel's October 2026 update (5 before). */
        const val MAX_ROUNDS = 4
        /** A start press's round task: 4 ticks after the tick it's handled in (5 after its echo). */
        const val ROUND_DELAY = 4
        /** A round's last right press to the next round's show. */
        const val NEXT_ROUND_DELAY = 6
        /** The limiter: a player's press reaching the server closer than this after their last accepted one. */
        const val LIMIT_MS = 49.0
        /** Arrival noise per press (ms). */
        const val JITTER_MS = 6.0
    }
}
