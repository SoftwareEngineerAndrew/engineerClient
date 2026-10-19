package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.google.gson.JsonObject
import java.util.UUID

/*
 * RunState: the single hub that every client surface (sidebar, tab, map, action bar, end screen) and every bot reads, and
 * that every module writes its own slice of. It holds facts; the modules hold the rules. Server thread only.
 *
 * Rules of the hub (docs/ads/ARCHITECTURE.md):
 *  1. One writer per field. The "Written by" lines below name it. Anyone may read anything.
 *  2. A writer changes the field and posts the matching event ([RunBus]) in the same call, so a reader that misses the
 *     event can still poll the field, and a reader that gets the event sees the field already changed.
 *  3. Nothing here talks to Minecraft. Entities and blocks are the modules' business; RunState holds ids and numbers.
 *  4. Everything here is saved and restored by the savepoint ([snapshot]/[restore], filled by the engine).
 */

/** A room's check mark, as the map draws it and Odin names it (`UNDISCOVERED`, `NONE`, `WHITE`, `GREEN`, `RED`). FORMAT-runstate 6.1. */
enum class CheckMark {
    /** Not on the map yet. */
    UNDISCOVERED,
    /** Discovered or entered; nothing required is done. (Odin: NONE, the grey room.) */
    NONE,
    /** The required objective is done (mobs dead / puzzle solved); secrets may remain. Counts as completed. */
    WHITE,
    /** Everything done, all of the room's secrets found. */
    GREEN,
    /** A puzzle failed, or the blood room during the Watcher fight. Does not count as completed. */
    RED;
}

/** One room's live state. Written by the doors/state module (`clear/state`): the only writer of [check]. */
class RoomRecord(val plan: PlacedRoom) {
    val index: Int get() = plan.index

    var check: CheckMark = CheckMark.UNDISCOVERED
    /** Run ticks (`Run.t`) of the first time the room reached each mark, or null. */
    var tDiscovered: Int? = null
    var tWhite: Int? = null
    var tGreen: Int? = null
    var tRed: Int? = null

    /** Secrets found here by the whole team (written by the secrets module). */
    var secretsFound: Int = 0
    val secretsMax: Int get() = plan.secretsMax

    /** Mobs that must die for WHITE (starred mobs, minibosses): spawned and still alive. Written by the mobs module. */
    var mobsRequired: Int = 0
    var mobsRequiredAlive: Int = 0

    /** Players currently inside (by position), written by the doors/state module's room detection. */
    val occupants: MutableSet<UUID> = LinkedHashSet()
    /** Everybody who has ever been inside. */
    val visitors: MutableSet<UUID> = LinkedHashSet()

    /** A free-form bag for a module's per-room facts that nobody else reads (key: "<moduleId>.<name>"). Saved by the owner's own snapshot, not here. */
    val extras: MutableMap<String, Any?> = HashMap()
}

/** `CLOSED`: the door block volume stands. `FALLING`: barrier for 14 ticks. `OPEN`: air. Written by the doors/state module. */
enum class DoorPhase { CLOSED, FALLING, OPEN }

class DoorRecord(val plan: PlacedDoor) {
    val index: Int get() = plan.index
    /** Plain doors start OPEN; every other kind with a doorway starts CLOSED. A `NONE` slot is a wall and stays CLOSED forever. */
    var phase: DoorPhase = if (plan.kind == DoorKind.NORMAL) DoorPhase.OPEN else DoorPhase.CLOSED
    /** Run ticks the door started to fall (the first block became barrier), or null. */
    var openedAt: Int? = null
    var openedBy: UUID? = null
}

/** The party-shared key counters (FORMAT-runstate 4, rule 5). Written by the doors/state module. */
class KeyState {
    /** Wither keys held now (usually 0 or 1). */
    var witherHeld: Int = 0
    var bloodHeld: Boolean = false
    var witherObtained: Int = 0
    var witherUsed: Int = 0
    var bloodObtained: Boolean = false
    var bloodUsed: Boolean = false
}

