package com.engineerclient.p3sim.mobs

import com.engineerclient.p3sim.clear.mobs.MobPlanner
import com.engineerclient.p3sim.clear.mobs.PlanParams
import com.engineerclient.p3sim.clear.mobs.PlannedMob
import com.engineerclient.p3sim.clear.mobs.SpawnMode
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.Placement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MobPlannerTest {
    private val pack = MobFixtures.pack()
    private val south = Placement.of("1x1", 31, 31, Rot.SOUTH, listOf(Tile(2, 3)))
    private val params = PlanParams(moverShare = 0.0)

    @Test fun cellsBecomeWorldPositionsWithHalfBlockCentres() {
        val plans = MobPlanner.planRoom(pack, "Test Room", null, south, PlanParams(mode = SpawnMode.CELLS, moverShare = 0.0), Random(1))
        assertTrue(plans.isNotEmpty())
        // Tile (2,3) has its min corner at (-136, -104): canonical (6,70,6) is world block (-130, 70, -98), centred at +0.5.
        // That cell has chance 20 of 20 visits, so it always spawns.
        val big = plans.firstOrNull { it.x == -129.5 }
        assertNotNull(big)
        assertEquals(-97.5, big.z)
        assertEquals(70.0, big.y)
    }

    @Test fun placementRotatesThePositions() {
        val west = Placement.of("1x1", 31, 31, Rot.WEST, listOf(Tile(0, 0)))
        val w = west.toWorld(2, 70, 4)
        val plans = MobPlanner.planRoom(pack, "Test Room", null, west, PlanParams(mode = SpawnMode.CELLS, moverShare = 0.0), Random(2))
        assertTrue(plans.any { it.x == w.x + 0.5 && it.z == w.z + 0.5 } || plans.isNotEmpty())
    }

    @Test fun starredMobsAreRequiredAndDisguisesGetVersionTwoUuids() {
        val plans = MobPlanner.planRoom(pack, "Test Room", null, south, params, Random(3))
        for (p in plans) {
            val a = pack.archetype(p.arch)!!
            assertEquals(p.star, p.required)
            assertEquals(if (a.disguise) 2 else 4, p.id.version())
        }
    }

    @Test fun hpIsBaseTimesScaleTimesModifier() {
        val plans = MobPlanner.planRoom(pack, "Test Room", null, south, PlanParams(hpScale = 1.5, moverShare = 0.0), Random(4))
        for (p in plans) {
            val base = pack.archetype(p.arch)!!.hpFor("F7")!!
            val f = if (p.modifier.isEmpty()) 1.0 else pack.modifier(p.modifier)!!.hpFactor
            assertEquals(Math.round(base * 1.5 * f).toDouble(), p.maxHp)
        }
    }

    @Test fun starredAndUnstarredClearMobsDrawAPrefixWord() {
        val rng = Random(5)
        var words = 0; var unstarred = 0; var unstarredWords = 0
        repeat(300) {
            for (p in MobPlanner.planRoom(pack, "Test Room", null, south, params, rng)) if (p.modifier.isNotEmpty()) words++
            for (p in MobPlanner.planRoom(pack, "Test Room", null, south, PlanParams(mode = SpawnMode.CELLS, moverShare = 0.0), rng)) if (!p.star) { unstarred++; if (p.modifier.isNotEmpty()) unstarredWords++ }
        }
        assertTrue(words > 100)
        assertTrue(unstarred > 20)
        // The fixture's table is half "no word"; recorded unstarred mobs are modified about 45 percent of the time.
        assertEquals(0.5, unstarredWords.toDouble() / unstarred, 0.15)
        for (p in MobPlanner.planRoom(pack, "King Room", null, south, params, Random(6))) assertEquals("", if (pack.archetype(p.arch)!!.cls == "clear") "" else p.modifier)
    }

    @Test fun minibossRoomHasExactlyOneBossOnThePatrolSpawnWithItsLoop() {
        repeat(40) { i ->
            val plans = MobPlanner.planRoom(pack, "Boss Room", null, south, PlanParams(moverShare = 0.0, minibossChance = 1.0), Random(100L + i))
            val bosses = plans.filter { pack.archetype(it.arch)!!.cls == "miniboss" }
            assertEquals(1, bosses.size)
            val b = bosses.single()
            val w = south.toWorld(12, 70, 12)
            assertEquals(w.x + 0.5, b.x); assertEquals(w.z + 0.5, b.z)
            assertEquals(4, b.loop!!.size)
            assertTrue(b.mover)
            assertTrue(b.required)
        }
    }

    @Test fun minibossTypeIsDrawnPerRunNotTakenFromTheRow() {
        val seen = HashMap<String, Int>()
        repeat(600) { i ->
            val plans = MobPlanner.planRoom(pack, "Boss Room", null, south, PlanParams(moverShare = 0.0, minibossChance = 1.0), Random(1000L + i))
            seen.merge(pack.archetype(plans.single { pack.archetype(it.arch)!!.cls == "miniboss" }.arch)!!.id, 1, Int::plus)
        }
        assertEquals(setOf("lost_adventurer", "angry_archaeologist", "frozen_adventurer"), seen.keys)
        assertEquals(0.54, seen.getValue("lost_adventurer") / 600.0, 0.07)
        assertEquals(0.19, seen.getValue("frozen_adventurer") / 600.0, 0.06)
    }

    @Test fun onlySomeMinibossRoomsHoldAMiniboss() {
        val isBoss = { p: PlannedMob -> pack.archetype(p.arch)!!.cls == "miniboss" }
        var with = 0
        repeat(400) { i -> if (MobPlanner.planRoom(pack, "Boss Room", null, south, params, Random(3000L + i)).any(isBoss)) with++ }
        assertEquals(MobPlanner.MINIBOSS_CHANCE, with / 400.0, 0.08)
        // Chance zero: never a miniboss, but the room's ordinary mobs still stand (an empty draw used to clear the whole room).
        repeat(20) { i ->
            val plans = MobPlanner.planRoom(pack, "Boss Room", null, south, PlanParams(minibossChance = 0.0), Random(1L + i))
            assertTrue(plans.none(isBoss))
        }
        val rest = (0 until 20).sumOf { i -> MobPlanner.planRoom(pack, "Boss Room", null, south, PlanParams(minibossChance = 0.0), Random(1L + i)).size }
        assertTrue(rest > 0, "the ordinary mobs of a miniboss room spawn without their miniboss")
    }

    @Test fun minibossesTakeTheRunHpScaleAndTheClearAssassinHasFourPointTwoMillion() {
        val b = MobPlanner.planRoom(pack, "Boss Room", null, south, PlanParams(hpScale = 1.5, moverShare = 0.0, minibossChance = 1.0), Random(5)).single { pack.archetype(it.arch)!!.cls == "miniboss" }
        assertEquals(Math.round(pack.archetype(b.arch)!!.hpFor("F7")!! * 1.5).toDouble(), b.maxHp)
        val sa = pack.archetypeById("test_assassin")!!
        assertEquals(4_200_000L, MobPlanner.clearHp(sa, "F7"))
        assertEquals(145_000_000L, MobPlanner.clearHp(sa, "M7"))
    }

    @Test fun mobsSpawnFacingYawZero() {
        for (p in MobPlanner.planRoom(pack, "Test Room", null, south, params, Random(11))) assertEquals(0f, p.yaw)
    }

    @Test fun kingMidasIsNotSpawnedByTheRoomRoll() {
        repeat(20) { i ->
            for (p in MobPlanner.planRoom(pack, "King Room", null, south, params, Random(i.toLong()))) assertTrue(pack.archetype(p.arch)!!.id != "king_midas")
        }
    }

    @Test fun moverShareSplitsStaticMobs() {
        var movers = 0; var all = 0
        val rng = Random(7)
        repeat(400) {
            for (p in MobPlanner.planRoom(pack, "Test Room", null, south, PlanParams(moverShare = 0.25), rng)) {
                val a = pack.archetype(p.arch)!!
                if (a.movement == "static") { all++; if (p.mover) movers++ }
            }
        }
        assertEquals(0.25, movers.toDouble() / all, 0.03)
    }

    @Test fun sleepersFollowTheMoverShareLikeOtherMobs() {
        val rng = Random(8)
        val fels = (1..400).flatMap { MobPlanner.planRoom(pack, "Test Room", null, south, PlanParams(mode = SpawnMode.CELLS, moverShare = 0.0), rng) }
            .filter { pack.archetype(it.arch)!!.movement == "sleeper" }
        assertTrue(fels.isNotEmpty())
        assertTrue(fels.none { it.mover })
    }

    @Test fun unknownRoomSpawnsNothing() {
        assertTrue(MobPlanner.planRoom(pack, "No Such Room", null, south, params, Random(9)).isEmpty())
    }

    @Test fun sameSeedSamePlan() {
        val a = MobPlanner.planRoom(pack, "Test Room", null, south, params, Random(42)).map { listOf(it.id, it.arch, it.x, it.z, it.maxHp) }
        val b = MobPlanner.planRoom(pack, "Test Room", null, south, params, Random(42)).map { listOf(it.id, it.arch, it.x, it.z, it.maxHp) }
        assertEquals(a, b)
    }
}
