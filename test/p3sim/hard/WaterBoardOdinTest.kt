package com.engineerclient.p3sim.clear.puzzles.hard

import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Optional gate: Odin's own Water Board table, played against the real Water Board room. Runs only when ADS_REAL_PACKS names the
 * folder of the built packs and ADS_ODIN_PUZZLES the folder with Odin's `water-solutions.json` (Odin is BSD-3 licensed; the table
 * is read from there, never copied into this repository). Every entry of the table (both optimisation levels, the 10 choices of
 * three closed pits) is played with the clicks at the table's times; each must open all three pits.
 */
class WaterBoardOdinTest {
    private val packs: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }
    private val odin: Path? = System.getenv("ADS_ODIN_PUZZLES")?.let { Path.of(it, "water-solutions.json") }?.takeIf { Files.isRegularFile(it) }

    private val leverByName = mapOf(
        "coal_block" to WaterLever.COAL, "gold_block" to WaterLever.GOLD, "quartz_block" to WaterLever.QUARTZ,
        "diamond_block" to WaterLever.DIAMOND, "emerald_block" to WaterLever.EMERALD, "hardened_clay" to WaterLever.CLAY, "water" to WaterLever.WATER,
    )

    @Test fun everyEntryOfOdinsWaterTableSolvesTheRealBoard() {
        assumeTrue(packs != null && odin != null, "ADS_REAL_PACKS / ADS_ODIN_PUZZLES not set")
        val rooms = assertNotNull(RoomPack.load(packs!!, PackReport()))
        val design = assertNotNull(rooms.design("Water_Board"))
        val volume = assertNotNull(rooms.volume(design))
        val table = JsonParser.parseString(Files.readString(odin!!)).asJsonObject
        var played = 0; val failures = ArrayList<String>()
        // The board built in the pack is one of Odin's four layouts (the one whose probe block is quartz): key "3".
        for (optimised in listOf("false", "true")) {
            val byPattern = table.getAsJsonObject(optimised).getAsJsonObject("3")
            for ((slots, levers) in byPattern.entrySet()) {
                val closed = slots.map { it.digitToInt() }.toSet()
                val model = assertNotNull(WaterBoardModel.create(volume, closed))
                val clicks = HashMap<Int, MutableList<WaterLever>>()
                for ((name, times) in levers.asJsonObject.entrySet()) for (t in times.asJsonArray) {
                    clicks.getOrPut(Math.round(t.asDouble * 20).toInt() + 1) { ArrayList() } += leverByName.getValue(name)
                }
                for (n in 1..4000) {
                    clicks[n]?.forEach { model.click(it) }
                    model.tick()
                    if (model.solved) break
                }
                played++
                if (!model.solved) failures += "$optimised/$slots opened ${model.openedPits.sorted()} of ${closed.sorted()}"
            }
        }
        assertEquals(20, played)
        assertTrue(failures.isEmpty(), failures.toString())
    }

    @Test fun aSavepointRestoredMidFlowFinishesTheSameWay() {
        assumeTrue(packs != null && odin != null, "ADS_REAL_PACKS / ADS_ODIN_PUZZLES not set")
        val rooms = assertNotNull(RoomPack.load(packs!!, PackReport()))
        val volume = assertNotNull(rooms.volume(assertNotNull(rooms.design("Water_Board"))))
        val table = JsonParser.parseString(Files.readString(odin!!)).asJsonObject
        val levers = table.getAsJsonObject("false").getAsJsonObject("3").getAsJsonObject("124")
        val closed = setOf(1, 2, 4)
        val clicks = HashMap<Int, MutableList<WaterLever>>()
        for ((name, times) in levers.entrySet()) for (t in times.asJsonArray) clicks.getOrPut(Math.round(t.asDouble * 20).toInt() + 1) { ArrayList() } += leverByName.getValue(name)
        val a = assertNotNull(WaterBoardModel.create(volume, closed))
        var snap: com.google.gson.JsonObject? = null
        var n = 1
        while (n <= 130) { clicks[n]?.forEach { a.click(it) }; a.tick(); n++ }
        snap = a.snapshot()
        val b = assertNotNull(WaterBoardModel.create(volume, closed))
        b.restore(snap)
        for (m in n..2000) {
            clicks[m]?.forEach { a.click(it); b.click(it) }
            val ea = a.tick(); val eb = b.tick()
            assertEquals(ea.map { "${it.x},${it.y},${it.z}=${it.state}" }.toSet(), eb.map { "${it.x},${it.y},${it.z}=${it.state}" }.toSet(), "edits at tick $m")
        }
        assertEquals(a.openedPits, b.openedPits)
        assertEquals(a.solved, b.solved)
        assertTrue(b.solved)
        assertEquals(a.gates.map { it.extended }, b.gates.map { it.extended })
    }
}