/**
 * The tile-unit counters (FORMAT-runstate 6.3): `Opened Rooms` and `Completed Rooms`, each the sum of tiles of the rooms in
 * the matching state, the ENTRANCE excluded. Written by the doors/state module.
 */
class TileCounters {
    val total: Int = DungeonFrame.TILE_COUNT
    var opened: Int = 0
    var completed: Int = 0
    /** `Cleared: P%`, `round(100 * completed / 36)`. */
    val clearedPct: Int get() = Math.round(100.0 * completed / total).toInt()
}

/** What the team found. Written by the secrets/score modules. */
class SecretLedger {
    var teamFound: Int = 0
    /** The team total the bars print (sum of the rooms' maxima). */
    var teamMax: Int = 0
    val byPlayer: MutableMap<UUID, Int> = HashMap()
    /** Crypts counted (uncapped; the score uses at most 5). */
    var crypts: Int = 0
    var mimicKilled: Boolean = false
    var princeKilled: Boolean = false
    var batKilled: Boolean = false
    /** Wither essence skulls found. */
    var witherEssence: Int = 0
    val blessings: MutableMap<String, Int> = HashMap()

    /** `Secrets Found: P%`, one decimal as Hypixel prints it. */
    val percent: Double get() = if (teamMax <= 0) 0.0 else Math.round(1000.0 * teamFound / teamMax) / 10.0
}

/** Team deaths. Written by the damage module. */
class DeathLedger {
    var teamDeaths: Int = 0
    val log: MutableList<Pair<Int, UUID>> = ArrayList()
}

/** The team score, recomputed every 200 ticks from T0+200 (FORMAT-runstate 7). Written by the score module. */
class ScoreState {
    var score: Int = 0
    /** `Cleared: P%` as last published with the score. */
    var clearedPct: Int = 0
    var lastUpdateT: Int = Int.MIN_VALUE
    /** `(t, score)` after each recompute. */
    val history: MutableList<Pair<Int, Int>> = ArrayList()
}

/** `UNSOLVED`: not touched or in progress. `SOLVED`, `FAILED` (until reset). */
enum class PuzzleStatus { UNSOLVED, SOLVED, FAILED }

/** One puzzle of the layout. Written by the puzzles module (and hard puzzles/traps for theirs). */
class PuzzleRecord(
    val roomIndex: Int,
    /** The pack/Odin code: `BLAZE BOULDER BEAMS WATER_BOARD TP_MAZE ICE_PATH WEIRDOS QUIZ TTT ICE_FILL`. */
    val kind: String,
    /** The tab row's name (`Higher Or Lower`, `Three Weirdos`...). */
    val tabName: String,
) {
    var status: PuzzleStatus = PuzzleStatus.UNSOLVED
    var revealed: Boolean = false
    var failures: Int = 0
    var solvedBy: UUID? = null
}

/**
 * One party member, player or bot. Membership is the engine's (humans) and the bots module's (bots) -- they add the record in
 * `onRunStart`; hp, ghost, deaths and revive time are the damage module's; secretsFound the secrets module's; roomIndex the
 * doors/state module's; position as noted on the fields.
 */
class PlayerRecord(val uuid: UUID, val name: String, var dungeonClass: String?, val isBot: Boolean) {
    /** Percent of max HP (the ledger, FORMAT-mobs 7): vanilla health is `40 * hpPercent / 100`. */
    var hpPercent: Double = 100.0
    var ghost: Boolean = false
    var deaths: Int = 0
    var secretsFound: Int = 0
    /** The room the member stands in (by position), written by the doors/state module. */
    var roomIndex: Int? = null
    /** Position, refreshed at the start of every tick: by the engine for humans (the engine module runs first) and by the bots module for bots. */
    var x: Double = 0.0
    var y: Double = 0.0
    var z: Double = 0.0
    /** The run tick of the last hit taken (the 10-tick immunity window). */
    var lastHitT: Int = Int.MIN_VALUE
    var reviveAtT: Int? = null
    val extras: MutableMap<String, Any?> = HashMap()
}

