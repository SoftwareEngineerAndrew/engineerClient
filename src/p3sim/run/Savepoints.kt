package com.engineerclient.p3sim.run

import com.google.gson.JsonObject

/**
 * A point a run can restart from. It is a RunState snapshot plus one entry per module (their [RunSystem.snapshot]), plus the
 * players' positions and gear; restoring it resyncs every [WorldRegion] and re-creates the mobs and entities the modules
 * list. Taken automatically when the party enters a room, and by `/ads run savepoint`. Filled by the engine.
 *
 * `data` layout (the engine's contract with itself; modules only see their own `modules[id]` entry):
 * `{ "state": <RunState.snapshot>, "modules": { "<systemId>": <snapshot>, ... }, "players": [ ... ], "serverTick": n }`.
 *
 * What a restore does, in this order (the contract every module relies on; ARCHITECTURE.md 7): (1) `forceStage` back to [stage];
 * (2) the clock is put back with `run.setT0(run.serverTick - t)`; (3) `RunState.restore`; (4) every [WorldRegion] is resynced,
 * which puts its blocks back to the START state as generated (doors closed, chests unopened, walls whole); (5) every system's
 * `restore` runs in module order, and each module re-applies whatever it changed on top of that start state (the doors module
 * the open doors, the secrets module the opened chests and broken walls, the puzzles their blocks) and re-creates its own entities;
 * (6) `SavepointRestored` is posted. Absolute server ticks are NOT restored; a module that scheduled something for an absolute
 * tick saves the delay instead and re-schedules it in `restore`. The engine takes a savepoint labelled "Start" at the end of
 * `Run.begin`, so `/ads run restart` always has one.
 */
class Savepoint(
    val id: String,
    /** Shown in the run UI ("Room: Dino Site", "Start"). */
    val label: String,
    /** Run clock (`Run.t`) when it was taken. */
    val t: Int,
    val stage: RunStage,
    /** The room the party was entering, or null for a manual or start savepoint. */
    val roomIndex: Int?,
    val data: JsonObject,
)

/** Takes and restores savepoints. The engine assigns the real one to [Run.savepoints]; until then nothing is saved. */
interface SavepointStore {
    /** Takes a savepoint now; null if saving is not available. Posts `SavepointTaken`. */
    fun take(run: Run, label: String, roomIndex: Int? = null): Savepoint? = null

    /** All savepoints of this run, oldest first. */
    fun list(): List<Savepoint> = emptyList()

    /** Restores one: stage, RunState, every module, regions resynced, players placed. Posts `SavepointRestored` last. */
    fun restore(run: Run, id: String): Boolean = false

    /** The most recent savepoint taken on entering [roomIndex], or null. */
    fun forRoom(roomIndex: Int): Savepoint? = list().lastOrNull { it.roomIndex == roomIndex }
}

/** The store before the engine installs one: saves nothing. */
object NoSavepoints : SavepointStore
