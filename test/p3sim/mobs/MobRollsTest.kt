package com.engineerclient.p3sim.mobs

import com.engineerclient.p3sim.clear.mobs.MobRolls
import com.engineerclient.p3sim.clear.mobs.SpawnMode
import com.engineerclient.p3sim.pack.mobs.MeleeTable
import com.engineerclient.p3sim.pack.mobs.MobLayout
import com.engineerclient.p3sim.pack.mobs.MobSpawn
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The samplers against the SHAPE of the pack's data (synthetic numbers): star counts per room follow the recorded distribution. */
class MobRollsTest {
    private val pack = MobFixtures.pack()
    private val room = pack.room("Test Room")!!

    @Test fun layoutSamplerReplaysRecordedVisitsInTheRightProportions() {
        // Layouts: three full ones (6, 5, 6 stars), one partial (1 star, weight 0.25), one ClearData (6 stars, floor "?").
        val rng = Random(1)
        val n = 40_000
        val counts = HashMap<Int, Int>()
        repeat(n) { counts.merge(MobRolls.rollRoom(room, "F7", rng, SpawnMode.LAYOUTS).count { it.star }, 1, Int::plus) }
        val total = 1 + 1 + 1 + 0.25 + 1   // weights of the five layouts
        fun share(c: Int) = counts.getOrDefault(c, 0).toDouble() / n
        assertEquals(3 / total, share(6), 0.01)    // two ADR + the CD layout
        assertEquals(1 / total, share(5), 0.01)
        assertEquals(0.25 / total, share(1), 0.01)
        assertEquals(0, counts.getOrDefault(0, 0))
    }

    @Test fun partialWeightZeroNeverDrawsThePartialView() {
        val rng = Random(2)
        repeat(2000) { assertTrue(MobRolls.rollRoom(room, "F7", rng, SpawnMode.LAYOUTS, partialWeight = 0.0).count { it.star } >= 5) }
    }

    @Test fun cellSamplerHasTheExpectedStarredMean() {
        // Cells: (2,2) occ 10/20; (4,4) occ 10/20 total over two archetypes; (6,6) occ 20/20; the (8,8) row is unstarred.
        assertEquals(0.5 + 0.5 + 1.0, MobRolls.expectedStarred(room), 1e-9)
        val rng = Random(3)
        val n = 30_000
        var stars = 0; var unstarred = 0; var archA = 0; var archB = 0
        repeat(n) {
            for (m in MobRolls.rollCells(room, rng)) {
                if (m.star) stars++ else unstarred++
                if (m.x == 4) { if (m.arch == 1) archB++ else if (m.arch == 0) archA++ }
            }
        }
        assertEquals(2.0, stars.toDouble() / n, 0.03)
        assertEquals(0.1, unstarred.toDouble() / n, 0.01)
        assertEquals(1.0, archA.toDouble() / archB, 0.15)   // a 5:5 split between the two kinds at one cell
    }

    @Test fun aVisitBlindToTheUnstarredMobsTakesThemFromACompleteOne() {
        // Three visits saw only the starred mob, two saw the room's eight unstarred mobs too (one of them on the starred mob's block).
        val star = MobSpawn(1, 70, 1, 0, true)
        val blind = List(3) { MobLayout("adr", "F7", listOf(star.copy(x = it + 1))) }
        val full = List(2) { k -> MobLayout("adr", "F7", listOf(star) + List(8) { MobSpawn(if (it == 0 && k == 0) 2 else 10 + it, 70, if (it == 0 && k == 0) 1 else 5 + k, 1, false) }) }
        val boss = pack.room("Boss Room")!!
        val r = com.engineerclient.p3sim.pack.mobs.RoomMobs("Hall", emptyList(), "mob", 5, 0, boss.starred, null, emptyList(), blind + full, emptyList(), null)
        assertEquals(full, MobRolls.completeUnstarred(blind + full))
        val rng = Random(7)
        repeat(300) {
            val m = MobRolls.rollLayout(r, "F7", rng)!!
            assertEquals(1, m.count { it.star }, "the drawn visit's starred mob stays")
            assertTrue(m.count { !it.star } >= 7, "the unstarred mobs of a complete visit come along")
            val starAt = m.filter { it.star }.map { Triple(it.x, it.y, it.z) }
            assertTrue(m.none { !it.star && Triple(it.x, it.y, it.z) in starAt }, "never two mobs on one block")
        }
        // A room no visit saw unstarred mobs in keeps its visits as they are.
        assertTrue(MobRolls.completeUnstarred(blind).isEmpty())
    }

    @Test fun fewerThanThreeLayoutsFallBackToCells() {
        val rng = Random(4)
        val boss = pack.room("Boss Room")!!
        val one = com.engineerclient.p3sim.pack.mobs.RoomMobs(boss.name, emptyList(), "mob", 4, 0, boss.starred, null, boss.cells, boss.layouts.take(2), emptyList(), null)
        assertNull(MobRolls.rollLayout(one, "F7", rng))
        assertEquals(1, MobRolls.rollRoom(one, "F7", rng).size)   // the single always-on cell
    }

