package com.engineerclient.p3sim

/**
 * Plays one of your [GhostRun]s back as a teammate, against a party that isn't the one you had.
 *
 * Played straight off the clock it would fall apart the first time anyone is faster or slower than
 * your party was (it would leave an early enter before the leaps came, leap onto someone who isn't
 * there yet, stand at a terminal of a section that hasn't started). So the run is cut into
 * [Action]s - what you did - each with what it waited for in your run ([Anchor]s: its section
 * starting, the early enterer you leapt to being on its spot, everyone being on you at yours, the
 * recore). Live, an action happens when the same things have happened, plus the time you took after
 * the last of them (your reaction, your terminal). Your walking between actions plays at your pace;
 * only the standing about before an action stretches (waiting longer) or is cut short.
 *
 * After a leap you land where your target is now, not where it was then: the difference is blended
 * out over the walk to your next action, which you reach exactly where you did.
 */
class GhostPlayer(val run: GhostRun, private val world: World, private val body: Body) {

    /** The live fight, as the ghost sees it (null: not yet). Classes are [DungeonClass] names. */
    interface World {
        /** n the section started (5: the core opened), null: not yet. */
        fun sectionStart(s: Int): Int?
        /** Finished already (by anyone). */
        fun stationDone(id: String): Boolean
        /** It can be done now (its section in progress - a device's or earlier -, nobody at it, not held). */
        fun canDo(id: String): Boolean
        fun gateDown(s: Int): Boolean
        fun canGate(s: Int): Boolean
        /** n [who] was on its early-enter spot for [into] (they're not the early enterer there: anything, not waited for). */
        fun eeArrivedAt(into: Int, who: String): Int?
        /** n everyone was on this ghost at its early enter into [into]. */
        fun eeReadyAt(into: Int): Int?
        /** n the core early enterer [who] was in the core, on the recore. */
        fun recoredAt(who: String): Int?
        /** Where [who] is now: you, a bot or another ghost. */
        fun positionOf(who: String): P?
    }

    /** What the ghost does in the live fight. */
    interface Body {
        fun move(p: P, yaw: Float, pitch: Float, held: String)
        fun complete(id: String)
        fun gate(s: Int)
        /** Onto [who], landing at [at]. */
        fun leap(who: String, at: P)
        fun swing()
        fun eeOn(into: Int)
        fun eeLeft(into: Int)
        fun recore()
        /** At this terminal doing it (null: not). */
        fun working(id: String?)
        /** It couldn't do [job] (a station id or "gate k"): someone else must. */
        fun giveUp(job: String)
    }

    /**
     * [ENTER]: into the core (not through its door before it opens). [GO]: off from where you stood
     * until a section started (on an early enterer, at the door), once it has.
     */
    enum class Kind { DONE, GATE, LEAP, EE_ON, EE_LEAVE, RECORE, ENTER, GO }

    /** What an action waited for in your run, and when that was. */
    sealed class Anchor(val rec: Int) {
        class Section(val s: Int, rec: Int) : Anchor(rec)
        class EeOn(val into: Int, val who: String, rec: Int) : Anchor(rec)
        class Ready(val into: Int, rec: Int) : Anchor(rec)
        class Recore(val who: String, rec: Int) : Anchor(rec)
        override fun toString() = "${javaClass.simpleName}@$rec"
    }

    /**
     * [rec]: n you did it. [settle]: n you got there - from then to [rec] you stood about, which live
     * stretches or is cut short. [work]: how long after the last thing it waited for you did it.
     */
    class Action(val kind: Kind, val rec: Int, val settle: Int, val arg: String, val k: Int, val anchors: List<Anchor>) {
        val waitedFor = maxOf(settle, anchors.maxOfOrNull { it.rec } ?: settle)
        val work = (rec - waitedFor).coerceAtLeast(0)
        val job get() = if (kind == Kind.GATE) "gate $k" else arg
        override fun toString() = "$kind(${if (kind == Kind.DONE || kind == Kind.LEAP) arg else "$k"} @$rec settle $settle work $work $anchors)"
    }

    val actions: List<Action> = build(run)

    /** Live n minus run n: how far behind (or ahead of) your run the ghost is. */
    var shift = 0
        private set
    /** The next action to play. */
    var next = 0
        private set
    /** The run tick shown last (it only ever goes forward). */
    var shown = -1
        private set
    /** Live n it started waiting at its next action (-1: not waiting), for the safety net. */
    private var waitingSince = -1
    /** After a leap: where it landed vs where you did, blended out by run tick [blendEnd]. */
    private var offset = P(0.0, 0.0, 0.0)
    private var blendFrom = 0
    private var blendEnd = -1
    /** Its leaps: onto whom, live n. */
    private val leapt = ArrayList<Pair<String, Int>>()
    private var workingOn: String? = null

