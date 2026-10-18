package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.runstate.Dist
import com.engineerclient.p3sim.pack.runstate.RunStatePack
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The run-state tables loader and the percentile sampling rule of FORMAT-runstate.md 2.2, on invented numbers. */
class RunStatePackTest {
    @Test fun sampleIsPiecewiseLinear() {
        val d = Dist(100, doubleArrayOf(5.0, 25.0, 50.0, 75.0, 95.0), doubleArrayOf(10.0, 20.0, 40.0, 60.0, 100.0), null, null)
        assertEquals(40.0, d.median)
        assertEquals(30.0, d.sample(0.375))      // halfway between 25 and 50 percent -> 20 + (40-20)/2
        assertEquals(10.0, d.sample(0.0))        // below the first percent, no min: clamp to q[0]
        assertEquals(100.0, d.sample(1.0))       // above the last, no max: clamp
        assertEquals(10.0, d.at(5.0)); assertEquals(100.0, d.at(95.0))
    }

    @Test fun minAndMaxExtendTheTails() {
        val d = Dist(100, doubleArrayOf(5.0, 95.0), doubleArrayOf(10.0, 100.0), 0.0, 200.0)
        assertEquals(0.0, d.sample(0.0))
        assertEquals(5.0, d.at(2.5))             // halfway from min 0 at p0 to q0 10 at p5
        assertEquals(200.0, d.sample(1.0))
        assertEquals(150.0, d.at(97.5))          // halfway from 100 at p95 to 200 at p100
    }

    @Test fun adviceForSmallSamples() {
        assertTrue(Dist(7, doubleArrayOf(50.0), doubleArrayOf(1.0), null, null).advisory)
        assertTrue(!Dist(8, doubleArrayOf(50.0), doubleArrayOf(1.0), null, null).advisory)
    }

    @Test fun parseNeedsMatchingLengths() {
        val g = Dist.DEFAULT_PCT
        assertNotNull(Dist.parse(Fixtures.json("""{"n":3,"q":[1,2,3,4,5,6,7]}"""), g))
        assertNull(Dist.parse(Fixtures.json("""{"n":3,"q":[1,2,3]}"""), g), "7 percents but 3 values")
        assertNotNull(Dist.parse(Fixtures.json("""{"n":3,"pct":[5,50,95],"q":[1,2,3]}"""), g))
        assertNull(Dist.parse(null, g))
    }

    private val text = """
    {"format":"ads-runstate","version":1,"mc":"26.3","pct":[5,10,25,50,75,90,95],
     "rooms":{"Test Room":{"type":"normal","shape":"1x2","tiles":2,"secretsMax":4,"secretsDb":3,"clear":"mob",
              "dur":{"n":100,"q":[10,20,30,40,50,60,70]},"byClass":{"MAGE":{"n":9,"pct":[5,50,95],"q":[1,2,3]}},"toWhite":null}},
     "doors":{"volume":{"w":3,"h":4,"d":3,"y0":69},"fall":{"barrierTicks":14,"barrierTicksQ":[12,13,14,15,16]},
              "entrance":{"startLeadTicks":null,"freeFairyDoorWithinTicks":2},"witherPerLayout":{"n":5,"counts":{"3":2,"4":3}},
              "stand":{"witherY":68.531,"bloodY":67.531,"nameWither":"W","nameBlood":"B"},"noKeyMessage":{"chat":"no key","sound":{"id":"x.y","vol":8.0,"pitch":0.0}}},
     "splits":{"F7":{"bloodOpen":{"n":10,"pct":[5,25,50,75,95],"q":[100,200,300,400,500]},"perType":{"NORMAL":{"greenFrac":0.7}}}},
     "checkStates":{"types":{"normal":{"paths":{"UNWG":5},"ticks":{"NtoW":{"n":10,"q":[1,2,3,4,5,6,7]}}}},"greenNeedsAllSecrets":true},
     "counters":{"totalUnits":36,"excludeEntrance":true,"tabRefreshTicks":20},
     "score":{"periodTicks":200,"firstUpdateTick":200,"terms":{"secret":{"maxSecretPoints":40,"secretsPctDecimals":1},"rooms":{"table":[0,0,1.5],"interp":"linear","rounding":"floor"},
              "deaths":{"perDeathFormula":"max(0, 2*d - 1)"},"puzzles":{"perUnsolved":null},"bonus":{"crypts":{"w":1.0,"cap":5},"weightsFitted":false},"speed":{"pointsSec":[[600,0],[900,-28]],"fitted":false},"constant":0.0}},
     "timing":{"roomTypeDur":{"NORMAL":{"n":50,"pct":[5,25,50,75,95],"q":[1,2,3,4,5]}},"classRoles":{"n":10,"TANK":{"roomsOwnedMean":1.4,"puzzlesOwnedMean":0.0,"doorOpenerFrac":0.1}},"puzzleSolve":{}},
     "puzzles":{"countOdds":{"n":10,"counts":{"2":4,"3":6}},"pool":[{"code":"BOULDER","rooms":["Boulder"],"mapShare":0.3},{"code":"BLAZE","rooms":["A","B"],"mapShare":0.5,"exclusivePair":true}]},
     "chatFormats":["{name} did a thing"],
     "sbStats":{"presets":[{"id":"p1","label":"One","floor":7,"maxHp":10000,"defense":3000,"maxMana":9000,"speed":700,"vitality":null}]},
     "damage":{"trapPctOfMaxHp":0.15,"defenseFormula":"raw*100/(100+defense)"},
     "lag":{"typicalLossPerMin":30.3,"tiers":{"F7":{"good":14.5,"typical":30.3,"bad":56.6,"slotOver200":54.6,"slotMultiplier":1.8}},"runLevelModel":{"median":30.3,"sigma":0.5},
            "freezes":{"exceedPerHour":[{"gapMs":100,"perHour":1000},{"gapMs":1000,"perHour":20}],"tickIntervalMsQ":{"pct":[10,50],"q":[25,50]},"catchup":{"recoverFracWithin5s":0.23,"burstRateTicksPerSec":null}}},
     "constants":{"UNMEASURED":[{"name":"START_LEAD_TICKS","value":20,"why":"unmeasured"},{"name":"FLAG","value":true,"why":null}]}}
    """.trimIndent()

