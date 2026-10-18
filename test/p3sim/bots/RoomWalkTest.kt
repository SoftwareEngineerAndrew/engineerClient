package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.*
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wander against the recorded movement of teammates in the clear, and against a floor with walls in it. */
class RoomWalkTest {
    /** A room's floor: 25 blocks square. */
    private val arena = FreeArea(listOf(Rect(0.0, 0.0, 25.0, 25.0)))

    /** The net speed over every one-second window of many room visits, blocks a tick. */
    private fun windows(visits: Int, ticks: Int, area: WalkArea = arena): List<Double> {
        val out = ArrayList<Double>()
        for (seed in 1..visits) {
            val random = Random(seed)
            val (legs, _) = RoomWalk.legs(0, ticks, Pt(12.0, 12.0), emptyList(), 1.2, area, random)
            val track = SeatTrack(0, legs, emptyList())
            var t = 0
            while (t + 20 <= ticks) { out += track.positionAt(t).dist(track.positionAt(t + 20)) / 20.0; t += 20 }
        }
        return out
    }

    @Test fun theMovementHasTheRecordedShape() {
        val w = windows(150, 600)
        val still = w.count { it < 0.02 }.toDouble() / w.size
        val slow = w.count { it in 0.1..0.6 }.toDouble() / w.size
        val fast = w.count { it >= 1.0 }.toDouble() / w.size
        val median = w.sorted()[w.size / 2]
        // Recorded over 27,700 one-second windows of the whole clear: 25.5% still, 36.7% slow, 11.7% at 1.0 or more (the dashes between rooms are
        // most of those: a wander inside a room does not run twenty blocks in a second), median 0.19.
        assertTrue(still in 0.15..0.38, "still windows $still (recorded 0.255)")
        assertTrue(slow in 0.3..0.65, "slow windows $slow (recorded 0.367)")
        assertTrue(fast < 0.05, "fast windows $fast")
        assertTrue(median in 0.06..0.3, "median window speed $median (recorded 0.19)")
    }

    @Test fun theSpeedOfAStretchIsLogNormalAboutTheRecordedMedian() {
        val r = Random(5)
        val v = List(40_000) { RoomWalk.stretchSpeed(r, 1.2) }.sorted()
        assertEquals(0.38, v[v.size / 2], 0.03)
        assertEquals(1.3, v[(v.size * 0.9).toInt()], 0.25)
        val slowClass = List(20_000) { RoomWalk.stretchSpeed(r, 1.05) }.sorted()
        assertTrue(slowClass[slowClass.size / 2] < v[v.size / 2], "a slower class is slower")
    }

    @Test fun aBotKeepsToTheFreeAreaItWasGiven() {
        val box = FreeArea(listOf(Rect(0.0, 0.0, 20.0, 20.0)))
        for (seed in 1..30) {
            val (legs, _) = RoomWalk.legs(0, 800, Pt(10.0, 10.0), emptyList(), 1.2, box, Random(seed))
            for (l in legs) for (p in listOf(l.a, l.b)) assertTrue(p.x in -0.001..20.001 && p.z in -0.001..20.001, "left the box: $p")
        }
    }

    @Test fun onAFloorWithAWallTheBotGoesRoundIt() {
        // 40 x 20 floor, a wall across it at x = 20 from z = 0 to 15, so the way from west to east is by z 15..19.
        val grid = gridOf(40, 20) { x, z -> !(x == 20 && z < 15) }
        val area = GridArea(grid)
        for (seed in 1..40) {
            val (legs, _) = RoomWalk.legs(0, 900, Pt(5.5, 5.5), listOf(Pt(35.5, 5.5)), 1.2, area, Random(seed))
            for (l in legs) {
                assertTrue(grid.lineClear(l.a, l.b) || l.a == l.b || l.a.dist(l.b) < 0.5, "leg ${l.a} to ${l.b} goes through the wall (seed $seed)")
            }
        }
    }