    @Test fun wrongFloorHasNoLayouts() {
        // Only the floorless ClearData layout fits M7: fewer than three.
        assertNull(MobRolls.rollLayout(room, "M7", Random(5)))
    }

    @Test fun hpScaleFollowsTheTable() {
        val t = pack.hpScaleFor("F7")!!
        val rng = Random(6)
        val draws = List(20_000) { MobRolls.drawHpScale(t, rng) }
        assertEquals(0.7, draws.count { it == 1.0 }.toDouble() / draws.size, 0.015)
        assertEquals(0.3, draws.count { it == 1.5 }.toDouble() / draws.size, 0.015)
    }

    @Test fun modifierWeightsAndTheM7Fallback() {
        val rng = Random(7)
        val n = 30_000
        val f7 = HashMap<String, Int>()
        repeat(n) { f7.merge(MobRolls.rollModifier(pack.modifiers, "F7", rng)!!.id, 1, Int::plus) }
        assertEquals(0.5, f7.getValue("").toDouble() / n, 0.015)
        assertEquals(0.2, f7.getValue("Healthy").toDouble() / n, 0.015)
        // Healthy's M7 weight is null: it reuses the F7 weight.
        val m7 = HashMap<String, Int>()
        repeat(n) { m7.merge(MobRolls.rollModifier(pack.modifiers, "M7", rng)!!.id, 1, Int::plus) }
        assertTrue(m7.getValue("Healthy") > n * 0.15)
        // A per-archetype override wins.
        assertEquals("Speedy", MobRolls.rollModifier(pack.modifiers, "F7", rng, mapOf("Speedy" to 1.0))!!.id)
    }

    @Test fun spawnHpIsRoundedBaseTimesScaleTimesFactor() {
        assertEquals(2_080_000.0, MobRolls.spawnHp(1_300_000, 1.0, pack.modifier("Healthy")))
        assertEquals(1_365_000.0, MobRolls.spawnHp(1_300_000, 1.05, null))
        assertEquals(3_276_000.0, MobRolls.spawnHp(1_300_000, 1.575, pack.modifier("Healthy")))
    }

    private val melee: MeleeTable get() = pack.playerDamage!!.melee.getValue("F7")

    @Test fun meleeInterpolatesLinearlyInLog() {
        // quantiles {0: 0, 50: 2, 100: 120}; u = 75: exp(lerp(ln 2, ln 120, 0.5)) = sqrt(240) = 15.49
        assertEquals(Math.sqrt(240.0), MobRolls.sampleMeleeAt(melee, atomRoll = 0.99, u = 75.0), 1e-9)
        assertEquals(2.0, MobRolls.sampleMeleeAt(melee, 0.99, 50.0), 1e-9)
        assertEquals(120.0, MobRolls.sampleMeleeAt(melee, 0.99, 100.0), 1e-9)
        assertEquals(0.0, MobRolls.sampleMeleeAt(melee, 0.99, 0.0), 1e-9)
        assertEquals(10.0, MobRolls.sampleMeleeAt(melee, 0.05, 75.0))   // under the atom total (0.1): the atom
    }

    @Test fun meleeSamplesStayInRange() {
        val rng = Random(8)
        repeat(5000) { val v = MobRolls.sampleMelee(melee, rng); assertTrue(v in 0.0..120.0, v.toString()) }
    }

    @Test fun projectileHitsFollowThePercentiles() {
        val h = pack.playerDamage!!.projectile.getValue("arrow")
        assertEquals(3.0, MobRolls.sampleHitAt(h, 50.0), 1e-9)
        assertEquals(0.5, MobRolls.sampleHitAt(h, 10.0), 1e-9)
        assertEquals(9.0, MobRolls.sampleHitAt(h, 90.0), 1e-9)
        val rng = Random(9)
        val draws = List(20_000) { MobRolls.sampleHit(h, rng) }.sorted()
        assertEquals(3.0, draws[draws.size / 2], 0.2)
    }

    @Test fun fakePlayerUuidsAreVersionTwoAndOthersFour() {
        val rng = Random(10)
        repeat(500) {
            val v2 = MobRolls.uuidV2(rng); val v4 = MobRolls.uuidV4(rng)
            assertEquals(2, v2.version()); assertEquals(4, v4.version())
            assertEquals('2', v2.toString()[14])
        }
    }

    @Test fun profileNamesAreTenLowercaseAlphanumerics() {
        val rng = Random(11)
        repeat(200) { val n = MobRolls.profileName(rng); assertEquals(10, n.length); assertTrue(n.all { it in 'a'..'z' || it in '0'..'9' }) }
        assertNotNull(pack)
    }

    @Test fun sameSeedSameRoom() {
        val a = MobRolls.rollRoom(room, "F7", Random(99), SpawnMode.CELLS)
        val b = MobRolls.rollRoom(room, "F7", Random(99), SpawnMode.CELLS)
        assertEquals(a, b)
    }
}
