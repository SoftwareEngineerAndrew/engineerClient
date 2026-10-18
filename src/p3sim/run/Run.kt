package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.PackSet
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * One full run: countdown, clear, blood, the boss entry, the boss, the end. It owns the clock, the stage machine, the
 * [RunState] hub and the [RunBus], and calls every [RunSystem] in module order. One Run exists at a time ([Runs.current]);
 * none exists in today's boss-only practice.
 *
 * The skeleton implements the parts that are pure (stage machine, fan-out to systems and bus, clock arithmetic) so they are
 * unit-tested from day one. The parts that touch the world are marked "engine fills": savepoints, the boss handoff, the
 * server-to-client stage broadcast. THREADING: everything here is server thread only; the client reads [ClientRun].
 *
 * Time: [serverTick] is the absolute server tick (`Fight.serverTick`); [t] is the run clock, server ticks since T0 (the tick
 * of Mort's first line), NEGATIVE during the countdown. Pack durations and recorded ticks are in the `t` frame. Lag
 * (the lag layer) stretches wall time, never `t`: bots and mobs schedule on server ticks.
 */
class Run(
    val id: String,
    val config: RunConfig,
    val plan: DungeonPlan,
    val packs: PackSet,
    val bus: RunBus = RunBus(),
    /** The systems called, in order. Production: [AdsModules.all]. Tests pass fakes. */
    private val systems: List<RunSystem> = AdsModules.all,
) {
    private val log = LoggerFactory.getLogger("engineerclient")

    /** A random source seeded from the run's seed: same seed, same rolls (mobs, builds, HP scale). Modules derive their own from it by [subRandom]. */
    val random: Random = Random(config.seed)

    /** A random source for one purpose (`"mobs"`, `"bots"`...), independent of how often others draw. Same seed + same salt = same sequence. */
    fun subRandom(salt: String): Random = Random(config.seed xor salt.hashCode().toLong() * -0x61c8864680b583ebL)

    var stage: RunStage = RunStage.COUNTDOWN
        private set

    /** Absolute server tick of the current tick (set by [tick]). */
    var serverTick: Int = 0
        private set

    /** Absolute server tick that is T0 (Mort's first line), once known. Null until the countdown plans it. */
    var t0Tick: Int? = null
        private set

    /** Run clock: server ticks since T0 (negative before it). 0 until T0 is known. */
    val t: Int get() = serverTick - (t0Tick ?: serverTick)

    val state: RunState = RunState(plan, bus) { t }

    /** Savepoints of this run. [Runs.start] installs the real store ([RunSavepoints]); a bare Run (the tests) saves nothing. */
    var savepoints: SavepointStore = NoSavepoints

    /** The systems this run calls, in order: the savepoint store walks them to snapshot and restore each one. */
    internal val systemList: List<RunSystem> get() = systems

    private var started = false
    private var ended = false

    init {
        // The fan-out of bus events to the systems, in module order, is one subscriber.
        bus.subscribe(RunBus.SYSTEMS_PRIORITY) { e -> for (s in systems) guard(s, "onEvent") { s.onEvent(this, e) } }
        // A game that joins later (a party joiner) has never heard the stage; the engine posts PlayerJoined for it.
        bus.subscribe(RunBus.SYSTEMS_PRIORITY - 1) { e -> if (e is PlayerJoined && started && !ended) broadcastStage() }
    }

    /**
     * Sets T0 (the doors module does it when the countdown is planned: `serverTick + countdown + Mort delay`). A savepoint restore
     * also uses it to put the clock back: `setT0(serverTick - savepoint.t)`, so `t` reads the savepoint's value again while the
     * absolute server tick keeps counting forward (see [SavepointStore]).
     */
    fun setT0(tick: Int) {
        t0Tick = tick
        state.milestones.t0Tick = tick
    }

    /**
     * Puts the run clock back to [savedT] (a savepoint restore): `t` reads [savedT] again while the absolute server tick keeps going
     * forward. [t0WasKnown] false (the savepoint was taken before the countdown planned T0) makes T0 unknown again.
     */
    internal fun restoreClock(savedT: Int, t0WasKnown: Boolean) {
        if (t0WasKnown) setT0(serverTick - savedT) else { t0Tick = null; state.milestones.t0Tick = null }
    }

    /**
     * Begins the run in the COUNTDOWN stage: calls every system's `onRunStart` then `onStage(null, COUNTDOWN)`, and posts
     * `RunStarted` and `StageChanged`. The engine calls this once the dungeon is built; call it once.
     */
    fun begin(atServerTick: Int) {
        check(!started) { "run already begun" }
        started = true
        packs.pin()
        serverTick = atServerTick
        state.milestones.countdownStartTick = atServerTick
        for (s in systems) guard(s, "onRunStart") { s.onRunStart(this) }
        for (s in systems) guard(s, "onStage") { s.onStage(this, null, RunStage.COUNTDOWN) }
        // Odin's bridge forces "in boss" until a stage message says otherwise, so the very first stage must be announced too.
        broadcastStage()
        bus.post(RunStarted(t))
        bus.post(StageChanged(t, null, RunStage.COUNTDOWN))
    }

    /** One server tick: sets the clock, then `onTick` on every system in module order. The engine calls this from `Fight.tick`. */
    fun tick(atServerTick: Int) {
        if (!started || ended) return
        serverTick = atServerTick
        for (s in systems) guard(s, "onTick") { s.onTick(this) }
    }

    /**
     * Moves to [to] if the stage machine allows it ([RunStage.canGo]); otherwise logs and returns false. Calls `onStage` on
     * every system, posts `StageChanged`, and tells the clients ([broadcastStage]). Any module may request a stage; in
     * practice: the state module (CLEAR when the Mort line is said), the blood module (BLOOD, BOSS_ENTRY, BOSS), the engine (END).
     */
    fun requestStage(to: RunStage): Boolean {
        if (ended) return false
        if (!RunStage.canGo(stage, to)) { log.warn("[ads] refused stage change $stage -> $to"); return false }
        moveTo(to)
        return true
    }

    /** Engine only: sets the stage with no checks (a savepoint restore going back). Calls the same hooks as [requestStage]. */
    fun forceStage(to: RunStage) = moveTo(to)

    private fun moveTo(to: RunStage) {
        val from = stage
        stage = to
        for (s in systems) guard(s, "onStage") { s.onStage(this, from, to) }
        bus.post(StageChanged(t, from, to))
        broadcastStage()
        if (to == RunStage.END && !ended) finish(EndReason.COMPLETED)
    }

    /** Ends the run: stage END (if not there yet), `onRunEnd` on every system, `RunEnded`. Idempotent. */
    fun end(reason: EndReason) {
        if (ended) return
        if (stage != RunStage.END) { val from = stage; stage = RunStage.END; for (s in systems) guard(s, "onStage") { s.onStage(this, from, RunStage.END) }; bus.post(StageChanged(t, from, RunStage.END)); broadcastStage() }
        finish(reason)
    }

    private fun finish(reason: EndReason) {
        if (ended) return
        ended = true
        if (started) packs.unpin()
        state.milestones.endT = state.milestones.endT ?: t
        for (s in systems) guard(s, "onRunEnd") { s.onRunEnd(this, reason) }
        bus.post(RunEnded(t, reason))
    }

    /** True once [end] has run. */
    val isOver: Boolean get() = ended

    /** Takes a savepoint now (shortcut for `savepoints.take`). Engine fills the store. */
    fun savepoint(label: String, roomIndex: Int? = null): Savepoint? = savepoints.take(this, label, roomIndex)

    /**
     * Hands the party to the existing boss sim in one tick: the blood module has done the entry effects (lightning, bar, the
     * party teleported to P1) and calls this. Stage BOSS_ENTRY -> BOSS, then [bossStarter] (the engine's: `Fight.begin(P1Maxor)` with
     * the options, no hotbar re-give, Maxor's first line two ticks after the jump, `Stats.runStart` from T0). The stage message
     * flips Odin's `inBoss` on the clients. Called from an earlier stage it first walks the stages forward (a dev command does).
     */
    fun beginBoss(options: BossEntryOptions = BossEntryOptions()) {
        if (ended || stage == RunStage.BOSS || stage == RunStage.END) { log.warn("[ads] beginBoss ignored in stage $stage"); return }
        while (stage.ordinal < RunStage.BOSS_ENTRY.ordinal) if (!requestStage(RunStage.entries[stage.ordinal + 1])) return
        if (!requestStage(RunStage.BOSS)) return
        val start = bossStarter
        if (start == null) log.warn("[ads] no boss starter is installed; options=$options") else guardBoss { start(this, options) }
    }

    /** What turns the BOSS stage into the boss sim's phase chain. [Runs.start] installs `BossHandoff.start`; null in the tests. */
    var bossStarter: ((Run, BossEntryOptions) -> Unit)? = null

    private inline fun guardBoss(block: () -> Unit) {
        try { block() } catch (t: Throwable) { log.error("[ads] the boss start failed", t) }
    }

    /** Tells every client the stage (so Odin's bridge follows it). Engine fills the sending; see [RunWire]. */
    fun broadcastStage() { RunWire.broadcast(this) }

    private val failures = FailureLog()

    private inline fun guard(s: RunSystem, what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            // A system that throws every tick would write twenty stack traces a second: the first is full, the rest are counted.
            failures.admit("${s.id}.$what")?.let { held -> log.error("[ads] ${s.id}.$what failed${failures.suffix(held)}", t) }
        }
    }
}

/** What the boss handoff may not do. */
data class BossEntryOptions(
    /** P1's start normally gives the boss hotbar; a run that has real gear leaves it alone. */
    val giveHotbar: Boolean = false,
    /** Maxor's first line comes this many ticks after the jump (recorded: jump + 2). */
    val firstLineDelayTicks: Int = 2,
)
