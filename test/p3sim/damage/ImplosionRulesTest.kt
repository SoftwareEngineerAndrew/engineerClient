package com.engineerclient.p3sim.clear.damage

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which mobs an Implosion reaches, how hard it hits per floor, and how many casts a mob needs. */
class ImplosionRulesTest {
    private fun cand(n: Long, x: Double, y: Double, z: Double, alive: Boolean = true, untargetable: Boolean = false, kind: String = "zombie_soldier"): ImplosionRules.Candidate {
        val (w, h) = ImplosionRules.size(kind)
        return ImplosionRules.Candidate(UUID(0, n), ImplosionRules.Hitbox(x, y, z, w, h), alive, untargetable)
    }

    private val box = ImplosionRules.castBox(100.0, 70.62, 100.0)

    @Test fun theBoxIsSixSidewaysSixDownAndSevenUpFromTheEye() {
        assertEquals(listOf(94.0, 64.62, 94.0, 106.0, 77.62, 106.0), box.toList().map { Math.round(it * 100) / 100.0 })
    }

    @Test fun aMobInsideTheBoxIsReachedAndOneOutsideIsNot() {
        val inside = cand(1, 103.0, 70.0, 98.0)
        val farX = cand(2, 107.0, 70.0, 100.0)
        val farZ = cand(3, 100.0, 70.0, 94.0 - 1.0)
        assertEquals(listOf(inside.id), ImplosionRules.select(box, listOf(inside, farX, farZ)))
    }

    @Test fun theHitboxCountsNotJustTheFeet() {
        // feet 6.2 blocks away sideways, but the body (0.6 wide) reaches 5.9: inside
        val edge = cand(1, 106.2, 70.0, 100.0)
        assertEquals(listOf(edge.id), ImplosionRules.select(box, listOf(edge)))
        // feet 6.4 away: body edge at 6.1, outside
        assertTrue(ImplosionRules.select(box, listOf(cand(2, 106.4, 70.0, 100.0))).isEmpty())
    }

    @Test fun theReachIsLongerUpThanDown() {
        // feet 7 blocks above the eye reach the box through the top (the body starts at 77.62 = eye + 7)
        assertEquals(1, ImplosionRules.select(box, listOf(cand(1, 100.0, 77.5, 100.0))).size)
        assertEquals(0, ImplosionRules.select(box, listOf(cand(2, 100.0, 77.7, 100.0))).size)
        // below: the top of the body (feet + 1.95) must reach eye - 6 = 64.62, so feet down to 62.67
        assertEquals(1, ImplosionRules.select(box, listOf(cand(3, 100.0, 62.8, 100.0))).size)
        assertEquals(0, ImplosionRules.select(box, listOf(cand(4, 100.0, 62.5, 100.0))).size)
    }

    @Test fun aTallFelsReachesFurtherDownThanAZombie() {
        val zombie = cand(1, 100.0, 62.0, 100.0, kind = "zombie_soldier")
        val fels = cand(2, 100.0, 62.0, 100.0, kind = "fels")
        assertEquals(listOf(fels.id), ImplosionRules.select(box, listOf(zombie, fels)))
    }

    @Test fun deadAndSleepingMobsAreNotHit() {
        val dead = cand(1, 100.0, 70.0, 100.0, alive = false)
        val asleep = cand(2, 101.0, 70.0, 100.0, untargetable = true, kind = "fels")
        val awake = cand(3, 102.0, 70.0, 100.0)
        assertEquals(listOf(awake.id), ImplosionRules.select(box, listOf(dead, asleep, awake)))
    }

    @Test fun everyMobInTheBoxIsReachedWithNoCrowdSplit() {
        val crowd = (1L..12L).map { cand(it, 95.0 + it * 0.8, 70.0, 100.0) }
        assertEquals(12, ImplosionRules.select(box, crowd).size)
    }

