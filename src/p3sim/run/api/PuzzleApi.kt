package com.engineerclient.p3sim.run.api

import com.engineerclient.p3sim.pack.rooms.WorldPos
import com.engineerclient.p3sim.run.PlacedRoom
import com.engineerclient.p3sim.run.PuzzleRecord
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.Run
import com.google.gson.JsonObject
import java.util.UUID

/**
 * One puzzle of the dungeon. Implemented by the puzzles work items (easy/medium in `clear/puzzles`, hard in
 * `clear/puzzles/hard`); the shared frame ([PuzzleHost]) is the puzzles module's. A puzzle keeps its in-world state
 * (blocks, entities, NPC names, chat) EXACTLY as Odin's own solver for that puzzle reads it from Hypixel. Pure state machines
 * live in their own classes with unit tests; this interface is the thin glue to the world. Server thread only.
 */
interface Puzzle {
    /** The pack/Odin code (`BLAZE`, `BOULDER`, `BEAMS`, `WATER_BOARD`, `TP_MAZE`, `ICE_PATH`, `WEIRDOS`, `QUIZ`, `TTT`, `ICE_FILL`). */
    val kind: String
    val context: PuzzleContext
    val status: PuzzleStatus get() = context.record.status

    /** The world is built; set up the puzzle's entities, NPCs and chests. Called once per run (and again after a savepoint restore, then [restore]). */
    fun onRunStart() {}

    /** A party member walked into the room. */
    fun onRoomEntered(player: UUID) {}

    /** Every server tick while the run is in CLEAR or BLOOD and a player is within the room's chunks. */
    fun onTick() {}

    /** A right click on block (x, y, z) in this room; true = consumed (nothing else handles it). */
    fun onInteract(player: UUID, x: Int, y: Int, z: Int): Boolean = false

    /** A chat-driven puzzle (Quiz, Three Weirdos): the player clicked the NPC with this name. True = consumed. */
    fun onNpcClick(player: UUID, npcName: String): Boolean = false

    /** The reset item was used in the room: back to the unsolved start (no failure counted). */
    fun onReset() {}

    /** The puzzle's own state for a savepoint. */
    fun snapshot(): JsonObject? = null
    fun restore(data: JsonObject) {}
}

/** What a puzzle knows about its place. */
class PuzzleContext(val run: Run, val room: PlacedRoom, val record: PuzzleRecord, val host: PuzzleHost) {
    /**
     * The world position of a named anchor of the room's design (FORMAT-rooms 7.9); empty if the design lacks it. The names in the
     * built pack: `chest` (Boulder, Creeper Beams, Teleport Maze, Water Board), `chest1` and `chest2` (Ice Fill), `chest1` to
     * `chest3` (Three Weirdos), `pillar1` to `pillar3` (Quiz). Each name holds one position; the other puzzles have no anchors yet.
     */
    fun anchor(name: String): List<WorldPos> {
        val design = room.design ?: return emptyList()
        val placement = room.placement ?: return emptyList()
        return design.puzzle?.anchors?.get(name)?.map { placement.toWorld(it.x, it.y, it.z) }.orEmpty()
    }
}

/**
 * A puzzle kind that the dungeon can contain. An [AdsModule][com.engineerclient.p3sim.run.AdsModule] lists its types in
 * `puzzleTypes()`; the puzzles module collects them all at run start and creates one [Puzzle] per puzzle room.
 */
class PuzzleType(
    val kind: String,
    /** The name in the tab's `Puzzles (N)` list (`Higher Or Lower`, `Three Weirdos`...). */
    val tabName: String,
    /** Rooms-pack families that carry this puzzle (`Blaze_Lower`, `Blaze_Upper`, `Boulder`...). */
    val families: Set<String>,
    val create: (PuzzleContext) -> Puzzle,
)

/**
 * The shared puzzle frame. Implemented by [com.engineerclient.p3sim.clear.puzzles.PuzzlesModule]: tab rows, the
 * reward-chest finish event, Hypixel's solved/failed chat lines, the reset item, and routing of clicks to the right puzzle.
 */
interface PuzzleHost {
    /** The live puzzle in a room, or null. */
    fun puzzleOf(run: Run, roomIndex: Int): Puzzle? = null

    /** The puzzle is solved: status SOLVED, `PuzzleSolved` posted, Hypixel's "PUZZLE SOLVED!" line, the tab row, the reward chest. */
    fun reportSolved(puzzle: Puzzle, by: UUID?) {}

    /** The puzzle failed: status FAILED, `PuzzleFailed` posted, the "PUZZLE FAIL!" line; [reason] is the line's generic ending. */
    fun reportFailed(puzzle: Puzzle, by: UUID?, reason: String) {}

    /** The reward chest finished opening (the room turns GREEN a moment before `Completed` for most puzzles). */
    fun rewardChestOpened(puzzle: Puzzle, by: UUID?) {}
}