    /** Every action played and the last frame shown: it stands where you ended. */
    val finished get() = next >= actions.size && shown >= run.end

    /** What it's waiting for, for the debug line. */
    var status = ""
        private set

    /** The jobs it will do (its run's), for the party to leave to it. */
    val jobs: List<String> get() = actions.filter { it.kind == Kind.DONE || it.kind == Kind.GATE }.map { it.job }

    /** It leapt onto [who] at n >= [since]. */
    fun leaptOnto(who: String, since: Int) = leapt.any { it.first == who && it.second >= since }

    /** A leap onto [who]'s early enter into [into] is still to come. */
    fun expectsLeap(who: String, into: Int) = actions.drop(next).any { a ->
        a.kind == Kind.LEAP && a.arg == who && a.anchors.any { it is Anchor.EeOn && it.into == into }
    }

    /** It early-enters into [into] (on its spot at some point). */
    fun earlyEnters(into: Int) = actions.any { it.kind == Kind.EE_ON && it.k == into }

    /** Where it stood for its early enter into [into]. */
    fun eeSpot(into: Int): GhostRun.Frame? = actions.firstOrNull { it.kind == Kind.EE_ON && it.k == into }?.let { run.frame(it.rec) }

    fun tick(n: Int) {
        // Several actions can fall in one tick (a lever pulled and a leap straight after).
        var guard = 0
        while (guard++ < 32) {
            val a = actions.getOrNull(next)
            if (a == null) { show(n - shift); status = if (finished) "finished" else "playing out"; return }
            val at = n - shift
            if (at < a.settle) { show(at); status = "on its way to $a"; return }
            if (waitingSince < 0) waitingSince = n
            // Someone did it already: on at once, as you would.
            if (alreadyDone(a)) { fire(a, n); continue }
            val ready = readyAt(a)
            if (ready == null || n < ready) {
                // The safety net: what it waits for never comes (someone never got to their spot).
                if (ready == null && gaveUp(a, n)) { fire(a, n); continue }
                show(minOf(at, hold(a)))
                status = if (ready == null) "waiting: ${a.anchors.joinToString()}" else "${a.kind} in ${ready - n}"
                return
            }
            if (!fire(a, n)) { show(minOf(at, hold(a))); status = "blocked: $a"; return }
        }
    }

    private fun gaveUp(a: Action, n: Int) = waitingSince >= 0 && n - waitingSince >= MAX_WAIT + a.rec - a.settle

    private fun alreadyDone(a: Action) = when (a.kind) {
        Kind.DONE -> world.stationDone(a.arg)
        Kind.GATE -> world.gateDown(a.k)
        else -> false
    }

    /** The last run tick it may show before [a] happens (a leap's own tick is already where it landed; leaving: off the spot). */
    private fun hold(a: Action) = if (a.kind == Kind.DONE || a.kind == Kind.GATE || a.kind == Kind.EE_ON || a.kind == Kind.RECORE) a.rec else maxOf(a.rec - 1, a.settle)

    /** Live n [a] happens: once everything it waited for has (null: not yet), plus your time after. */
    fun readyAt(a: Action): Int? {
        var g = a.settle + shift
        for (an in a.anchors) {
            val live = when (an) {
                is Anchor.Section -> world.sectionStart(an.s)
                is Anchor.EeOn -> world.eeArrivedAt(an.into, an.who)
                is Anchor.Ready -> world.eeReadyAt(an.into)
                is Anchor.Recore -> world.recoredAt(an.who)
            } ?: return null
            g = maxOf(g, live)
        }
        return g + a.work
    }

    /** Does [a] now; false: it can't yet (its terminal's in use...), try again next tick. */
    private fun fire(a: Action, n: Int): Boolean {
        when (a.kind) {
            Kind.DONE -> if (!world.stationDone(a.arg)) {
                if (world.canDo(a.arg)) body.complete(a.arg)
                else if (!gaveUp(a, n)) return false
                else body.giveUp(a.arg)
            }
            Kind.GATE -> if (!world.gateDown(a.k)) {
                if (world.canGate(a.k)) body.gate(a.k)
                else if (!gaveUp(a, n)) return false
                else body.giveUp(a.job)
            }
            Kind.LEAP -> {
                val to = world.positionOf(a.arg)
                if (to != null) {
                    body.leap(a.arg, to)
                    leapt += a.arg to n
                    // Blend the difference out by the next place you stood still at.
                    offset = to - run.frame(a.rec).p
                    blendFrom = a.rec
                    blendEnd = actions.getOrNull(next + 1)?.settle?.coerceAtLeast(a.rec) ?: a.rec
                }
            }
            Kind.EE_ON -> body.eeOn(a.k)
            Kind.EE_LEAVE -> body.eeLeft(a.k)
            Kind.RECORE -> body.recore()
            Kind.ENTER, Kind.GO -> {}
        }
        waitingSince = -1
        shift = n - a.rec
        next++
        show(a.rec)
        return true
    }