    @Test fun aRouteThroughARoomGoesRoundTheWallAndKeepsItsDoors() {
        val grid = gridOf(40, 20) { x, z -> !(x == 20 && z < 15) }
        val area = GridArea(grid)
        val route = listOf(RoutePoint(Pt(5.5, 5.5)), RoutePoint(Pt(35.5, 5.5), gate = 7), RoutePoint(Pt(36.5, 5.5)))
        val out = RouteExpander.expand(route) { area }
        assertEquals(Pt(5.5, 5.5), out.first().pt); assertEquals(Pt(36.5, 5.5), out.last().pt)
        assertEquals(7, out.single { it.gate != null }.gate, "the door stays a door")
        assertEquals(Pt(35.5, 5.5), out.single { it.gate != null }.pt)
        assertTrue(out.size >= 4, "a corner was added: ${out.size} points")
        for ((a, b) in out.zipWithNext()) if (a.pt.dist(b.pt) > RouteExpander.MIN_STRETCH) assertTrue(grid.lineClear(a.pt, b.pt), "${a.pt} to ${b.pt} is in the wall")
        // No floor known: the route is left alone.
        assertEquals(route.map { it.pt }, RouteExpander.expand(route) { null }.map { it.pt })
    }

    private fun gridOf(w: Int, d: Int, open: (Int, Int) -> Boolean): RoomGrid =
        RoomGrid(0, 0, w, d, BooleanArray(w * d) { open(it % w, it / w) })

    @Test fun theGridFindsThePathRoundAWallAndPullsItTaut() {
        val grid = gridOf(40, 20) { x, z -> !(x == 20 && z < 15) }
        val a = Pt(5.5, 5.5); val b = Pt(35.5, 5.5)
        assertTrue(!grid.lineClear(a, b))
        val path = assertNotNull(grid.path(a, b))
        assertEquals(b, path.last())
        var cur = a
        var len = 0.0
        for (p in path) { assertTrue(grid.lineClear(cur, p), "each stretch is in clear sight"); len += cur.dist(p); cur = p }
        assertTrue(len < 45.0 && len > 31.0, "the way round is longer than the straight 30 but not absurd: $len")
        assertTrue(path.size <= 4, "pulled taut: ${path.size} points")
    }

    @Test fun aPieceOfFloorCutOffIsApproachedAsFarAsTheWall() {
        val grid = gridOf(30, 10) { x, _ -> x != 15 }
        val path = assertNotNull(grid.path(Pt(2.5, 5.5), Pt(25.5, 5.5)))
        assertEquals(Pt(14.5, 5.5), path.last(), "a closet behind a wall is approached as far as the wall")
        assertNull(RoomGrid(0, 0, 4, 4, BooleanArray(16)).path(Pt(1.5, 1.5), Pt(3.5, 3.5)), "off the floor altogether")
        // A random place is always on the walker's own piece.
        val r = Random(2)
        repeat(100) { val p = assertNotNull(grid.randomPoint(Pt(2.5, 5.5), r)); assertTrue(p.x < 15.0) }
    }

    @Test fun nearestFindsTheClosestStandingPlace() {
        val grid = gridOf(10, 10) { x, z -> x >= 5 || z >= 8 }
        assertEquals(Pt(5.5, 2.5), grid.nearest(Pt(4.2, 2.1)))
        assertEquals(Pt(7.5, 7.5), grid.nearest(Pt(7.5, 7.5)))
        assertNull(RoomGrid(0, 0, 3, 3, BooleanArray(9)).nearest(Pt(1.0, 1.0)))
    }
}

/** With ADS_REAL_PACKS set: the walkable floors read from the real rooms, and the movement of whole plans against the recorded movement. */
class RealRoomWalkTest {
    private fun realDir() = System.getenv("ADS_REAL_PACKS")?.let { java.nio.file.Path.of(it) }?.takeIf { java.nio.file.Files.isDirectory(it) }

