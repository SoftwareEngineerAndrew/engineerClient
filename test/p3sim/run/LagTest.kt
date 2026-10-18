package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.runstate.LagTier
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lag layer's model: the loss it produces, the shape of a freeze, and the evidence it leaves in the tick timeline. */
class LagTest {
    private val params = LagParams.DEFAULTS

    /** Simulates [hours] of wall time and returns (lost ticks per minute, share of onsets within 5 s of another, events list). */
    private fun simulate(loss: Double, hours: Int, seed: Int): Triple<Double, Double, List<LagEvent>> {
        val g = LagGenerator(params, loss, Random(seed))
        val events = ArrayList<LagEvent>()
        var wall = 0L                      // wall ticks: ticks that ran plus ticks missed by freezes, less the recovered
        var lostTotal = 0L
        val onsetAt = ArrayList<Long>()
        val wallTarget = hours * 72_000L
        while (wall < wallTarget) {
            val e = g.nextTick()
            wall++
            if (e != null) {
                events += e; onsetAt += wall
                wall += e.missedTicks
                lostTotal += e.lostTicks
            }
        }
        val bigAt = events.indices.filter { events[it].gapMs >= LagGenerator.CLUSTER_FROM_MS }.map { onsetAt[it] }
        val within = bigAt.zipWithNext().count { (a, b) -> b - a <= 100 }.toDouble() / (bigAt.size - 1)
        return Triple(lostTotal / (wall / 1200.0), within, events)
    }

    @Test fun thePresetsLoseTheirTicksPerMinute() {
        for (loss in listOf(14.5, 30.3, 56.6, 54.6)) {
            val (measured, _, _) = simulate(loss, 40, 1)
            assertEquals(loss, measured, loss * 0.10, "target $loss, measured $measured")
        }
    }

    @Test fun morePresetMeansMoreLossAndMoreFreezes() {
        val good = simulate(14.5, 20, 2); val bad = simulate(56.6, 20, 2)
        assertTrue(bad.first > good.first * 2.5)
        assertTrue(bad.third.size > good.third.size * 2)
    }

    @Test fun freezesComeInTheMeasuredSizeMixAndCluster() {
        val (_, within, events) = simulate(30.3, 60, 3)
        assertTrue(events.isNotEmpty() && events.all { it.gapMs >= 100.0 && it.gapMs <= params.maxGapMs })
        // The table: 23.1 of 1230.25 freezes an hour reach 1 s, 1.9 %. Most are short.
        val long = events.count { it.gapMs >= 1000 }.toDouble() / events.size
        assertTrue(long in 0.008..0.04, "share of freezes of 1 s or more was $long")
        assertTrue(events.count { it.gapMs < 250 } > events.size / 2)
        assertEquals(0.313, within, 0.07, "share of the freezes of 250 ms or more that start within 5 s of another")
    }

    @Test fun aBurstRecoversOnlyPartOfWhatAFreezeMissed() {
        assertEquals(0.0, params.recovered(5.6), 1e-9, "a quarter-second freeze gets nothing back")
        assertEquals(6.4, params.recovered(12.2), 1e-9)
        assertEquals(9.9, params.recovered(38.6), 1e-9, "a 1-3 s freeze misses 38.6 ticks and recovers 9.9")
        assertEquals(8.6, params.recovered(78.2), 1e-9)
        assertEquals(8.6, params.recovered(500.0), 1e-9, "past the last measured size the recovery stays flat")
        assertTrue(params.recovered(3.0) <= 3.0)
        val e = LagEvent(1980.0, 38, 10)
        assertEquals(28, e.lostTicks)
    }

    @Test fun noLossWithoutFreezes() {
        val g = LagGenerator(params, 0.0, Random(1))
        repeat(100_000) { assertEquals(null, g.nextTick()) }
    }

    @Test fun theSameSeedGivesTheSameFreezes() {
        fun run() = LagGenerator(params, 30.3, Random(77)).let { g -> (0 until 50_000).mapNotNull { i -> g.nextTick()?.let { i to it } } }
        assertEquals(run(), run())
    }