    /** Shows run tick [at] (never going back): moved, held, swung, working. */
    private fun show(at: Int) {
        val t = at.coerceIn(0, run.end)
        if (t > shown) {
            val from = shown
            shown = t
            // Swings since (a few at most when idle time is cut short).
            var swings = 0
            for (e in run.events) if (e.type == "swing" && e.n in (from + 1)..t && swings++ < 3) body.swing()
            val f = run.frame(t)
            body.move(f.p + blend(t), f.yaw, f.pitch, f.held)
        }
        working()
    }

    /** At its next terminal, standing about before doing it: "already using" for anyone else. */
    private fun working() {
        val a = actions.getOrNull(next)
        val id = a?.takeIf { it.kind == Kind.DONE && shown >= it.settle && isTerminal(it.arg) && !world.stationDone(it.arg) }?.arg
        if (id != workingOn) { workingOn = id; body.working(id) }
    }

    /** The landing offset still left at run tick [t] (all of it at the leap, none from the next stand on). */
    fun blend(t: Int): P {
        if (blendEnd < 0 || t < blendFrom || t > blendEnd) return ZERO
        if (blendEnd == blendFrom) return if (t == blendFrom) offset else ZERO
        return offset * ((blendEnd - t).toDouble() / (blendEnd - blendFrom))
    }

