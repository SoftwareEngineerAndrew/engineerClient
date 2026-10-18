package com.engineerclient.p3sim.bots

import com.engineerclient.p3sim.clear.bots.BotActivity
import com.engineerclient.p3sim.clear.bots.BotMotion
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotMotionTest {
    private fun shares(activity: BotActivity, n: Int = 200_000): Map<String, Double> {
        val r = Random(11)
        val c = HashMap<String, Int>()
        repeat(n) { c.merge(BotMotion.pick(activity, r.nextDouble()), 1, Int::plus) }
        return c.mapValues { it.value.toDouble() / n }
    }

    @Test fun theItemSharesAreTheRecordedOnes() {
        val s = shares(BotActivity.STAND)
        for ((item, share) in BotMotion.ITEM_SHARES) assertEquals(share, s.getValue(item), 0.01, item)
        assertEquals(1.0, BotMotion.ITEM_SHARES.sumOf { it.second }, 1e-9)
    }

    @Test fun theWorkPullsTheMatchingItemsUp() {
        assertTrue(shares(BotActivity.SEARCH).getValue("breaker") > 0.3, "searching: the Dungeonbreaker")
        assertTrue(shares(BotActivity.FIGHT).getValue("hyperion") > 0.4, "fighting: the Hyperion")
        assertTrue(shares(BotActivity.WALK).getValue("aotv") > 0.4, "travelling: the Aspect of the Void")
    }

    @Test fun theMainHandChangesAboutTwelveTimesAMinute() {
        val m = BotMotion(Random(3)); m.start(1, "hyperion")
        var changes = 0
        val minutes = 60
        for (t in 0 until minutes * 1200) if (m.step(1, t, 0.3, 0f, BotActivity.WALK).item != null) changes++
        val perMinute = changes.toDouble() / minutes
        assertTrue(perMinute in 9.5..14.5, "main-hand changes a minute: $perMinute (recorded median 11.9)")
    }

    @Test fun aWorkingBotSwingsAboutEighteenTimesAMinuteAndAnIdleOneNever() {
        val m = BotMotion(Random(4))
        var swings = 0
        val minutes = 60
        for (t in 0 until minutes * 1200) if (m.step(1, t, 0.0, 0f, BotActivity.FIGHT).swing) swings++
        assertTrue(swings.toDouble() / minutes in 15.0..21.0, "swings a minute while working: ${swings.toDouble() / minutes}")
        val idle = BotMotion(Random(5))
        assertTrue((0 until 20_000).none { idle.step(1, it, 0.3, 0f, BotActivity.WALK).swing }, "walking is not working")
    }

    @Test fun aKillSwingsOnTheNextTickWhateverTheDiceSay() {
        val m = BotMotion(Random(6))
        assertTrue((0 until 50).none { m.step(1, it, 0.0, 0f, BotActivity.STAND).swing })
        m.swingNow(1)
        assertTrue(m.step(1, 50, 0.0, 0f, BotActivity.STAND).swing)
        assertTrue(!m.step(1, 51, 0.0, 0f, BotActivity.STAND).swing, "only once")
    }

    @Test fun aBotSprintsAboveAWalkingPaceAndNeverCrouchesWhileItMoves() {
        val m = BotMotion(Random(7))
        assertTrue(m.step(1, 0, 0.9, 0f, BotActivity.WALK).sprint)
        assertTrue(!m.step(1, 1, 0.3, 0f, BotActivity.WALK).sprint)
        assertTrue((0 until 5000).none { m.step(1, 2 + it, 0.4, 0f, BotActivity.WALK).crouch })
    }

    @Test fun aFifthOfTheStandingStretchesAreSpentCrouching() {
        val m = BotMotion(Random(8))
        var crouching = 0
        val n = 200_000
        var runs = 0; var inRun = false; var runLen = 0; var longest = 0
        for (t in 0 until n) {
            val c = m.step(1, t, 0.0, 0f, BotActivity.STAND).crouch
            if (c) { crouching++; runLen++; if (!inRun) { inRun = true; runs++ } } else { if (inRun) { longest = maxOf(longest, runLen); runLen = 0 }; inRun = false }
        }
        assertTrue(crouching.toDouble() / n in 0.14..0.26, "crouching share ${crouching.toDouble() / n} (recorded one stretch in five)")
        assertTrue(runs > 100)
    }

    @Test fun standingTheHeadKeepsTurningAndStaysCloseToTheBody() {
        val m = BotMotion(Random(9))
        var prev = Float.NaN
        var turning = 0
        val gaps = ArrayList<Float>()
        val n = 100_000
        for (t in 0 until n) {
            val f = m.step(1, t, 0.0, 90f, BotActivity.STAND)
            if (!prev.isNaN() && Math.abs(f.headYaw - prev) > 1f) turning++
            prev = f.headYaw
            gaps += Math.abs(f.headYaw - f.bodyYaw)
        }
        val share = turning.toDouble() / n
        assertTrue(share in 0.15..0.35, "standing ticks with a head turn above one degree: $share (recorded 0.24)")
        gaps.sort()
        assertTrue(gaps[(n * 0.95).toInt()] < 6f, "p95 of |head - body| ${gaps[(n * 0.95).toInt()]}")
    }

    @Test fun walkingTheBodyFacesTheWayItGoesAndTheHeadGlancesAboutIt() {
        val m = BotMotion(Random(10))
        var prev = Float.NaN
        var changed = 0
        val gaps = ArrayList<Float>()
        val n = 100_000
        for (t in 0 until n) {
            val f = m.step(1, t, 0.4, 135f, BotActivity.WALK)
            assertEquals(135f, f.bodyYaw)
            if (!prev.isNaN() && Math.abs(f.headYaw - prev) > 1f) changed++
            prev = f.headYaw
            gaps += Math.abs(f.headYaw - f.bodyYaw)
        }
        val share = changed.toDouble() / n
        assertTrue(share in 0.28..0.5, "walking ticks with a head change above one degree: $share (recorded 0.40)")
        gaps.sort()
        assertTrue(gaps[n / 2] < 1.0f, "median |head - body| ${gaps[n / 2]} (recorded 0.4)")
        assertTrue(gaps[(n * 0.95).toInt()] in 2.5f..6.5f, "p95 ${gaps[(n * 0.95).toInt()]} (recorded 4.2)")
    }

    @Test fun theSameSeedGivesTheSameLife() {
        fun run(): List<String> {
            val m = BotMotion(Random(12))
            return (0 until 3000).map { val f = m.step(1, it, if (it % 200 < 100) 0.4 else 0.0, 10f, BotActivity.FIGHT); "${f.item}${f.swing}${f.crouch}${f.headYaw}" }
        }
        assertEquals(run(), run())
    }
}
