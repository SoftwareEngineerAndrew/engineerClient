package com.engineerclient.p3sim.clear.blood

import kotlin.random.Random

/**
 * Where the blood room's stage is, as a small pure state machine (no Minecraft, tested headless). The blood module feeds it the
 * absolute server tick, whether the camp says it is over, and whether a player's body touches the portal, and it answers with the
 * one thing to do next.
 *
 * ```
 * CLOSED --open--> CAMP --(camp finished, portal due, or the safety timeout)--> PASSED --(portal tick)--> PORTAL --(contact + 1)--> ENTERED
 * ```
 * - In [Phase.CAMP] the camp ticks. The moment it reports it is finished (or [campTimeoutTicks] have gone by, so a broken camp can
 *   never hold the run for ever, or the portal is already due) the Watcher says his pass line: [Step.SAY_PASS].
 * - The clock of the exit does not start at the pass line. It starts at the DEFEAT, the tick the camp reports the fifteenth counted
 *   kill ([noteKills]); a camp that never reports kills (the stand-in, an older plug-in) is taken to have been defeated at its pass
 *   line. From the defeat, [BloodExit] says when the lightning bolt ([Step.STRIKE]), the portal ([Step.OPEN_PORTAL]) and the Watcher's
 *   removal ([Step.REMOVE_WATCHER]) come.
 * - Once the portal is open, the first tick a player touches it starts the entry: [Step.ENTER] comes [BloodExit.ENTRY_LAG] ticks later.
 *
 * Several steps can fall due in the same tick (a savepoint restore, a lag burst): call [step] again until it answers [Step.NONE]; each
 * step is answered once, in the order above. All ticks are absolute server ticks, so lag stretches the waits the way it stretches
 * everything else in the run.
 */
