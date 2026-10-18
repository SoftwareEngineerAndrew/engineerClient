package com.engineerclient.p3sim.clear.traps

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FallingFloorTest {
    private val me = UUID(0, 1)

    /** A 4 x 3 floor at y 68, x 10..13, z 8..10. */
    private fun floorCells() = (10..13).flatMap { x -> (8..10).map { z -> intArrayOf(x, 68, z) } }

    @Test fun aFloorLetsGoInOneTickWhenAPlayerStandsOnItAndNeverAgain() {
        val h = RoomHazards(emptyList(), emptyList(), extras = listOf(FallingFloor(floorCells(), emptyList(), FloorTrigger.StandOn)))
        // Walking in beside it does nothing.
        assertTrue(h.tick(listOf(PlayerPos(me, 9.5, 69.0, 9.5))).edits.isEmpty())
        // Jumping 1.5 blocks above it does nothing either.
        assertTrue(h.tick(listOf(PlayerPos(me, 11.5, 70.6, 9.5))).edits.isEmpty())
        val r = h.tick(listOf(PlayerPos(me, 11.5, 69.0, 9.5)))
        assertEquals(12, r.edits.size, "every block of the floor in one tick")
        assertTrue(r.edits.all { it.state == "minecraft:air" && it.y == 68 })
        assertEquals(12, r.debris.size)
        val cue = r.cues.single { it.sound == HazardSound.FLOOR_COLLAPSE }
        assertEquals(listOf(11.5, 68.5, 9.5), listOf(cue.x, cue.y, cue.z), "at the block under the player")
        assertEquals(5.0f, HazardSound.FLOOR_COLLAPSE.volume); assertEquals(0.4920635f, HazardSound.FLOOR_COLLAPSE.pitch)
        for (t in 1..20) { val n = h.tick(listOf(PlayerPos(me, 11.5, 69.0, 9.5))); assertTrue(n.edits.isEmpty() && n.debris.isEmpty() && n.cues.isEmpty()) }
    }

    @Test fun aFloorWaitsForItsStringWhenThatIsItsTrigger() {
        val line = TripLine('z', 81, 15, 0, 8)
        val h = RoomHazards(emptyList(), listOf(line), linesHurt = false, linesClick = true,
            extras = listOf(FallingFloor(floorCells(), listOf(intArrayOf(15, 81, 3), intArrayOf(15, 80, 3)), FloorTrigger.StringTouched(0))))
        // Standing on the floor itself does not drop it.
        assertTrue(h.tick(listOf(PlayerPos(me, 11.5, 69.0, 9.5))).edits.isEmpty())
        // Touching the string does, and takes the string and the block under it too.
        val r = h.tick(listOf(PlayerPos(me, 15.5, 81.0, 4.5)))
        assertEquals(12 + 2, r.edits.size)
        assertTrue(r.edits.any { it.x == 15 && it.y == 81 && it.z == 3 })
        assertEquals(2, r.cues.count { it.sound == HazardSound.LEVER_ON }, "the hooks click as they do on any string of the room")
        val cue = r.cues.single { it.sound == HazardSound.FLOOR_COLLAPSE }
        assertEquals(listOf(13.5, 68.5, 10.5), listOf(cue.x, cue.y, cue.z), "at the far end of the walkway")
    }

    @Test fun aFloorThatHasGoneIsPutBackGoneAfterASavepointRestore() {
        val f = FallingFloor(floorCells(), emptyList(), FloorTrigger.StandOn)
        assertTrue(f.currentEdits().isEmpty())
        val h = RoomHazards(emptyList(), emptyList(), extras = listOf(f))
        h.tick(listOf(PlayerPos(me, 11.5, 69.0, 9.5)))
        val snap = h.snapshot()
        val g = FallingFloor(floorCells(), emptyList(), FloorTrigger.StandOn)
        val h2 = RoomHazards(emptyList(), emptyList(), extras = listOf(g))
        h2.restore(snap)
        assertTrue(g.fired)
        assertEquals(12, h2.currentEdits().size)
        // A room restored from before the collapse still has its floor to lose.
        val before = RoomHazards(emptyList(), emptyList(), extras = listOf(FallingFloor(floorCells(), emptyList(), FloorTrigger.StandOn)))
        val early = before.snapshot()
        val k = FallingFloor(floorCells(), emptyList(), FloorTrigger.StandOn)
        RoomHazards(emptyList(), emptyList(), extras = listOf(k)).restore(early)
        assertTrue(!k.fired)
    }

    @Test fun aClusterIsTheConnectedPartOfTheFallingCells() {
        val a = floorCells()
        val b = (20..22).map { intArrayOf(it, 68, 20) }       // a second, separate floor
        val diag = listOf(intArrayOf(14, 68, 11))             // touches the first floor at a corner
        val all = a + b + diag
        assertEquals(all.size, FallingFloor.cluster(all, null).size, "no cell given: all of them")
        val first = FallingFloor.cluster(all, intArrayOf(11, 68, 9))
        assertEquals(13, first.size)
        assertTrue(first.none { it[0] >= 20 })
        assertEquals(3, FallingFloor.cluster(all, intArrayOf(21, 68, 20)).size)
        assertEquals(3, FallingFloor.cluster(all, intArrayOf(40, 68, 40)).size, "the nearest cell's floor when the cell is not one")
    }

    @Test fun aFallRuleBecomesAFloorAndNeedsItsHook() {
        val lines = listOf(TripLine('z', 81, 7, 0, 8), TripLine('z', 81, 15, 0, 8))
        val rule = FallRule("Old_Trap", "string", intArrayOf(15, 81, 0), intArrayOf(11, 68, 9), listOf(intArrayOf(15, 80, 3, 15, 81, 4)))
        val f = assertNotNull(TrapsModule.fallingFloor(rule, floorCells() + listOf(intArrayOf(30, 68, 30)), lines))
        assertEquals(12, f.cells.size, "the other floor stays")
        assertEquals(4, f.extra.size)
        assertEquals(1, (f.trigger as FloorTrigger.StringTouched).line, "the line whose hook is (15, 81, 0)")
        assertNull(TrapsModule.fallingFloor(FallRule("Old_Trap", "string", intArrayOf(9, 81, 0), null, emptyList()), floorCells(), lines), "no such hook in the room")
        assertNull(TrapsModule.fallingFloor(FallRule("X", "stand", null, null, emptyList()), emptyList(), lines), "no falling cells")
        assertTrue(assertNotNull(TrapsModule.fallingFloor(FallRule("New_Trap", "stand", null, null, emptyList()), floorCells(), emptyList())).trigger is FloorTrigger.StandOn)
    }

    @Test fun theFallRulesOfTheTimingsFileParse() {
        val t = TrapTimings.parse("""{"format":"ads-traps","version":1,"falls":[
            {"design":"New_Trap","trigger":"stand"},
            {"design":"Old_Trap","trigger":"string","hook":[15,81,0],"cluster":[18,80,4],"also":[[15,80,3,15,81,6]]},
            {"design":"Bad","trigger":"string"},{"design":"Bad2","trigger":"jump"}]}""")
        assertEquals(listOf("New_Trap", "Old_Trap"), t.sites.falls.map { it.design }, "a string rule without a hook and an unknown trigger are dropped")
        val old = t.sites.falls[1]
        assertEquals(listOf(15, 81, 0), old.hook!!.toList()); assertEquals(listOf(18, 80, 4), old.cluster!!.toList()); assertEquals(listOf(15, 80, 3, 15, 81, 6), old.also[0].toList())
        assertTrue(TrapTimings.NONE.sites.falls.isEmpty())
    }
}