    @Test fun thePackOverridesTheDefaultsAndAMissingPackKeepsThem() {
        assertEquals(LagParams.DEFAULTS.exceedPerHour, LagParams.fromPack(null).exceedPerHour)
        val raw = JsonParser.parseString("""{"freezes":{"exceedPerHour":[{"gapMs":100,"perHour":500},{"gapMs":1000,"perHour":50}],
            "catchup":{"byGap":[{"missedTicks":10,"recoveredWithin5s":-1},{"missedTicks":40,"recoveredWithin5s":20}]},
            "clustering":{"within5sFrac":0.5,"randomExpected":0.2}}}""").asJsonObject
        val p = LagParams.fromPack(raw)
        assertEquals(listOf(100.0 to 500.0, 1000.0 to 50.0), p.exceedPerHour)
        assertEquals(listOf(10.0 to 0.0, 40.0 to 20.0), p.recovery, "a negative recovery reads as none")
        assertEquals(0.5, p.within5sFrac)
        // A broken section falls back to the defaults, never throws.
        assertEquals(LagParams.DEFAULTS.recovery, LagParams.fromPack(JsonParser.parseString("""{"freezes":{"exceedPerHour":"no"}}""").asJsonObject).recovery)
    }

    @Test fun thePresetLossComesFromThePackTiersWhenThereAreSome() {
        assertEquals(30.3, LagPresets.lossPerMinute(LagPreset.TYPICAL, null))
        val tiers = mapOf("F7" to LagTier(10.0, 20.0, 40.0, 25.0, 36.0, 1.8))
        assertEquals(10.0, LagPresets.lossPerMinute(LagPreset.GOOD, tiers)); assertEquals(36.0, LagPresets.lossPerMinute(LagPreset.HIGH_SLOT, tiers))
        assertEquals(0.0, LagPresets.lossPerMinute(LagPreset.OFF, tiers))
        assertEquals(54.6, LagPresets.lossPerMinute(LagPreset.HIGH_SLOT, mapOf("F7" to LagTier(1.0, 2.0, 3.0, null, null, null))), "a tier without a slot value keeps the measured one")
    }

    @Test fun theBurstPaceIsTheNormalIntervalOverTheRate() {
        assertEquals(25.0, LagBurst.sleepMs(0.0, 2.0), 1e-9)
        assertEquals(5.0, LagBurst.sleepMs(20.0, 2.0), 1e-9)
        assertEquals(0.0, LagBurst.sleepMs(40.0, 2.0), 1e-9, "a tick that already took longer needs no wait")
        assertEquals(50.0, LagBurst.sleepMs(0.0, 0.2), 1e-9, "a rate under 1 is not a slowdown")
        assertEquals(20, LagBurst.ticksFor(10, 2.0), "ten ticks gained at twice the pace take twenty burst ticks")
        assertEquals(15, LagBurst.ticksFor(10, 3.0))
        assertEquals(10, LagBurst.ticksFor(10, 1.0))
    }

    // ---- the timeline the layer leaves ------------------------------------------------------------------------------------------------

    private fun ms(n: Double) = (n * 1_000_000).toLong()

    @Test fun aFreezeThenBurstIsRecognisedInTheTimeline() {
        val stats = LagStats()
        var t = 0L
        repeat(40) { stats.record(t); t += ms(50.0) }           // a healthy second
        t += ms(1950.0); stats.record(t)                         // a 2 s freeze (the tick after it)
        repeat(20) { t += ms(25.0); stats.record(t) }            // twenty ticks at twice the pace: ten gained
        repeat(100) { t += ms(50.0); stats.record(t) }
        val r = stats.report()!!
        assertEquals(1, r.freezes.size)
        val f = r.freezes[0]
        assertEquals(2000.0, f.gapMs, 1.0)
        assertEquals(20, f.burstTicks); assertEquals(25.0, f.burstMeanMs, 0.5)
        assertEquals(39.0, f.missedTicks, 0.1)
        assertEquals(10.0, f.recoveredTicks, 0.2)
        assertEquals(10.0 / 39.0, r.recoveredShare, 0.01)
        val seconds = r.seconds
        assertEquals(seconds * 20 - r.ticks, r.lostTicks, 1e-6)
        assertTrue(r.lostTicks > 28, "about 29 ticks never came back: ${r.lostTicks}")
    }

    @Test fun aSteadyServerHasNoFreezesAndNoLoss() {
        val stats = LagStats()
        var t = 0L
        repeat(1200) { stats.record(t); t += ms(50.0) }
        val r = stats.report()!!
        assertTrue(r.freezes.isEmpty())
        assertEquals(0.0, r.lostTicks, 0.05)
        assertEquals(null, LagStats().report())
    }

    @Test fun theReportCanLookAtTheLastSecondsOnly() {
        val stats = LagStats()
        var t = 0L
        repeat(200) { stats.record(t); t += ms(50.0) }
        t += ms(1000.0); stats.record(t)                         // an old freeze...
        repeat(400) { t += ms(50.0); stats.record(t) }          // ...then 20 s of calm
        assertEquals(1, stats.report()!!.freezes.size)
        assertTrue(stats.report(10.0)!!.freezes.isEmpty())
    }
}
