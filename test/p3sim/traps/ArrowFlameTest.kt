package com.engineerclient.p3sim.clear.traps

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The arrow banks and flamethrowers: their beats, who they hit, and how `traps.json` describes them. */
class ArrowFlameTest {
    private val who = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val other = UUID.fromString("00000000-0000-4000-8000-000000000002")

    private fun bank(period: Int = 40, per: Int = 1) = ArrowRule("Test", period, per, listOf(Emitter(5, 70, 5, "south"), Emitter(6, 70, 5, "south")))

    @Test fun anArrowBankFiresOnItsBeatAndOnlyThen() {
        val b = ArrowBank(bank(40), 7)
        val fired = (1..200).filter { c -> HazardOut().also { b.tick(c, emptyList(), it) }.volleys.isNotEmpty() }
        assertEquals(listOf(33, 73, 113, 153, 193), fired, "every 40 ticks of the bank's clock, shifted by the phase")
        val out = HazardOut(); b.tick(33, emptyList(), out)
        assertEquals(2, out.volleys.single().dispensers.size, "one volley holds a shot from every dispenser")
        assertTrue(out.hits.isEmpty() && out.cues.isEmpty(), "the volley is silent and hurts nobody by itself")
    }

    @Test fun twoBanksWithDifferentPhasesDoNotBeatTogether() {
        val a = ArrowBank(bank(40), 0); val b = ArrowBank(bank(40), 11)
        val at = { x: ArrowBank -> (1..120).filter { c -> HazardOut().also { x.tick(c, emptyList(), it) }.volleys.isNotEmpty() } }
        assertEquals(listOf(40, 80, 120), at(a)); assertEquals(listOf(29, 69, 109), at(b))
    }

    private fun flames(emitters: List<Emitter>, region: IntArray? = null, min: Int = 1, max: Int = 1, pick: Double? = null, seed: Long = 5) =
        FlameBank(FlameRule("Test", emitters, region, min, max, pick), FlameStream(), seed)

    private fun player(x: Double, z: Double, id: UUID = who, y: Double = 70.0) = PlayerPos(id, x, y, z)

    /** Ticks [from]..[to] of a bank with the player given, the shots (cues) and hits it made, by tick. */
    private fun run(b: FlameBank, p: List<PlayerPos>, from: Int, to: Int): Pair<Map<Int, Int>, Map<Int, List<UUID>>> {
        val shots = HashMap<Int, Int>(); val hits = HashMap<Int, List<UUID>>()
        for (c in from..to) {
            val o = HazardOut(); b.tick(c, p, o)
            if (o.cues.isNotEmpty()) shots[c] = o.cues.size
            if (o.hits.isNotEmpty()) hits[c] = o.hits.map { it.player }
        }
        return shots to hits
    }

    @Test fun aFlamethrowerFiresOnAnEightTickBeatAndOnlyWhileSomeoneIsNear() {
        val e = listOf(Emitter(10, 70, 10, "east"))
        val near = run(flames(e), listOf(player(15.5, 20.5)), 1, 100).first
        assertTrue(near.size in 12..13, "one shot every 8 ticks (12 or 13 in 100): ${near.size}")
        assertTrue(near.keys.sorted().zipWithNext().all { (a, b) -> b - a == 8 })
        assertTrue(run(flames(e), listOf(player(15.5, 80.5)), 1, 100).first.isEmpty(), "nobody within 16 blocks: the emitter sleeps")
        assertTrue(run(flames(e), emptyList(), 1, 100).first.isEmpty())
    }

    @Test fun aPairOfEmittersAlternatesInsteadOfFiringTogether() {
        val e = listOf(Emitter(10, 70, 10, "east"), Emitter(10, 70, 14, "east"))
        val b = flames(e)
        val which = ArrayList<Double>()
        for (c in 1..80) { val o = HazardOut(); b.tick(c, listOf(player(14.5, 12.5)), o); o.cues.forEach { which += it.z } }
        assertTrue(which.size >= 9)
        assertTrue(which.zipWithNext().all { (a, b2) -> a != b2 }, "never the emitter that just fired when the other can: $which")
    }

    @Test fun aStreamHitsThePlayerInItOnceAndNobodyBesideIt() {
        // An emitter at (10,70,10) facing east: the stream runs x 11..17, z 9.5..11.5 (centre 10.5, 1 to each side), y 70..71.4.
        val e = listOf(Emitter(10, 70, 10, "east"))
        val b = flames(e)
        val inStream = player(14.5, 10.5); val beside = player(14.5, 13.5, other); val above = player(14.5, 10.5, other, y = 73.0)
        val (shots, hits) = run(b, listOf(inStream, beside, above), 1, 60)
        assertTrue(shots.isNotEmpty())
        assertTrue(hits.values.all { it == listOf(who) }, "only the player in the stream is hit: $hits")
        // Each shot hits that player once (a stream lives 5 ticks), one tick after the shot.
        val early = shots.keys.filter { it < 60 }
        assertEquals(early.size, hits.size)
        for (s in early) assertTrue((s + 1) in hits, "shot at $s hits at the next tick")
    }

