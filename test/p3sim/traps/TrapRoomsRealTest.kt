package com.engineerclient.p3sim.clear.traps

import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.rooms.RoomPack
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Optional: the trap rooms' crushers and tripwires against the real rooms (ADS_REAL_PACKS; skipped otherwise). */
class TrapRoomsRealTest {
    private val dir: Path? = System.getenv("ADS_REAL_PACKS")?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    @Test fun everyTrapRoomCrusherSweepsThroughFreeCellsAndEveryHookPairs() {
        assumeTrue(dir != null, "ADS_REAL_PACKS not set")
        val rooms = assertNotNull(RoomPack.load(dir!!, PackReport()))
        var crushers = 0
        for (id in listOf("New_Trap", "Old_Trap")) {
            val design = assertNotNull(rooms.design(id))
            val vol = assertNotNull(rooms.volume(design))
            for (rc in design.crushers) {
                val c = Crusher(rc.id, rc.slab, rc.axis!!.first(), rc.sign, rc.sweep, rc.material!!, TrapTimings.DEFAULT)
                assertTrue(c.layer(0).all { !vol.isAir(it[0], it[1], it[2]) }, "$id ${rc.id}: the slab stands at rest")
                for (k in 1..c.sweep) {
                    val blocked = c.cells(k).filter { it[0] in 0 until vol.w && it[2] in 0 until vol.d && !vol.isAir(it[0], it[1], it[2]) }
                    // A crusher may end in a wall at its far end, but must travel mostly through open cells.
                    assertTrue(blocked.size <= c.layer(0).size, "$id ${rc.id} k=$k: ${blocked.size} solid cells in its way")
                }
                crushers++
            }
            val lines = TripLine.pair(design.tripwires.map { Hook(it.pos.x, it.pos.y, it.pos.z, it.facing ?: "") })
            assertEquals(design.tripwires.size / 2, lines.size, "$id pairs every hook")
        }
        assertEquals(10, crushers)
    }

