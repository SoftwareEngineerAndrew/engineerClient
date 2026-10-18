package com.engineerclient.p3sim.puzzles

import com.engineerclient.p3sim.clear.puzzles.BlazeLayout
import com.engineerclient.p3sim.clear.puzzles.BlazeLogic
import com.engineerclient.p3sim.clear.puzzles.BlazeRail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlazeLogicTest {
    @Test fun blazesDieInOrderOfHealth() {
        val up = BlazeLogic(listOf(3000, 1000, 2000), ascending = true)
        assertEquals(1000, up.next)
        assertTrue(up.kill(1000)); assertTrue(up.kill(2000)); assertFalse(up.solved); assertTrue(up.kill(3000))
        assertTrue(up.solved)
        val down = BlazeLogic(listOf(3000, 1000, 2000), ascending = false)
        assertEquals(3000, down.next)
    }

    @Test fun aWrongKillFails() {
        val l = BlazeLogic(listOf(1000, 2000), ascending = true)
        assertFalse(l.kill(2000))
        assertTrue(l.failed)
        assertFalse(l.solved)
        l.reset(); assertFalse(l.failed); assertEquals(2, l.remaining)
    }

    /** The regex of Odin's `BlazeSolver` (OdinFabric 0.3.4), glyph included: U+E07C is the blaze icon the name carries. */
    private val odinBlazeRegex = Regex("^\\[Lv+\\d+] \uE07C Blaze [\\d,]+/([\\d,]+)\u2764$")

    @Test fun blazeNamesAreWhatOdinReads() {
        val name = BlazeLogic.nameOf(3261)
        val plain = name.replace(Regex("§."), "")
        assertEquals("[Lv11] \uE07C Blaze 3,261/3,261\u2764", plain)
        assertEquals("3,261", odinBlazeRegex.find(plain)!!.groups[1]!!.value)
        assertEquals("§8[§7Lv11§8] §4\uE07C §cBlaze §a3,261§f/§a3,261§c\u2764", name)
        assertEquals("1", odinBlazeRegex.find(BlazeLogic.nameOf(1).replace(Regex("§."), ""))!!.groups[1]!!.value)
    }

    @Test fun theRegexHereIsTheOneInOdin() {
        val class_ = Path.of("libs/odin/com/odtheking/odin/features/impl/dungeon/puzzlesolvers/BlazeSolver.class")
        assumeTrue(Files.exists(class_), "Odin is not unpacked in libs/odin")
        val bytes = String(Files.readAllBytes(class_), Charsets.UTF_8)
        // The class file keeps the pattern text as a UTF-8 constant.
        assertTrue(bytes.contains("^\\[Lv+\\d+] \uE07C Blaze [\\d,]+/([\\d,]+)\u2764$"), "Odin's blaze health regex changed")
    }

    @Test fun blazeHealthLooksLikeTheRecordedSets() {
        val rnd = Random(4)
        val spans = ArrayList<Int>(); val lows = ArrayList<Int>(); var roundOnes = 0; var n = 0
        repeat(2000) {
            val hps = BlazeLogic.drawHps(rnd)
            assertEquals(10, hps.size)
            assertEquals(10, hps.toSet().size, "all different")
            val sorted = hps.sorted()
            lows += sorted.first(); spans += sorted.last() - sorted.first()
            roundOnes += hps.count { it % 100 == 0 }; n += hps.size
        }
        assertTrue(lows.min() >= BlazeLogic.LOWEST_MIN && lows.max() <= BlazeLogic.LOWEST_MAX, "lowest ${lows.min()} to ${lows.max()}")
        // Recorded: spans 1,450 to 3,222, mean 2,022; the lowest 1,002 to 1,993.
        assertTrue(spans.average() in 1800.0..2300.0, "mean span ${spans.average()}")
        assertTrue(spans.max() < 4100)
        assertTrue(roundOnes < n / 50, "round numbers are rare ($roundOnes of $n)")
    }

    @Test fun theBlazesHangInASpiralAroundThePillar() {
        // Recorded: blaze k at y0 + 4k, around the pillar at (15, 15), 2 blocks out, a quarter turn per blaze.
        val lower = (0..9).map { BlazeLayout.cell(it, higher = false) }
        assertEquals(listOf(27, 31, 35, 39, 43, 47, 51, 55, 59, 63), lower.map { it.y })
        assertEquals(listOf(15 to 13, 13 to 15, 15 to 17, 17 to 15, 15 to 13, 13 to 15, 15 to 17, 17 to 15, 15 to 13, 13 to 15), lower.map { it.x to it.z })
        val higher = (0..9).map { BlazeLayout.cell(it, higher = true) }
        assertEquals(77, higher.first().y); assertEquals(113, higher.last().y)
        assertTrue(lower.all { Math.abs(it.x - 15) + Math.abs(it.z - 15) == 2 })
    }

    @Test fun theChestRailRunsDownForLowerAndUpForHigher() {
        val lower = BlazeRail(false); val higher = BlazeRail(true)
        assertEquals(69, lower.startY); assertEquals(20, lower.endY)
        assertEquals(70, higher.startY); assertEquals(119, higher.endY)
        assertEquals(50, lower.cells.count()); assertEquals(50, higher.cells.count())
        assertEquals(69 - 10, lower.yAt(10)); assertEquals(70 + 10, higher.yAt(10))
        // The first move is on the tick of the first kill; the 49th is 240 ticks later; the room turns 4 ticks after that.
        assertEquals(1000, lower.moveTick(1000, 0)); assertEquals(1240, lower.endTick(1000)); assertEquals(1244, lower.greenTick(1000))
    }

    @Test fun lowerLeavesBarsBehindAndHigherEatsTheBarsAbove() {
        val lower = BlazeRail(false)
        assertEquals(BlazeRail.Cell.CHEST, lower.cellAt(0, 69))
        assertEquals(BlazeRail.Cell.AIR, lower.cellAt(0, 68), "nothing below the chest yet")
        assertEquals(BlazeRail.Cell.CHEST, lower.cellAt(3, 66))
        assertEquals(BlazeRail.Cell.BARS, lower.cellAt(3, 67), "a bar where it has been")
        assertEquals(BlazeRail.Cell.BARS, lower.cellAt(3, 69))
        assertEquals(BlazeRail.Cell.AIR, lower.cellAt(3, 65))
        val higher = BlazeRail(true)
        assertEquals(BlazeRail.Cell.CHEST, higher.cellAt(0, 70))
        assertEquals(BlazeRail.Cell.BARS, higher.cellAt(0, 71), "the column the chest will eat")
        assertEquals(BlazeRail.Cell.BARS, higher.cellAt(0, 119))
        assertEquals(BlazeRail.Cell.CHEST, higher.cellAt(3, 73))
        assertEquals(BlazeRail.Cell.AIR, higher.cellAt(3, 72), "air where it has been")
        assertEquals(BlazeRail.Cell.BARS, higher.cellAt(3, 74))
        // At the end the chest stands on the last cell and the rest of the rail is behind it.
        assertEquals(BlazeRail.Cell.CHEST, higher.cellAt(BlazeRail.STEPS, 119)); assertEquals(BlazeRail.Cell.AIR, higher.cellAt(BlazeRail.STEPS, 118))
        assertEquals(BlazeRail.Cell.CHEST, lower.cellAt(BlazeRail.STEPS, 20)); assertEquals(BlazeRail.Cell.BARS, lower.cellAt(BlazeRail.STEPS, 21))
    }
}
