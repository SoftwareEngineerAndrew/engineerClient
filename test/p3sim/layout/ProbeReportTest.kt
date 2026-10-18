package com.engineerclient.p3sim.layout

import com.engineerclient.p3sim.clear.layout.ExpectedRoom
import com.engineerclient.p3sim.clear.layout.ProbeReport
import com.engineerclient.p3sim.clear.layout.SeenRoom
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProbeReportTest {
    private val want = listOf(
        ExpectedRoom("Andesite", "SOUTH", setOf(1 to 1)),
        ExpectedRoom("Pirate", "NORTH", setOf(1 to 2, 2 to 2, 2 to 3)),
    )

    private fun seen(name: String?, rot: String?, tiles: Set<Pair<Int, Int>>) = SeenRoom(name, rot, tiles, "NORMAL", "OneByOne")

    @Test fun allRightIsAllOk() {
        val lines = ProbeReport.compare(want, listOf(seen("Andesite", "SOUTH", setOf(1 to 1)), seen("Pirate", "NORTH", setOf(1 to 2, 2 to 2, 2 to 3))))
        assertTrue(ProbeReport.allOk(lines))
    }

    @Test fun aMissingRoomIsSaid() {
        val lines = ProbeReport.compare(want, listOf(seen("Andesite", "SOUTH", setOf(1 to 1))))
        assertFalse(ProbeReport.allOk(lines))
        assertTrue(lines[1].text.contains("Pirate") && lines[1].text.contains("not named"))
    }

    @Test fun aWrongRotationIsSaid() {
        val lines = ProbeReport.compare(want, listOf(seen("Andesite", "WEST", setOf(1 to 1)), seen("Pirate", "NORTH", setOf(1 to 2, 2 to 2, 2 to 3))))
        assertFalse(lines[0].ok)
        assertTrue(lines[0].text.contains("WEST") && lines[0].text.contains("SOUTH"))
        assertTrue(lines[1].ok)
    }

    @Test fun noRotationYetIsNotOk() {
        val lines = ProbeReport.compare(want.take(1), listOf(seen("Andesite", null, setOf(1 to 1))))
        assertFalse(lines[0].ok)
    }

    @Test fun anotherRoomOnTheTileIsNamed() {
        val lines = ProbeReport.compare(want.take(1), listOf(seen("Admin", "SOUTH", setOf(1 to 1))))
        assertTrue(lines[0].text.contains("Admin"))
    }

    @Test fun emptyIsNeverOk() {
        assertFalse(ProbeReport.allOk(emptyList()))
        assertEquals(0, ProbeReport.compare(emptyList(), emptyList()).size)
    }
}
