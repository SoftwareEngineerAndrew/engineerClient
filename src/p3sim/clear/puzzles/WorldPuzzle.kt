package com.engineerclient.p3sim.clear.puzzles

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.Station
import com.engineerclient.p3sim.pack.rooms.WorldPos
import com.engineerclient.p3sim.run.RunBlocks
import com.engineerclient.p3sim.run.api.Puzzle
import com.engineerclient.p3sim.run.api.Audience
import com.engineerclient.p3sim.run.api.PuzzleContext
import com.engineerclient.p3sim.run.api.Services
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * The common part of the easy and medium puzzles: a puzzle that is read from, and built into, the room's blocks in the room's own
 * frame (the frame Odin's solvers use: x and z from the room's corner whatever way the room is turned, y absolute). It keeps the
 * thin world glue in one place so each puzzle holds only its own rules, which live in pure classes with unit tests.
 *
 * A world puzzle works in two steps. [activate] (called by [PuzzlesModule] when a player first comes near) reads the room's blocks and
 * works out the puzzle's plan, because only the chunks near a player are loaded; nothing the player could see is made yet. [wake]
 * (called a few ticks after a player first steps into the room, [wakeDelayTicks]) then makes the entities, chests and the first
 * move, as Hypixel's rooms do when they are entered. [onRunStart] therefore sets up only the puzzle's own memory.
 *
 * Timed steps (a chest that opens 9 ticks after a solve, a floor that comes back after 60) go through [after], which counts server
 * ticks and is pumped by the module every tick for every built puzzle, whether or not anyone is in the room.
 */
abstract class WorldPuzzle(final override val kind: String, final override val context: PuzzleContext) : Puzzle {
    /** True once [activate] has built the room's puzzle state. */
    var activated = false
        private set

    private val entities = ArrayList<Entity>()

    /** True once [wake] has run (or the room was already solved when it was built). */
    var awake = false
        private set

    /** The server tick a player was first seen in the room, or -1 while nobody has been. */
    var enteredAt = -1
        private set

    private class Timer(val at: Int, val tag: String, val what: () -> Unit)
    private val timers = ArrayList<Timer>()

    protected val run get() = context.run
    protected val placement get() = context.room.placement
    protected val now: Int get() = run.serverTick

    /** The sentence after the player's name in `PUZZLE SOLVED!`, or null when Hypixel says nothing for this puzzle. */
    open fun solvedPhrase(): String? = null

    /** Whether a failure prints `PUZZLE FAIL!` (the Quiz speaks through its statue instead). */
    open val printsFailLine: Boolean get() = true

    /**
     * Ticks the room turns GREEN before the puzzle counts as Completed. The usual answer is 0: the status flips with the green mark
     * and the 20-tick tab refresh spreads the tab's Completed over the next 0 to 20 ticks (measured: green leads the tab by 0 to 16,
     * median 4). A negative number is a delay the other way (Three Weirdos: the chat line first, green 3 ticks later). A puzzle with a
     * timetable of its own (Higher Or Lower, Tic Tac Toe) overrides [solveTiming] instead.
     */
    open val greenLeadTicks: Int get() = 0

    /**
     * Full control over when the solve's three effects land, in ticks after the call to [solve]: the `PUZZLE SOLVED!` line, the room turning
     * GREEN and the puzzle counting as Completed. Null (the default) derives them from [greenLeadTicks]. Read when the solve happens.
     */
    open fun solveTiming(): SolveTiming? = null

    /** Whether the module sends the reward chest's cherry-log signal when the room turns green. A puzzle that opens its chests on its own schedule says false and sends it itself. */
    open val rewardSignalAtGreen: Boolean get() = true

    /** Whether the module counts the room's one secret (its reward chest) when the room turns green. Higher Or Lower leaves it to the player, who opens the room's real chest. */
    open val countsRewardAsSecret: Boolean get() = true

    /** Whether a teammate alone in the room runs it, as a player there would (the Quiz: its statue speaks to the whole party). */
    open val startsForParty: Boolean get() = false

    /** Whether the room is built at once and ticks with nobody in it (a replay's Ice Fill, filled by a teammate out of your sight). */
    open val ticksUnattended: Boolean get() = false

