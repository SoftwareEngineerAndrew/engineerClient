package com.engineerclient.p3sim.puzzles

import com.engineerclient.p3sim.clear.puzzles.BeamsLogic
import com.engineerclient.p3sim.clear.puzzles.BlazeLogic
import com.engineerclient.p3sim.clear.puzzles.BlazeLayout
import com.engineerclient.p3sim.clear.puzzles.BlazeRail
import com.engineerclient.p3sim.clear.puzzles.IceFillPuzzle
import com.engineerclient.p3sim.clear.puzzles.BoulderLogic
import com.engineerclient.p3sim.clear.puzzles.IceFillLogic
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.rooms.RoomDesign
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Optional check of the puzzle logic against the REAL room pack (ADS_REAL_PACKS, as `RealPackTest`): for every build and variant of
 * each easy and medium puzzle room it asks the questions Odin's solvers ask of the room, and reports which volumes Odin can solve.
 * Prints counts and design ids only. ADS_REAL_REPORT (a file path) receives the same lines when set.
 */
class PuzzleRealPackTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
    private val out = StringBuilder()
    private fun say(s: String) { out.appendLine(s); println(s) }

    private fun volumes(rooms: com.engineerclient.p3sim.pack.rooms.RoomPack, d: RoomDesign): List<Pair<String, RoomVolume>> =
        listOfNotNull(rooms.volume(d)?.let { "base" to it }) + d.variants.mapNotNull { v -> rooms.variantVolume(d, v)?.let { v.id to it } }

    @Test fun odinCanSolveTheRoomsThePackBuilds() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = Packs.load(dir!!)
        val rooms = set.rooms!!
        try {
            // Boulder
            rooms.designsOfFamily("Boulder").forEach { d ->
                val vs = volumes(rooms, d)
                val ok = vs.filter { (_, v) -> BoulderLogic.solutionFor(BoulderLogic.key { x, z -> v.isAir(x, 66, z) }) != null }
                say("Boulder ${d.id}: ${ok.size} of ${vs.size} volumes have a pattern in Odin's table: ${ok.map { it.first }}; base ok=${ok.any { it.first == "base" }}")
            }
            // Ice Fill
            rooms.designsOfFamily("Ice_Fill").forEach { d ->
                val vs = volumes(rooms, d)
                val ok = vs.filter { (_, v) -> IceFillLogic.patterns { x, y, z -> v.isAir(x, y, z) }.all { it >= 0 } }
                say("Ice Fill ${d.id}: ${ok.size} of ${vs.size} volumes match Odin's patterns on all three floors: ${ok.map { it.first }}")
                for ((id, v) in vs.take(3)) {
                    val sizes = (0..2).map { f -> IceFillLogic.scan(f, isIce = { x, y, z -> v.stateAt(x, y, z).startsWith("minecraft:ice") && !v.stateAt(x, y, z).startsWith("minecraft:iron") }, isAir = { x, y, z -> v.isAir(x, y, z) }).size }
                    say("  $id: patch sizes per floor $sizes")
                }
            }
            // Creeper Beams
            rooms.designsOfFamily("Creeper_Beams").forEach { d ->
                val vs = volumes(rooms, d)
                say("Creeper Beams ${d.id}: pairs per volume " + vs.map { (id, v) -> id to BeamsLogic.pairsIn { c -> v.stateAt(c.x, c.y, c.z).startsWith("minecraft:sea_lantern") }.size })
                val chest = d.puzzle?.anchors?.get("chest")?.firstOrNull()
                if (chest != null) assertTrue(volumes(rooms, d).first().second.isAir(chest.x, chest.y, chest.z), "the chest spot is air at the start")
            }
            // Weirdos, Quiz anchors
            rooms.designsOfFamily("Three_Weirdos").forEach { d ->
                val v = rooms.volume(d)!!
                val ok = (1..3).all { i -> d.puzzle?.anchors?.get("chest$i")?.firstOrNull()?.let { a -> v.isAir(a.x, a.y, a.z) && !v.isAir(a.x, a.y - 1, a.z) && v.isAir(a.x - 1, a.y, a.z) } == true }
                say("Three Weirdos ${d.id}: chests have air on top, a floor and air to the west for the NPC: $ok")
            }
            rooms.designsOfFamily("Quiz").forEach { d ->
                val v = rooms.volume(d)!!
                val buttons = (1..3).map { i -> d.puzzle!!.anchors["pillar$i"]!![0].let { a -> listOf(a.x to a.z + 1, a.x to a.z - 1, a.x + 1 to a.z, a.x - 1 to a.z).count { (x, z) -> v.stateAt(x, a.y, z).contains("button") } } }
                say("Quiz ${d.id}: stone buttons around the three pillars: $buttons")
            }
            // Blaze: the spiral cells are free air, the rail column is air or bars (never a wall), the room's own chest cell is the pack's one secret.
            for (family in listOf("Blaze_Lower", "Blaze_Upper")) rooms.designsOfFamily(family).forEach { d ->
                val higher = family == "Blaze_Upper"
                val rail = BlazeRail(higher)
                volumes(rooms, d).forEach { (id, v) ->
                    val blocked = (0 until BlazeLogic.COUNT).map { BlazeLayout.cell(it, higher) }.filter { c -> !(0..1).all { dy -> v.isAir(c.x, c.y + dy, c.z) } }
                    val wall = rail.cells.filter { y -> val st = v.stateAt(BlazeLayout.CENTRE, y, BlazeLayout.CENTRE); !(v.isAir(BlazeLayout.CENTRE, y, BlazeLayout.CENTRE) || st.contains("iron_bars") || st.contains("chest")) }
                    say("$family ${d.id}/$id: ${blocked.size} of 10 blaze cells are not free air; ${wall.size} of ${rail.cells.count()} rail cells hold something else than air, bars or a chest")
                    assertTrue(blocked.isEmpty(), "$family ${d.id}/$id: blaze cells in the way: ${blocked.map { Triple(it.x, it.y, it.z) }}")
                    assertTrue(wall.isEmpty(), "$family ${d.id}/$id: the rail runs through $wall")
                }
                val chests = d.secrets.filter { it.type == "chest" }
                say("$family ${d.id}: ${chests.size} chest secret(s) at ${chests.map { Triple(it.pos.x, it.pos.y, it.pos.z) }}")
                assertTrue(chests.size == 1, "$family ${d.id}: the room has exactly one chest secret")
            }
            // Tic Tac Toe: the board wall, the cell of the reward chest and the free sides of the wall.
            rooms.designsOfFamily("Tic_Tac_Toe").forEach { d ->
                for ((id, v) in volumes(rooms, d)) {
                    val cells = (0..8).count { c -> v.stateAt(8, 72 - c / 3, 15 + c % 3).contains("button") }
                    val wall = (15..17).flatMap { z -> (69..72).map { y -> v.stateAt(7, y, z) } }
                    val chestFree = v.isAir(2, 70, 16)
                    say("Tic Tac Toe ${d.id}/$id: $cells of 9 board cells hold a button; wall of ${wall.size} cells, ${wall.count { it.contains("iron_block") }} iron blocks; reward chest cell free: $chestFree")
                    assertTrue(chestFree, "Tic Tac Toe ${d.id}/$id: the reward chest cell (2, 70, 16) is not air")
                    assertTrue(wall.none { it.contains("air") }, "Tic Tac Toe ${d.id}/$id: the board wall has a hole")
                }
            }
            // Ice Fill gate: 30 bars the opening turns to barrier, the chest anchors behind them are free.
            rooms.designsOfFamily("Ice_Fill").forEach { d ->
                val v = rooms.volume(d)!!
                val bars = IceFillPuzzle.GATE_Y.sumOf { y -> IceFillPuzzle.GATE_X.count { x -> v.stateAt(x, y, IceFillPuzzle.GATE_Z).contains("iron_bars") } }
                val free = listOf("chest1", "chest2").map { n -> d.puzzle?.anchors?.get(n)?.firstOrNull()?.let { a -> v.isAir(a.x, a.y, a.z) } }
                say("Ice Fill ${d.id}: $bars of 30 gate cells are bars; chest anchors free: $free")
                assertTrue(bars == 30 && free.all { it == true }, "Ice Fill ${d.id}: gate or chest anchors differ from the recorded room")
            }
        } finally {
            System.getenv("ADS_REAL_REPORT")?.let { Files.writeString(Path.of(it), out.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND) }
            set.close()
        }
    }
}