    @Test fun parsesEverySection() {
        val p = assertNotNull(RunStatePack.fromJson(Fixtures.json(text)))
        assertTrue(p.problems.isEmpty(), p.problems.toString())
        val r = p.room("Test Room")!!
        assertEquals(4, r.secretsMax); assertEquals(3, r.secretsDb); assertEquals(2, r.tiles); assertEquals(40.0, r.dur!!.median)
        assertEquals(3, r.byClass["MAGE"]!!.q.size); assertNull(r.toWhite)
        val d = p.doors!!
        assertEquals(14, d.barrierTicks); assertEquals(5, d.barrierTicksQ.size); assertNull(d.startLeadTicks); assertEquals(68.531, d.standWitherY)
        assertEquals("no key", d.noKeyChat); assertEquals(8.0, d.noKeySoundVolume); assertEquals(mapOf(3 to 2, 4 to 3), d.witherPerLayoutCounts)
        assertEquals(300.0, p.splits["F7"]!!.bloodOpen!!.median)
        assertNull(p.splits["F7"]!!.maxorEntry)
        assertEquals(5, p.checkStates!!.types["normal"]!!.paths["UNWG"]); assertEquals(4.0, p.checkStates!!.types["normal"]!!.ticks["NtoW"]!!.median)
        assertEquals(36, p.counters!!.totalUnits)
        val s = p.score!!
        assertEquals(40.0, s.maxSecretPoints); assertEquals(3, s.roomsTable.size); assertNull(s.puzzlePenaltyPerUnsolved); assertEquals(listOf(600.0 to 0.0, 900.0 to -28.0), s.speedPointsSec)
        assertEquals(5, s.cryptCap)
        assertEquals(3.0, p.roomTypeDur["NORMAL"]!!.median)
        assertEquals(0.1, p.classRoles!!.classes["TANK"]!!.doorOpenerFrac)
        assertEquals(mapOf(2 to 4, 3 to 6), p.puzzles!!.counts); assertTrue(p.puzzles!!.pool[1].exclusivePair)
        assertEquals(1, p.sbStatPresets.size); assertEquals(10000, p.sbStatPresets[0].maxHp)
        assertEquals(0.15, p.damage!!.trapPctOfMaxHp)
        val lag = p.lag!!
        assertEquals(14.5, lag.tiers["F7"]!!.good); assertEquals(2, lag.freezeExceedPerHour.size); assertEquals(0.23, lag.recoverFracWithin5s); assertNull(lag.burstRateTicksPerSec)
        assertEquals(20.0, p.constant("START_LEAD_TICKS")!!.value); assertEquals(true, p.constant("FLAG")!!.value)
    }

    @Test fun aMalformedSectionOnlyDisablesItself() {
        val broken = text.replace(""""counters":{"totalUnits":36,"excludeEntrance":true,"tabRefreshTicks":20},""", """"counters":"not an object",""")
            .replace(""""damage":{"trapPctOfMaxHp":0.15,"defenseFormula":"raw*100/(100+defense)"},""", "")
        val r = PackReport()
        val p = assertNotNull(RunStatePack.fromJson(Fixtures.json(broken), r))
        assertNull(p.counters); assertNull(p.damage)
        assertNotNull(p.score); assertNotNull(p.doors)
        assertEquals(2, p.problems.size, p.problems.toString())
        assertTrue(r.warnings().any { it.contains("counters") })
    }

    @Test fun readsTheChestThatFinishesATrapRoom() {
        val withChests = text.replace(""""constants":{"UNMEASURED":""", """"chestClears":{"rule":"x","rooms":{
            "Test Trap":{"secret":"s2","cell":[26,90,14],"visitsWithWhite":27,"afterOpenTicks":{"n":26,"q":[0,0,1,2,4,4,4],"min":0,"max":5}},
            "No Cell":{"secret":"s9"}}},
            "constants":{"UNMEASURED":""")
        val p = assertNotNull(RunStatePack.fromJson(Fixtures.json(withChests)))
        assertTrue(p.problems.isEmpty(), p.problems.toString())
        val c = assertNotNull(p.chestClear("Test Trap"))
        assertEquals("s2", c.secret); assertEquals(Pos(26, 90, 14), c.cell); assertEquals(2.0, c.afterOpen!!.median); assertEquals(27, c.visits)
        assertNull(p.chestClear("Test Trap2")); assertNotNull(p.chestClears["No Cell"]); assertNull(p.chestClears["No Cell"]!!.cell)
    }

    @Test fun aPackWithoutTheChestSectionStillLoads() {
        val p = assertNotNull(RunStatePack.fromJson(Fixtures.json(text)))
        assertTrue(p.chestClears.isEmpty()); assertNull(p.chestClear("Test Trap"))
        assertTrue(p.problems.isEmpty(), "the section is optional and its absence is not a problem")
    }

    @Test fun refusesWrongFormat() {
        val r = PackReport()
        assertNull(RunStatePack.fromJson(Fixtures.json("""{"format":"ads-mobs","version":1}"""), r))
        assertTrue(r.hasErrors())
    }
}