    /** Ticks after a player first steps into the room until [wake] runs (measured: Ice Fill 0, Higher Or Lower 7, Tic Tac Toe 24). */
    open val wakeDelayTicks: Int get() = 0

    /** Makes what the room shows once it is entered: entities, chests, the room's first move. Called once, [wakeDelayTicks] after the first player steps in; not called for a room that is already solved. */
    protected open fun wake() {}

    /** A secret of this room was found (a chest opened by a player). */
    open fun onSecretFound(x: Int, y: Int, z: Int, by: UUID?) {}

    /** The block positions whose chest-open signal Odin listens for (a cherry log block event at that spot). */
    open fun rewardSpots(): List<WorldPos> = emptyList()

    /** The world as it is once the puzzle is solved (a chest, a gate opened). Called at the solve and again after a savepoint restore. */
    open fun applySolved() {}

    /** Builds the puzzle into the world. Called once, when a player is near; [PuzzlesModule] guards it. */
    protected abstract fun build()

    /** Removes what [build] made in the world that is not a block put back by the region (entities). */
    protected open fun teardown() {}

    /** An arrow landed at the block (x, y, z) of this puzzle's room (world coordinates); [by] shot it. */
    open fun onArrowHit(x: Int, y: Int, z: Int, by: UUID?) {}

    /** A left click on an entity of this puzzle (a blaze). True = consumed. */
    open fun onAttack(player: UUID, entity: Entity): Boolean = false

    override fun onRunStart() {}

    fun activate() {
        if (activated) return
        activated = true
        try {
            build()
            if (status == com.engineerclient.p3sim.run.PuzzleStatus.SOLVED) { awake = true; applySolved() }
        } catch (t: Throwable) {
            org.slf4j.LoggerFactory.getLogger("engineerclient").error("[ads] puzzle ${kind} in room ${context.room.name} failed to build", t)
        }
    }

    /** For the dev command: builds the room and wakes it at once, whatever the distance and the clock. */
    fun wakeNow() {
        activate()
        if (!awake && activated) {
            awake = true
            try { wake() } catch (t: Throwable) { org.slf4j.LoggerFactory.getLogger("engineerclient").error("[ads] puzzle ${kind} in room ${context.room.name} failed to wake", t) }
        }
    }

    /** A player stands in the room at server tick [tick]; the first call starts the wake-up clock. */
    fun noteEntered(tick: Int) { if (enteredAt < 0) enteredAt = tick }

    /** Called by the module every server tick for a built puzzle: wakes the room when its time has come, then runs the timed steps that are due. */
    fun pump(tick: Int) {
        if (!awake && enteredAt >= 0 && tick >= enteredAt + wakeDelayTicks) {
            awake = true
            try { wake() } catch (t: Throwable) { org.slf4j.LoggerFactory.getLogger("engineerclient").error("[ads] puzzle ${kind} in room ${context.room.name} failed to wake", t) }
        }
        if (timers.isEmpty()) return
        val due = timers.filter { it.at <= tick }
        if (due.isEmpty()) return
        timers.removeAll(due.toSet())
        for (t in due.sortedBy { it.at }) {
            try { t.what() } catch (e: Throwable) { org.slf4j.LoggerFactory.getLogger("engineerclient").error("[ads] puzzle ${kind} timed step failed", e) }
        }
    }

    /** Runs [what] [ticks] server ticks from now (at once for 0 or less). [tag] lets [cancelTimers] drop a group of steps. */
    protected fun after(ticks: Int, tag: String = "", what: () -> Unit) {
        if (ticks <= 0) { what(); return }
        timers += Timer(now + ticks, tag, what)
    }

    /** Drops every waiting timed step with this tag. */
    protected fun cancelTimers(tag: String) { timers.removeAll { it.tag == tag } }

    /** Forgets the built state and removes the puzzle's entities (end of run, savepoint restore). The next [activate] builds again. */
    fun deactivate() {
        if (!activated) return
        try { teardown() } catch (t: Throwable) { org.slf4j.LoggerFactory.getLogger("engineerclient").error("[ads] puzzle ${kind} teardown failed", t) }
        entities.forEach { runCatching { it.discard() } }
        entities.clear()
        timers.clear()
        activated = false
        awake = false
        enteredAt = -1
    }