    @Test fun theTimingsFileNamesRealCrushersOfThePack() {
        assumeTrue(dir != null && Files.isRegularFile(dir.resolve("traps.json")), "no traps.json next to the packs")
        val rooms = assertNotNull(RoomPack.load(dir!!, PackReport()))
        val ids = rooms.designs.flatMap { d -> d.crushers.map { it.id } }.toSet()
        val root = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("traps.json"))).asJsonObject
        val listed = root.getAsJsonObject("crushers").keySet()
        assertTrue(listed.isNotEmpty())
        assertTrue(listed.all { it in ids }, "ids in the file that no design has: ${listed.filter { it !in ids }}")
        val t = TrapTimings.load(dir)
        for (id in ids) assertTrue(t.forCrusher(id).period(1) > 0)
    }

    @Test fun theOldTrapPincerMeetsInTheMiddleAndTheNewTrapCrushersHaveTheirMeasuredPace() {
        assumeTrue(dir != null && Files.isRegularFile(dir.resolve("traps.json")), "no traps.json next to the packs")
        val rooms = assertNotNull(RoomPack.load(dir!!, PackReport()))
        val timings = TrapTimings.load(dir)
        val plan = timings.roll(kotlin.random.Random(7))
        val old = assertNotNull(rooms.design("Old_Trap"))
        val arms = old.crushers.map { rc ->
            val t = plan.timing(rc.id)
            Crusher(rc.id, rc.slab, rc.axis!!.first(), rc.sign, t.sweep ?: rc.sweep, rc.material!!, t, plan.startAt(rc.id))
        }
        assertEquals(listOf(13, 13), arms.map { it.sweep })
        assertEquals(155, arms[0].timing.period(arms[0].sweep)); assertEquals(155, arms[1].timing.period(arms[1].sweep))
        val boxes = arms.map { it.armBox(it.sweep) }.sortedBy { it[0] }
        assertEquals(listOf(2, 14), listOf(boxes[0][0], boxes[0][3]), "the left arm covers x 2..14")
        assertEquals(listOf(15, 27), listOf(boxes[1][0], boxes[1][3]), "the right arm covers x 15..27")
        assertEquals(520, arms.sumOf { it.cells(it.sweep).size })
        val nt = assertNotNull(rooms.design("New_Trap"))
        val low = nt.crushers.associate { rc -> rc.id.substringAfter('/') to plan.timing(rc.id) }
        for (n in listOf("crusher-6", "crusher-7", "crusher-8")) { assertEquals(3, low.getValue(n).ticksPerBlock, n); assertEquals(18, low.getValue(n).stillTicks, n) }
        for (n in listOf("crusher-1", "crusher-2", "crusher-3")) { assertEquals(5, low.getValue(n).ticksPerBlock, n); assertEquals(30, low.getValue(n).stillTicks, n) }
        assertTrue(low.getValue("crusher-4").ticksPerBlock in listOf(40, 30) && low.getValue("crusher-5").ticksPerBlock in listOf(40, 20))
    }

    @Test fun theFallRulesPickTheRealFloors() {
        assumeTrue(dir != null && Files.isRegularFile(dir.resolve("traps.json")), "no traps.json next to the packs")
        val rooms = assertNotNull(RoomPack.load(dir!!, PackReport()))
        val timings = TrapTimings.load(dir)
        assertEquals(setOf("New_Trap", "Old_Trap"), timings.sites.falls.map { it.design }.toSet())
        for (rule in timings.sites.falls) {
            val design = assertNotNull(rooms.design(rule.design))
            val lines = TripLine.pair(design.tripwires.map { Hook(it.pos.x, it.pos.y, it.pos.z, it.facing ?: "") })
            val floor = assertNotNull(TrapsModule.fallingFloor(rule, design.fallingBlocks.map { intArrayOf(it.x, it.y, it.z) }, lines), rule.design)
            val vol = assertNotNull(rooms.volume(design))
            // Every cell of the floor holds a block in the base room.
            assertTrue(floor.cells.all { !vol.isAir(it[0], it[1], it[2]) }, "${rule.design}: cells hold blocks")
            if (rule.design == "New_Trap") {
                assertEquals(143, floor.cells.size); assertTrue(floor.trigger is FloorTrigger.StandOn)
                assertEquals(listOf(10, 20, 8, 24), listOf(floor.cells.minOf { it[0] }, floor.cells.maxOf { it[0] }, floor.cells.minOf { it[2] }, floor.cells.maxOf { it[2] }))
            } else {
                assertEquals(28, floor.cells.size, "the walkway beside the string, not the second floor")
                val t = floor.trigger as FloorTrigger.StringTouched
                val l = lines[t.line]
                assertEquals(listOf('z', 81, 15, 0, 8), listOf(l.axis, l.y, l.fixed, l.from, l.to))
                assertEquals(8, floor.extra.size)
            }
        }
    }

    @Test fun everyArrowBankAndFlamethrowerOfTheFileStandsOnARealDispenserWithOpenAirInFront() {
        assumeTrue(dir != null && Files.isRegularFile(dir.resolve("traps.json")), "no traps.json next to the packs")
        val rooms = assertNotNull(RoomPack.load(dir!!, PackReport()))
        val sites = TrapTimings.load(dir).sites
        assertTrue(sites.arrows.size >= 10 && sites.flames.size >= 10, "banks ${sites.arrows.size}, flames ${sites.flames.size}")
        val all = sites.arrows.flatMap { r -> r.dispensers.map { r.design to it } } + sites.flames.flatMap { r -> r.emitters.map { r.design to it } }
        for ((id, e) in all) {
            val design = assertNotNull(rooms.design(id), id)
            val vol = assertNotNull(rooms.volume(design))
            val state = vol.stateAt(e.x, e.y, e.z)
            assertTrue("dispenser" in state && "facing=${e.facing}" in state, "$id (${e.x},${e.y},${e.z}) should be a dispenser facing ${e.facing}, is $state")
            assertTrue(vol.isAir(e.x + e.dx, e.y + e.dy, e.z + e.dz), "$id (${e.x},${e.y},${e.z}): the cell in front is not open")
        }
        // Spot checks against the recordings: Melon's eight dispensers beat every 40 ticks, Mines' six every 5, Buttons' five every 30; the Slime pair and the New Trap pit flame.
        val byName = rooms.designs.associateBy { it.id }
        fun banks(name: String) = sites.arrows.filter { byName[it.design]?.name == name }
        assertEquals(listOf(40 to 8), banks("Melon").map { it.period to it.dispensers.size })
        assertEquals(listOf(30 to 5), banks("Buttons").map { it.period to it.dispensers.size })
        assertTrue(banks("Mines").any { it.period == 5 && it.dispensers.size == 6 })
        assertTrue(sites.flames.any { byName[it.design]?.name == "Slime" && it.emitters.size == 2 })
        val pit = sites.flames.single { byName[it.design]?.name == "New Trap" && it.region != null }
        assertEquals(1, pit.perBeatMin); assertEquals(3, pit.perBeatMax); assertTrue(pit.emitters.all { it.y == 64 })
    }
}