/** Run-level clock readings; each written by the module that owns the event. */
class Milestones {
    /** Absolute server ticks. */
    var countdownStartTick: Int? = null
    var t0Tick: Int? = null
    /** Run ticks (`Run.t`). */
    var bloodKeyT: Int? = null
    var bloodOpenT: Int? = null
    var watcherDoneT: Int? = null
    var bossEntryT: Int? = null
    var bossDefeatedT: Int? = null
    var endT: Int? = null
}

/**
 * The hub. Created by the engine from the [DungeonPlan] at run start; every field is mutable and server-thread only.
 * The checkmark helper [setCheck] is the one mutator the skeleton provides, because it is the one change with several
 * derived consequences (timestamps, the matching event); everything else is a plain field its owner updates and announces.
 */
class RunState(val plan: DungeonPlan, private val bus: RunBus, private val clock: () -> Int) {
    val rooms: List<RoomRecord> = plan.rooms.map { RoomRecord(it) }
    val doors: List<DoorRecord> = plan.doors.map { DoorRecord(it) }
    val keys = KeyState()
    val counters = TileCounters()
    val secrets = SecretLedger().also { it.teamMax = plan.secretsTotal }
    val deaths = DeathLedger()
    val score = ScoreState()
    val puzzles: MutableList<PuzzleRecord> = ArrayList()
    val players: MutableMap<UUID, PlayerRecord> = LinkedHashMap()
    val milestones = Milestones()

    fun room(index: Int): RoomRecord? = rooms.getOrNull(index)
    fun door(index: Int): DoorRecord? = doors.getOrNull(index)

    /** The room record a world position stands in, or null in a gap, outside the dungeon, or in an empty tile. */
    fun roomAt(x: Double, z: Double): RoomRecord? = plan.roomAtBlock(x, z)?.let { rooms[it.index] }

    /** The party members that are alive (not ghosts). */
    fun alivePlayers(): List<PlayerRecord> = players.values.filter { !it.ghost }

    /**
     * Moves a room's check mark and posts the matching event: [RoomDiscovered] on the first move off UNDISCOVERED (also
     * for a room that jumps straight to WHITE or GREEN), then [RoomCleared] (WHITE), [RoomGreen] (GREEN) or [RoomFailed]
     * (RED). Records the first tick of each mark. Does nothing when the mark is unchanged. The counters
     * ([TileCounters]) are NOT touched here: the state module recomputes them from the event.
     * Only the doors/state module calls this.
     */
    fun setCheck(roomIndex: Int, to: CheckMark) {
        val r = rooms.getOrNull(roomIndex) ?: return
        if (r.check == to) return
        val t = clock()
        val wasUndiscovered = r.check == CheckMark.UNDISCOVERED
        r.check = to
        if (wasUndiscovered && to != CheckMark.UNDISCOVERED) {
            if (r.tDiscovered == null) r.tDiscovered = t
            bus.post(RoomDiscovered(t, roomIndex))
        }
        when (to) {
            CheckMark.WHITE -> { if (r.tWhite == null) r.tWhite = t; bus.post(RoomCleared(t, roomIndex)) }
            CheckMark.GREEN -> { if (r.tGreen == null) r.tGreen = t; bus.post(RoomGreen(t, roomIndex)) }
            CheckMark.RED -> { if (r.tRed == null) r.tRed = t; bus.post(RoomFailed(t, roomIndex)) }
            else -> {}
        }
    }

    /**
     * Saves the hub to JSON for a savepoint ([RunStateJson.write]): every field of every part below, by name. Absolute server
     * ticks (`milestones.countdownStartTick`, `t0Tick`) are NOT saved: a restore puts the clock back by `Run.restoreClock` instead.
     */
    fun snapshot(): JsonObject = RunStateJson.write(this)

    /**
     * Restores [snapshot] in place: the records already held by modules stay the same objects (their fields are overwritten), a
     * party member that is not in the snapshot is dropped and one that is missing is added. Posts NO events (a restore is not a
     * new run, ARCHITECTURE 7): the modules rebuild what they derive in their own `restore`, and `SavepointRestored` follows.
     */
    fun restore(data: JsonObject) = RunStateJson.read(this, data)
}