class BloodFlow(
    /** Ticks from the blood door opening after which the camp is ended by force; 0 = never. */
    val campTimeoutTicks: Int,
    /** Rolls the portal delay (a run's seeded stream). */
    private val random: Random = Random(0),
) {
    enum class Phase { CLOSED, CAMP, PASSED, PORTAL, ENTERED }

    /** What the module should do next. */
    enum class Step { NONE, SAY_PASS, STRIKE, OPEN_PORTAL, REMOVE_WATCHER, ENTER }

    var phase: Phase = Phase.CLOSED
        private set

    /** Absolute server tick the door opened, or null. */
    var openedAt: Int? = null
        private set

    /** Absolute server tick of the pass line, or null. */
    var passedAt: Int? = null
        private set

    /** True when the camp was ended by [campTimeoutTicks], not by the camp itself. */
    var timedOut: Boolean = false
        private set

    /** Counted kills so far, 0 to [WatcherStats.KILLS_TO_DEFEAT]. */
    var kills: Int = 0
        private set

    /** The exit's clock, once the defeat is known. */
    var exit: BloodExit? = null
        private set

    /** The first tick a player touched the open portal, or null. */
    var contactAt: Int? = null
        private set

    private var struck = false
    private var watcherGone = false
    private var expedited = false

    /** Whether the portal blocks are in the world now (open and not yet reset). */
    val portalOpen: Boolean get() = phase == Phase.PORTAL || phase == Phase.ENTERED

    /** Whether the Watcher and his Eyes have been taken away. */
    val watcherRemoved: Boolean get() = watcherGone

    fun open(tick: Int) {
        if (phase != Phase.CLOSED) return
        phase = Phase.CAMP
        openedAt = tick
    }

    /** Server ticks since the door opened (0 on the tick it opened); 0 while closed. */
    fun ticksSinceOpen(tick: Int): Int = openedAt?.let { (tick - it).coerceAtLeast(0) } ?: 0

    /**
     * The camp's counted kills are now [total] (repeats and smaller numbers change nothing). The fifteenth is the defeat: it starts
     * the exit's clock at [tick]. Returns true when this call was the defeat.
     */
    fun noteKills(total: Int, tick: Int): Boolean {
        if (phase == Phase.CLOSED || phase == Phase.ENTERED) return false
        kills = maxOf(kills, WatcherStats.clamp(total))
        if (kills >= WatcherStats.KILLS_TO_DEFEAT && exit == null) {
            exit = BloodExit.roll(tick, random)
            return true
        }
        return false
    }

    fun step(tick: Int, campFinished: Boolean, contact: Boolean): Step {
        when (phase) {
            Phase.CLOSED, Phase.ENTERED -> return Step.NONE
            Phase.CAMP -> {
                val late = campTimeoutTicks > 0 && ticksSinceOpen(tick) >= campTimeoutTicks
                val portalDue = exit?.let { tick >= it.strikeAt } ?: false
                if (!campFinished && !late && !portalDue && !expedited) return Step.NONE
                timedOut = !campFinished && late
                phase = Phase.PASSED
                passedAt = tick
                // A camp that never reported its kills was defeated when it ended.
                if (exit == null) { kills = WatcherStats.KILLS_TO_DEFEAT; exit = BloodExit.roll(tick, random) }
                return Step.SAY_PASS
            }
            else -> {}
        }
        val e = exit ?: return Step.NONE
        if (!struck && (expedited || tick >= e.strikeAt)) { struck = true; return Step.STRIKE }
        if (phase == Phase.PASSED) {
            if (expedited || tick >= e.portalAt) { phase = Phase.PORTAL; return Step.OPEN_PORTAL }
            if (!watcherGone && tick >= e.watcherGoneAt) { watcherGone = true; return Step.REMOVE_WATCHER }
            return Step.NONE
        }
        // PORTAL
        if (!watcherGone && (expedited || tick >= e.watcherGoneAt)) { watcherGone = true; return Step.REMOVE_WATCHER }
        if (contact && contactAt == null) contactAt = tick
        val c = contactAt
        if (expedited || (c != null && tick >= c + BloodExit.ENTRY_LAG)) { phase = Phase.ENTERED; return Step.ENTER }
        return Step.NONE
    }

    /** Skips every wait (a dev command): the next [step] calls answer the pass line, the strike, the portal and the entry in turn. */
    fun expedite() {
        if (phase != Phase.CLOSED && phase != Phase.ENTERED) expedited = true
    }

    /** Back to the start (a savepoint restore before the blood door, or a new run). */
    fun reset() {
        phase = Phase.CLOSED; openedAt = null; passedAt = null; timedOut = false
        kills = 0; exit = null; contactAt = null
        struck = false; watcherGone = false; expedited = false
    }

    /** For a savepoint: how far along the camp was, as ticks relative to now, so the timers resume after the restore. */
    fun snapshot(tick: Int): FlowSnapshot = FlowSnapshot(
        phase, openedAt?.let { tick - it }, passedAt?.let { tick - it }, timedOut,
        kills, exit?.let { tick - it.defeatTick }, exit?.portalDelay, contactAt?.let { tick - it }, struck, watcherGone,
    )

    fun restore(s: FlowSnapshot, tick: Int) {
        reset()
        phase = s.phase; openedAt = s.sinceOpen?.let { tick - it }; passedAt = s.sincePass?.let { tick - it }; timedOut = s.timedOut
        kills = WatcherStats.clamp(s.kills)
        val d = s.sinceDefeat; val delay = s.portalDelay
        exit = if (d != null && delay != null) BloodExit(tick - d, delay) else null
        contactAt = s.sinceContact?.let { tick - it }
        struck = s.struck; watcherGone = s.watcherGone
    }

    /** [sinceOpen], [sincePass], [sinceDefeat] and [sinceContact] are ticks AGO as of the savepoint (null when it had not happened). */
    data class FlowSnapshot(
        val phase: Phase,
        val sinceOpen: Int?,
        val sincePass: Int?,
        val timedOut: Boolean,
        val kills: Int = 0,
        val sinceDefeat: Int? = null,
        val portalDelay: Int? = null,
        val sinceContact: Int? = null,
        val struck: Boolean = false,
        val watcherGone: Boolean = false,
    )
}