    companion object {
        /** Standing about: within this of where an action was done (a terminal's window open, a lever's pull). */
        const val SETTLE = 3.0
        /** Off an early-enter spot and back on within this: never left. */
        const val BACK = 40
        /** Past what it should have waited, it gives up waiting (30 s, as the bots' safety net). */
        const val MAX_WAIT = 600
        private val ZERO = P(0.0, 0.0, 0.0)

        /**
         * Who early-enters into a section with ghosts playing (class names): you, if the plan has you
         * there; else a ghost that did in its run ([ghostsThere], in leap order: the first); else the
         * plan's, unless that's a ghost (one of [ghosts]) whose run didn't.
         */
        fun eeOwner(planned: String?, you: String?, ghostsThere: List<String>, ghosts: Set<String>): String? = when {
            planned != null && planned == you -> you
            ghostsThere.isNotEmpty() -> ghostsThere.first()
            planned == null || planned in ghosts -> null
            else -> planned
        }

        /** The section a station / gate job belongs to. */
        fun sectionOf(job: String) = job.removePrefix("gate ").toIntOrNull() ?: job.substring(1, 2).toInt()

        /** Devices can be done before their section (pre-devving); terminals and levers only in it. */
        fun isDevice(id: String) = id.endsWith(" SS") || id.endsWith(" Lights") || id.endsWith(" Arrows") || id.endsWith(" Target")

        fun isTerminal(id: String) = Regex("S\\d T\\d").matches(id)

        /** The first run tick from which you stood within [SETTLE] of where you were at [at] until then (not before [from]). */
        fun settle(run: GhostRun, from: Int, at: Int): Int {
            val p = run.frame(at).p
            var m = at
            while (m - 1 >= from && m - 1 >= 0 && run.frame(m - 1).p.dist(p) <= SETTLE) m--
            return m
        }

        /** Your run as actions, in order. */
        fun build(run: GhostRun): List<Action> {
            val me = run.clazz
            val ev = run.events.withIndex().sortedWith(compareBy({ it.value.n }, { it.index })).map { it.value }
            fun section(s: Int) = run.sectionN.getOrNull(s)?.takeIf { it >= 0 && s >= 2 }?.let { Anchor.Section(s, it) }
            class Raw(val n: Int, val order: Int, val make: (prev: Int) -> Action)
            val raw = ArrayList<Raw>()
            ev.forEachIndexed { i, e ->
                when (e.type) {
                    "done" -> raw += Raw(e.n, i) { prev ->
                        val anchors = if (isDevice(e.a)) emptyList() else listOfNotNull(section(sectionOf(e.a)))
                        Action(Kind.DONE, e.n, settle(run, prev, e.n), e.a, 0, anchors)
                    }
                    "gate" -> raw += Raw(e.n, i) { prev -> Action(Kind.GATE, e.n, settle(run, prev, e.n), "", e.k, listOfNotNull(section(e.k))) }
                    "leap" -> raw += Raw(e.n, i) { prev ->
                        // Onto an early enterer: not before they were on their spot (the latest of theirs, up to the leap);
                        // onto the core's, in the core: not before it was.
                        val onEe = ev.lastOrNull { it.type == "ee" && it.a == e.a && it.n <= e.n }
                        val recore = ev.lastOrNull { it.type == "recore" && it.a == e.a && it.n <= e.n }
                        val settle = settle(run, prev, (e.n - 1).coerceAtLeast(prev))
                        // Stood there until a section started (not the one you started yourself with your last job).
                        val anchors = listOfNotNull(onEe?.let { Anchor.EeOn(it.k, e.a, it.n) }, recore?.let { Anchor.Recore(e.a, it.n) }, startedDuring(run, settle, e.n))
                        Action(Kind.LEAP, e.n, settle, e.a, 0, anchors)
                    }
                    "recore" -> if (e.a == me) raw += Raw(e.n, i) { _ -> Action(Kind.RECORE, e.n, e.n, "", 0, emptyList()) }
                    // Into the core: once it's open (you stood at its door, or leapt in).
                    "core" -> if (ev.indexOfFirst { it.type == "core" } == i) raw += Raw(e.n, i) { prev ->
                        Action(Kind.ENTER, e.n, settle(run, prev, (e.n - 1).coerceAtLeast(prev)), "", 5, listOfNotNull(section(5)))
                    }
                    "ee" -> if (e.a == me && ev.indexOfFirst { it.type == "ee" && it.a == me && it.k == e.k } == i) {
                        // Only the first time on that spot counts.
                        val into = e.k
                        raw += Raw(e.n, i) { _ -> Action(Kind.EE_ON, e.n, e.n, "", into, emptyList()) }
                        // The time you left for good (not off and straight back on).
                        val j = ev.indices.firstOrNull { x ->
                            val l = ev[x]
                            l.type == "left" && l.a == me && l.k == into && l.n >= e.n && ev.none { it.type == "back" && it.k == into && it.n in l.n..l.n + BACK }
                        } ?: -1
                        if (j >= 0) raw += Raw(ev[j].n, j) { _ ->
                            // Off once everyone was on (the last who landed on you there), and the section
                            // (the core: open) if you stood there until it started.
                            val ready = ev.lastOrNull { it.type == "landed" && it.n in e.n..ev[j].n }
                            val anchors = listOfNotNull(ready?.let { Anchor.Ready(into, it.n) }, startedDuring(run, e.n, ev[j].n))
                            Action(Kind.EE_LEAVE, ev[j].n, e.n, "", into, anchors)
                        }
                    }
                }
            }
            val out = ArrayList<Action>()
            var prev = 0
            for (r in raw.sortedWith(compareBy<Raw> { it.n }.thenBy { it.order })) {
                val a = r.make(prev)
                // Never settled before the action before it (the walk from there is played as it was).
                out += if (a.settle < prev) Action(a.kind, a.rec, prev.coerceAtMost(a.rec), a.arg, a.k, a.anchors) else a
                prev = a.rec
            }
            // Departures between them (a GO goes before an action of its tick: it's the walk to it).
            return (out + departures(run, out)).withIndex()
                .sortedWith(compareBy({ it.value.rec }, { if (it.value.kind == Kind.GO) 0 else 1 }, { it.index })).map { it.value }
        }

        /** Standing still: within this of where you stopped, for at least [MIN_STAND] ticks. */
        const val STAND = 1.5
        const val MIN_STAND = 5

        /** The latest section (2-5) that started after [from] and by [to]: you stood there until it had. */
        private fun startedDuring(run: GhostRun, from: Int, to: Int): Anchor.Section? =
            (2..5).filter { s -> run.sectionN[s] in (from + 1)..to }.maxByOrNull { run.sectionN[it] }?.let { Anchor.Section(it, run.sectionN[it]) }

        /**
         * Where you stood still between actions until a section started and then went (off an early
         * enterer you leapt to, once the section's in): a [Kind.GO] then, waiting for that section.
         */
        fun departures(run: GhostRun, actions: List<Action>): List<Action> {
            val out = ArrayList<Action>()
            var from = 0
            for (i in 0..actions.size) {
                val until = if (i < actions.size) actions[i].settle else run.end
                var m = from
                while (m < until) {
                    val p = run.frame(m).p
                    var e = m
                    while (e + 1 <= until && run.frame(e + 1).p.dist(p) <= STAND) e++
                    if (e - m >= MIN_STAND && e + 1 <= until) startedDuring(run, m, e + 1)?.let { out += Action(Kind.GO, e + 1, m, "", it.s, listOf(it)) }
                    m = e + 1
                }
                if (i < actions.size) from = maxOf(from, actions[i].rec)
            }
            return out
        }
    }
}
