package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import com.engineerclient.p3sim.pack.Fixtures
import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.run.PlacedRoom
import com.engineerclient.p3sim.run.RoomType
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraversalsTest {
    private val json = """
    {"format":"ads-bot-traversals","version":1,"rooms":{"Invented_Room":[
      {"ticks":100,"pts":[[0,5,5],[25,5,20],[50,20,20],[75,20,5],[100,5,5]]},
      {"ticks":10,"pts":[[0,0,0]]}]}}
    """.trimIndent()

    private fun room(rot: Rot = Rot.SOUTH): PlacedRoom {
        val placement = Placement.of("1x1", 31, 31, rot, listOf(Tile(2, 2)))
        return PlacedRoom(7, "Invented Room", "Invented_Room", "Invented_Room", null, RoomType.NORMAL, "1x1", listOf(Tile(2, 2)), rot, placement, 3, null, null, null, null, null)
    }

    @Test fun readsTheFileAndSkipsWhatIsBroken() {
        val lib = assertNotNull(TraversalLibrary.fromJson(Fixtures.json(json)))
        assertEquals(1, lib.designs); assertEquals(1, lib.walks, "the one-point walk is skipped")
        assertTrue(lib.has("Invented_Room"))
        val r = PackReport()
        assertNull(TraversalLibrary.fromJson(Fixtures.json("""{"format":"other","version":1}"""), r)); assertTrue(r.warnings().isNotEmpty())
        assertNull(TraversalLibrary.fromJson(Fixtures.json("""{"format":"ads-bot-traversals","version":9}"""), r))
        assertNull(TraversalLibrary.load(java.nio.file.Path.of("/nowhere-at-all")))
    }

    @Test fun playsTheWalkInTheWorldAndFillsTheBudget() {
        val lib = TraversalLibrary.fromJson(Fixtures.json(json))!!
        val r = room()
        val from = Pt(r.placement!!.originX + 5.5, r.placement!!.originZ + 5.5)
        val (legs, end) = assertNotNull(lib.legs(r, from, 1000, 1200, 0.85, Random(1)))
        assertEquals(1000, legs.first().t0); assertEquals(1200, legs.last().t1)
        for ((a, b) in legs.zipWithNext()) { assertEquals(a.t1, b.t0); assertTrue(a.b.dist(b.a) < 1e-9) }
        // The walk goes through the far corner of the room (canonical 20, 20) in the world.
        val far = r.placement!!.toWorld(20, 0, 20).let { Pt(it.x + 0.5, it.z + 0.5) }
        assertTrue(legs.any { it.b.dist(far) < 1.0 })
        assertEquals(legs.last().b, end)
    }

    @Test fun aRotatedPlacementTurnsTheWalk() {
        val lib = TraversalLibrary.fromJson(Fixtures.json(json))!!
        val south = lib.legs(room(Rot.SOUTH), Pt(0.0, 0.0), 0, 400, 5.0, Random(1))!!.first
        val west = lib.legs(room(Rot.WEST), Pt(0.0, 0.0), 0, 400, 5.0, Random(1))!!.first
        assertTrue(south.map { it.b } != west.map { it.b })
    }

    @Test fun aBudgetTooShortForTheWalkGivesNothing() {
        val lib = TraversalLibrary.fromJson(Fixtures.json(json))!!
        assertNull(lib.legs(room(), Pt(0.0, 0.0), 0, 20, 0.85, Random(1)), "the planner then uses the plain route")
    }

    @Test fun thePlannerUsesItForARoomItKnows() {
        val lib = TraversalLibrary.fromJson(Fixtures.json(json))!!
        var asked = 0
        val spy = RoomTraversals { r, f, a, b, bpt, rnd -> asked++; lib.legs(r, f, a, b, bpt, rnd) }
        val plan = ClearPlanner(PlannerInput(BotFixtures.dungeon(), BotFixtures.tables(), ClearSim.seatsFor(ClearSim.FIVE), Random(2), traversals = spy)).build()
        assertTrue(asked > 0, "asked for the rooms' walks")
        assertTrue(plan.rooms.isNotEmpty())
    }
}