    @Test fun oneF7CastKillsTheBiggestOrdinaryMobEvenAtTheBiggestHpScale() {
        val f7 = ImplosionRules.profile("F7")
        val lowestRoll = f7.roll(object : Random() { override fun nextBits(bitCount: Int) = 0 })
        // the strongest ordinary F7 mob is 8.8M (the 5.0M Zombie Lord times the 1.76 scale seen); the cast must still kill it
        assertTrue(lowestRoll > 8_800_000.0, "lowest roll $lowestRoll")
        assertEquals(1, ImplosionRules.castsNeeded(8_800_000.0, lowestRoll))
        assertEquals(1, ImplosionRules.castsNeeded(5_000_000.0 * 2.1, lowestRoll))
        assertEquals(0, ImplosionRules.castsNeeded(0.0, lowestRoll))
    }

    @Test fun m7CastsRunFromOneToThirteen() {
        val m7 = ImplosionRules.profile("M7")
        assertEquals(56_600_000.0, m7.median)
        val per = m7.median
        assertEquals(1, ImplosionRules.castsNeeded(40_000_000.0, per))
        assertEquals(13, ImplosionRules.castsNeeded(720_000_000.0, per))
        assertEquals(2, ImplosionRules.castsNeeded(56_600_001.0, per))
    }

    @Test fun theMultiplierScalesTheCastAndTheSpreadStaysInTheRecordedRange() {
        val p = ImplosionRules.profile("F7")
        val r = Random(1)
        repeat(200) {
            val d = p.roll(r, 0.5)
            assertTrue(d in p.median * 0.5 * 0.9..p.median * 0.5 * 1.15, "roll $d")
        }
        // halved gear still kills on F7, but a 1.5x scaled run does not need more casts
        assertEquals(1, ImplosionRules.castsNeeded(8_800_000.0, p.median * 0.5 * 0.9))
    }

    @Test fun f7DamageDiffersByKindAndByCast() {
        val p = ImplosionRules.profile("F7")
        val r = Random(7)
        val base = p.castBase(r)
        assertTrue(base in p.median * 0.7..p.median * 1.3, "cast base $base")
        val tank = p.perMob("super_tank_zombie", false, 3_000_000.0, 1.0, base, r)
        val knight = p.perMob("zombie_knight", false, 5_000_000.0, 1.0, base, r)
        assertTrue(knight > tank * 20, "knight $knight tank $tank")
        // casts differ: over many casts the base is not constant
        val bases = (1..50).map { p.castBase(r) }.toSet()
        assertTrue(bases.size > 40)
        // M7 stays flat
        val m7 = ImplosionRules.profile("M7")
        assertEquals(1.0e7, m7.perMob("zombie_knight", false, 1.0e8, 1.0, 1.0e7, r))
    }

    @Test fun minibossesAndTheShadowAssassinNeedTwoCasts() {
        val p = ImplosionRules.profile("F7")
        val r = Random(3)
        repeat(100) {
            val base = p.castBase(r)
            // Lost Adventurer 2.6M, Angry Archaeologist 420k, Shadow Assassin 20M, at scale 1.0 and 1.2
            for ((kind, hp) in listOf("lost_adventurer" to 2_600_000.0, "angry_archaeologist" to 420_000.0, "shadow_assassin" to 20_000_000.0)) {
                for (scale in listOf(1.0, 1.2)) {
                    val d = p.perMob(kind, true, hp * scale, scale, base, r)
                    assertTrue(d <= 0.75 * hp * 1.05 + 1.0, "$kind $d")
                    assertEquals(2, ImplosionRules.castsNeeded(hp * scale, d), "$kind scale $scale")
                }
            }
        }
    }

    @Test fun unknownFloorsUseF7NumbersAndMNamesUseM7() {
        assertEquals("F7", ImplosionRules.profile("F5").floor)
        assertEquals("M7", ImplosionRules.profile("M7").floor)
    }

    @Test fun theChatLineIsHypixelsWithACommaAndNoTrailingZero() {
        assertEquals("§7Your Implosion hit §r§c1 §r§7enemy for §r§c25,400,000 §r§7damage.", ImplosionRules.line(1, 25_400_000.0))
        assertEquals("§7Your Implosion hit §r§c3 §r§7enemies for §r§c76,200,000.5 §r§7damage.", ImplosionRules.line(3, 76_200_000.5))
        assertFalse(ImplosionRules.line(2, 2.0).contains(".0 "))
    }
}