    override fun snapshot(): JsonObject? = null
    override fun restore(data: JsonObject) {}

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The world position of the room-frame cell (x, y, z); null in a test without a room pack. */
    protected fun world(x: Int, y: Int, z: Int): WorldPos? = placement?.toWorld(x, y, z)

    /** The room-frame (x, z) of a world column, or null outside the room's box. */
    protected fun local(wx: Int, wz: Int): Pair<Int, Int>? = placement?.toCanonical(wx, wz)

    protected fun stateAt(x: Int, y: Int, z: Int): BlockState? = world(x, y, z)?.let { RunBlocks.get(it.x, it.y, it.z) }

    /** Whether the room-frame cell is air (an unreadable cell counts as air). */
    protected fun isAirAt(x: Int, y: Int, z: Int): Boolean = stateAt(x, y, z)?.isAir ?: true

    protected fun setAt(x: Int, y: Int, z: Int, state: BlockState): Boolean {
        val p = world(x, y, z) ?: return false
        return RunBlocks.set(p.x, p.y, p.z, state)
    }

    protected fun setWorld(p: WorldPos, state: BlockState): Boolean = RunBlocks.set(p.x, p.y, p.z, state)

    protected fun solve(by: UUID?) = context.host.reportSolved(this, by)
    protected fun fail(by: UUID?, reason: String) = context.host.reportFailed(this, by, reason)

    protected fun nameOf(player: UUID?): String = PuzzlesModule.nameOf(run, player)

    /** A server player by UUID. */
    protected fun playerOf(id: UUID): ServerPlayer? = Sim.players().firstOrNull { it.uuid == id }

    /** The human players standing inside this room's tiles, as server players. */
    protected fun playersInRoom(): List<ServerPlayer> =
        Sim.players().filter { run.state.roomAt(it.x, it.z)?.index == context.room.index }

    protected fun say(text: String) = com.engineerclient.p3sim.run.api.Services.channels.chat(text)

    /** A chat line for one player only (the wrong-step lines of Ice Fill go to the player who stepped wrong). */
    protected fun sayTo(player: UUID, text: String) = Services.channels.chat(text, Audience.Only(player))

    /** The chest-open signal Odin listens for, at a world position, to every player. */
    protected fun lidSignal(p: WorldPos) = PuzzlesModule.sendRewardSignal(BlockPos(p.x, p.y, p.z))

    /** A block state written as in the room packs (`minecraft:chest[facing=north,...]`, in the room's canonical frame), turned for this room's placement. */
    protected fun roomState(s: String): BlockState? = PuzzleBlocks.state(s, placement?.quarterTurns ?: 0)

    /**
     * A stand with a name above it (Hypixel's NPCs and mob tags are stands). [marker] gives it no hitbox; the stand is tagged so
     * the module can tell whose it is and removed with the puzzle.
     */
    protected fun spawnStand(name: String, x: Double, y: Double, z: Double, invisible: Boolean = true, marker: Boolean = true): ArmorStand {
        val s = ArmorStand(EntityTypes.ARMOR_STAND, Sim.level)
        s.isInvisible = invisible; s.setNoGravity(true); s.isPermanentlyInvulnerable = true; s.isSilent = true
        if (marker) Station.setMarker(s)
        s.setCustomName(Sim.legacy(name))
        s.isCustomNameVisible = true
        s.snapTo(x, y, z, 0f, 0f)
        track(s)
        return Sim.spawn(s)
    }

    /** Remembers an entity this puzzle made so [deactivate] removes it, and marks whose it is. */
    protected fun <T : Entity> track(e: T): T {
        e.addTag(TAG)
        e.addTag("$TAG_ROOM${context.room.index}")
        entities += e
        return e
    }

    protected fun forget(e: Entity) { entities.remove(e); runCatching { e.discard() } }

    protected fun center(p: WorldPos) = BlockPos(p.x, p.y, p.z)

    companion object {
        /** On every entity a puzzle makes. */
        const val TAG = "adspuzzle"
        /** Followed by the room index. */
        const val TAG_ROOM = "adspuzzle:room:"
    }
}