    @Test fun theFloorsOfTheRealRoomsAreWalkableAndConnected() {
        val dir = realDir()
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = com.engineerclient.p3sim.pack.Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts).pool()
            val areas = PackRoomAreas(set.rooms)
            var mainSum = 0.0; var rooms = 0; var withGrid = 0; var centreOpen = 0; var openSum = 0L; var noPath = 0; var pairs = 0
            val seen = HashSet<String>()
            for ((i, l) in layouts.withIndex().take(120)) {
                val dungeon = (com.engineerclient.p3sim.run.DungeonPlan.build(l, i.toLong(), set.rooms, set.runstate) as? com.engineerclient.p3sim.run.DungeonPlan.Result.Ok)?.plan ?: continue
                val geo = PlanGeometry(dungeon)
                val local = PackRoomAreas(set.rooms)
                for (room in dungeon.rooms) {
                    if (!seen.add(room.designId + "/" + room.rotation)) continue
                    rooms++
                    val grid = local.gridOf(room) ?: continue
                    withGrid++; openSum += grid.openCount
                    if (grid.nearest(geo.centre(room.index), 3) != null) centreOpen++
                    // Can a walker get from the middle of the room to its secrets?
                    val c = geo.centre(room.index)
                    mainSum += grid.mainSize.toDouble() / grid.openCount
                    for (s in RoomRoutes.secretSpots(room)) { pairs++; val p = grid.path(c, s.pt); if (p == null || p.last().dist(s.pt) > 6.0) noPath++ }
                }
            }
            println("rooms: $rooms distinct placements, the biggest piece of floor holds ${if (withGrid > 0) "%.2f".format(mainSum / withGrid) else "?"} of the open columns, $withGrid with a walkable floor, middle open in $centreOpen, mean open columns ${if (withGrid > 0) openSum / withGrid else 0}, $noPath of $pairs secrets are more than six blocks from the nearest place to walk to")
            assertTrue(withGrid > rooms * 0.8, "most rooms have a walkable floor: $withGrid of $rooms")
            assertTrue(centreOpen > withGrid * 0.6, "the middle of most rooms can be stood in: $centreOpen of $withGrid")
        } finally { com.engineerclient.p3sim.pack.Packs.unload() }
    }

    @Test fun wholePlansMoveLikeRecordedTeammates() {
        val dir = realDir()
        org.junit.jupiter.api.Assumptions.assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val set = com.engineerclient.p3sim.pack.Packs.load(dir!!)
        try {
            val layouts = assertNotNull(set.layouts).pool()
            val tables = BotTables(set.runstate)
            val seats = ClearSim.seatsFor(ClearSim.FIVE)
            val w = ArrayList<Double>()
            for ((i, l) in layouts.withIndex().take(60)) {
                val dungeon = (com.engineerclient.p3sim.run.DungeonPlan.build(l, i.toLong(), set.rooms, set.runstate) as? com.engineerclient.p3sim.run.DungeonPlan.Result.Ok)?.plan ?: continue
                val plan = ClearPlanner(PlannerInput(dungeon, tables, seats, Random(i.toLong()), areas = PackRoomAreas(set.rooms))).build()
                for (track in plan.tracks) {
                    // Only the time a bot spends working in a room (the walk between rooms is the door schedule's, not the wander's).
                    for (rp in plan.rooms.values) {
                        if (rp.seat != track.seat || rp.soft) continue
                        val end = rp.greenT ?: rp.whiteT ?: continue
                        var t = rp.arriveT
                        while (t + 20 <= end) { w += track.positionAt(t).dist(track.positionAt(t + 20)) / 20.0; t += 20 }
                    }
                }
            }
            w.sort()
            val n = w.size
            fun share(f: (Double) -> Boolean) = w.count(f).toDouble() / n
            println("windows $n: still ${share { it < 0.02 }} slow ${share { it in 0.1..0.6 }} fast ${share { it >= 1.0 }} p25 ${w[n / 4]} p50 ${w[n / 2]} p75 ${w[n * 3 / 4]} p90 ${w[n * 9 / 10]} p95 ${w[n * 19 / 20]} (recorded over the whole clear, travel included: still 0.255, slow 0.367, fast 0.117, p25 0.02, p50 0.19, p75 0.59, p90 1.09, p95 1.51)")
            assertTrue(share { it < 0.02 } in 0.12..0.45, "still ${share { it < 0.02 }}")
            assertTrue(share { it in 0.1..0.6 } in 0.25..0.65, "slow ${share { it in 0.1..0.6 }}")
            assertTrue(share { it >= 1.0 } < 0.12, "fast ${share { it >= 1.0 }}")
            assertTrue(w[n / 2] in 0.05..0.35, "median ${w[n / 2]}")
        } finally { com.engineerclient.p3sim.pack.Packs.unload() }
    }
}