    @Test fun streamBoxesFollowTheFacing() {
        val b = flames(listOf(Emitter(10, 70, 10, "east")))
        fun box(f: String) = b.box(Emitter(10, 70, 10, f)).map { Math.round(it * 100) / 100.0 }
        assertEquals(listOf(11.0, 70.0, 9.5, 17.0, 71.4, 11.5), box("east"))
        assertEquals(listOf(4.0, 70.0, 9.5, 10.0, 71.4, 11.5), box("west"))
        assertEquals(listOf(9.5, 70.0, 11.0, 11.5, 71.4, 17.0), box("south"))
        assertEquals(listOf(9.5, 70.0, 4.0, 11.5, 71.4, 10.0), box("north"))
        assertEquals(listOf(9.5, 71.0, 9.5, 11.5, 77.0, 11.5), box("up"))
    }

    @Test fun aRegionRuleWakesOnlyForAPlayerInsideTheBoxAndFiresOneToThreeNearOnes() {
        val ems = (0 until 8).map { Emitter(9, 64, 8 + it * 2, "east") }
        val region = intArrayOf(8, 64, 8, 20, 66, 24)
        val b = flames(ems, region, 1, 3, 9.0)
        assertTrue(run(b, listOf(player(15.5, 16.5, y = 69.0)), 1, 80).first.isEmpty(), "a player above the pit does not wake it")
        assertTrue(run(flames(ems, region, 1, 3, 9.0), listOf(player(30.5, 16.5, y = 64.0)), 1, 80).first.isEmpty(), "nor one outside the box")
        val shots = run(flames(ems, region, 1, 3, 9.0), listOf(player(15.5, 16.5, y = 64.0)), 1, 400).first
        assertEquals(50, shots.size)
        assertEquals(setOf(1, 2, 3), shots.values.toSet(), "one to three emitters a beat")
    }

    @Test fun aStreamThatLivesFiveTicksIsDrawnInFivePieces() {
        val b = flames(listOf(Emitter(10, 70, 10, "east")))
        val pieces = ArrayList<FlameFx>()
        var shotAt = -1
        for (c in 1..30) { val o = HazardOut(); b.tick(c, listOf(player(15.5, 20.5)), o); if (shotAt < 0 && o.cues.isNotEmpty()) shotAt = c; if (shotAt in 1..c) pieces += o.flames }
        // From the first shot on, each stream is drawn as five pieces tiling its 6 blocks.
        val first = pieces.take(5)
        assertEquals(listOf(0.0, 1.2, 2.4, 3.6, 4.8), first.map { Math.round(it.from * 10) / 10.0 })
        assertEquals(6.0, first.last().to, 1e-9)
    }

    @Test fun theFileDescribesBanksAndFlamesAndFallsBackToTheMeasuredDefaults() {
        val t = TrapTimings.parse("""{
          "arrows":[{"design":"Buttons","period":30,"perDispenser":1,"dispensers":[[21,70,54,"north"],[22,71,54,"north"],[1,1,1,"sideways"]]},
                    {"design":"Broken","period":0,"dispensers":[[1,1,1,"north"]]}],
          "flames":[{"design":"New_Trap","emitters":[[21,64,10,"west"]],"region":[8,64,8,20,66,24],"perBeat":[1,3],"pickRange":9.0},
                    {"design":"Slime","emitters":[[72,68,22,"east"]]}],
          "flameStream":{"reach":5.0},
          "arrowFlight":{"range":40.0}
        }""")
        val a = t.sites.arrows.single()
        assertEquals("Buttons", a.design); assertEquals(30, a.period); assertEquals(2, a.dispensers.size, "the dispenser with no real facing is dropped")
        assertEquals(listOf(0, 0, -1), listOf(a.dispensers[0].dx, a.dispensers[0].dy, a.dispensers[0].dz))
        val (pit, slime) = t.sites.flames
        assertEquals(1, pit.perBeatMin); assertEquals(3, pit.perBeatMax); assertEquals(9.0, pit.pickRange); assertEquals(listOf(8, 64, 8, 20, 66, 24), pit.region!!.toList())
        assertEquals(1, slime.perBeatMin); assertEquals(1, slime.perBeatMax); assertEquals(null, slime.region)
        assertEquals(5.0, t.sites.flameStream.reach); assertEquals(8, t.sites.flameStream.period); assertEquals(1.0, t.sites.flameStream.halfWidth)
        assertEquals(40.0, t.sites.arrowFlight.range); assertEquals(1.5f, t.sites.arrowFlight.speed)
        assertTrue(TrapTimings.parse("{}").sites.arrows.isEmpty() && TrapTimings.parse("{}").sites.flames.isEmpty())
    }
}
