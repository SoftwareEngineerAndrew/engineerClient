package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.AdsCommand
import com.engineerclient.p3sim.run.AdsServerContext
import com.engineerclient.p3sim.run.PlacedRoom
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.api.Services

/**
 * `/ads dev hard ...`: the hard puzzles and the trap rooms, for testing in the game.
 *  - `list`: the hard puzzle rooms and trap rooms of the run, with their room number and tile.
 *  - `tp <kind|number>`: stands you in the room (`water`, `maze`, `ice`, `trap`, or a room number from the list).
 *  - `hint <kind|number>`: what the puzzle needs: the closed pits and their drains, the pads to step on, or the slides.
 */
internal object HardDevCommands {
    val hard = AdsCommand(
        "hard", "/ads dev hard <list|tp|hint> [water|maze|ice|trap|room number]",
        "Lists the hard puzzle and trap rooms of this run, takes you to one, or tells you what a puzzle needs.",
        server = { ctx -> run(ctx) },
    )

    private val KINDS = mapOf("water" to "Water_Board", "maze" to "Teleport_Maze", "ice" to "Ice_Path", "trap" to "Trap")

    private fun matches(room: PlacedRoom, family: String): Boolean =
        if (family == "Trap") room.family == "New_Trap" || room.family == "Old_Trap" else room.family == family

    private fun hardRooms(run: Run): List<PlacedRoom> = run.plan.rooms.filter { r -> KINDS.values.any { matches(r, it) } }

    private fun pick(run: Run, what: String?): PlacedRoom? {
        val rooms = hardRooms(run)
        if (what == null) return rooms.firstOrNull()
        what.toIntOrNull()?.let { n -> return rooms.firstOrNull { it.index == n } }
        val fam = KINDS[what.lowercase()] ?: return null
        return rooms.firstOrNull { matches(it, fam) }
    }

    private fun run(ctx: AdsServerContext) {
        val run = ctx.run ?: return ctx.reply("No run is on. Start one with /ads run.")
        when (ctx.arg(0)) {
            "list", null -> {
                val rooms = hardRooms(run)
                if (rooms.isEmpty()) return ctx.reply("This layout has no Water Board, Teleport Maze, Ice Path or trap room.")
                for (r in rooms) ctx.reply("room ${r.index}: ${r.name} (${r.family}) tile ${r.tiles.first().x},${r.tiles.first().z} facing ${r.rotation}")
            }
            "tp" -> {
                val room = pick(run, ctx.arg(1)) ?: return ctx.reply("No such room. Try /ads dev hard list.")
                val pl = room.placement ?: return ctx.reply("That room has no placement (headless plan).")
                val (cx, cy, cz) = standingSpot(room.family)
                val (wx, wz) = Frames.toWorld(pl, cx, cz)
                Sim.tp(ctx.player, wx, cy, wz, Frames.yawToWorld(pl, 0f), 0f)
                ctx.reply("In ${room.name} (room ${room.index}).")
            }
            "hint" -> {
                val room = pick(run, ctx.arg(1)) ?: return ctx.reply("No such room. Try /ads dev hard list.")
                when (val p = Services.puzzles.puzzleOf(run, room.index)) {
                    is WaterBoardPuzzle -> {
                        val m = p.boardModel ?: return ctx.reply("The Water Board could not be read from the room.")
                        ctx.reply("Closed pits (0 purple, 1 orange, 2 blue, 3 green, 4 red): ${m.closedPits.sorted()}, opened: ${m.openedPits.sorted()}, water on: ${m.waterOn}, solved: ${m.solved}.")
                        ctx.reply("Odin's solver lists the lever times; the drains are at x = ${WaterBoardModel.DRAIN_X.joinToString()} (canonical).")
                    }
                    is TpMazePuzzle -> {
                        val m = p.mazeModel ?: return ctx.reply("The maze is not drawn yet.")
                        ctx.reply("Pads to step on, in order (Odin's numbering): ${m.solution().joinToString(" > ")}; route groups ${m.route}.")
                    }
                    is IcePathPuzzle -> {
                        val m = p.iceModel ?: return ctx.reply("The Ice Path could not be read from the room.")
                        ctx.reply("Slides from the start, as the cell the fish stops on (canonical x,z): ${m.solution()?.joinToString(" > ") { "${it.first},${it.second}" } ?: "none"}.")
                    }
                    else -> ctx.reply("Nothing to hint for ${room.name}.")
                }
            }
            else -> ctx.reply("Usage: /ads dev hard <list|tp|hint> [water|maze|ice|trap|room number]")
        }
    }

    /** Where to stand in each kind of room, canonical x, y, z (feet): by the levers, beside the start pad, at the start of the ice, in the middle of a trap room. */
    private fun standingSpot(family: String): Triple<Double, Double, Double> = when (family) {
        "Water_Board" -> Triple(15.5, 59.0, 12.5)
        "Teleport_Maze" -> Triple(15.5, 69.0, 9.5)
        "Ice_Path" -> Triple(12.5, 67.0, 12.5)
        else -> Triple(15.5, 70.0, 15.5)
    }
}
