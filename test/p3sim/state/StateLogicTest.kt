package com.engineerclient.p3sim.state

import com.engineerclient.p3sim.clear.state.*
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.runstate.Dist
import com.engineerclient.p3sim.run.*
import com.engineerclient.p3sim.run.api.KeyPlan
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure pieces: the schedule, the key plan and ledger, the door fall, the check-mark rules, the rules from the pack. */
class StateLogicTest {
    @Test fun scheduleFiresEachItemOnceInOrder() {
        val s = TickSchedule<String>()
        s.add(5, "b"); s.add(3, "a"); s.add(5, "c"); s.add(9, "d")
        assertEquals(listOf("a"), s.due(0, 4))
        assertEquals(listOf("b", "c"), s.due(4, 5), "same tick keeps insertion order")
        assertEquals(emptyList(), s.due(5, 8))
        assertEquals(listOf("d"), s.due(8, 20))
        assertEquals(listOf("a", "b", "c", "d"), s.due(-100, 100), "a replay after a restore fires everything in the window again")
    }

    @Test fun keyPlanSkipsEntranceFairyAndTheBloodRoom() {
        val kp = KeyPlans.of(StatePlans.plan())
        assertEquals(listOf(1, 2, 4), kp.witherRooms, "A, B and C; not the entrance, the Fairy room, D or blood")
        assertEquals(5, kp.bloodRoom, "the room right before the blood room gives the blood key")
        assertEquals(KeyKind.BLOOD, kp.keyFor(5)); assertEquals(KeyKind.WITHER, kp.keyFor(4)); assertNull(kp.keyFor(3)); assertNull(kp.keyFor(0)); assertNull(kp.keyFor(6))
    }

    @Test fun paidDoorsEqualWitherKeys() {
        val plan = StatePlans.plan()
        val paid = plan.doors.count { it.kind == DoorKind.WITHER }
        assertEquals(paid, KeyPlans.of(plan).witherRooms.size, "one key per paid wither door, the free Fairy door costs none")
        assertEquals(1, plan.doors.count { it.kind == DoorKind.FAIRY })
    }

    @Test fun shortPathsHaveNoKeys() {
        val kp = KeyPlans.of(TestPlans.plan().let { DungeonPlanShort.cut(it) })
        assertEquals(emptyList(), kp.witherRooms); assertNull(kp.bloodRoom)
    }

    @Test fun ledgerCountsKeysAndDoors() {
        val keys = KeyState()
        val l = KeyLedger(keys, KeyPlan(listOf(1, 2), 3))
        assertFalse(l.has(KeyKind.WITHER)); assertFalse(l.consume(KeyKind.WITHER))
        l.pick(KeyKind.WITHER); l.pick(KeyKind.WITHER)
        assertEquals(2, keys.witherHeld)
        assertTrue(l.consume(KeyKind.WITHER))
        assertEquals(1, keys.witherHeld); assertEquals(1, keys.witherUsed); assertEquals(2, keys.witherObtained)
        assertTrue(l.consistent())
        l.pick(KeyKind.BLOOD)
        assertTrue(keys.bloodHeld && l.consume(KeyKind.BLOOD) && !keys.bloodHeld && keys.bloodUsed)
        assertFalse(l.consume(KeyKind.BLOOD))
        assertTrue(l.consistent())
    }

    @Test fun eachRoomDropsItsKeyOnce() {
        val l = KeyLedger(KeyState(), KeyPlan(listOf(1), 2))
        assertEquals(KeyKind.WITHER, l.pending(1)); assertTrue(l.markDropped(1))
        assertNull(l.pending(1)); assertFalse(l.markDropped(1)); assertFalse(l.markDropped(9))
        assertEquals(KeyKind.BLOOD, l.pending(2))
    }

    @Test fun doorBarrierLastsFourteenTicksThenAir() {
        val f = DoorFalls()
        assertEquals(listOf(FallStep(3, BodyBlocks.BARRIER)), f.begin(3, 100, 14))
        assertTrue(f.begin(3, 101, 14).isEmpty(), "a door already falling is not started again")
        assertEquals(114, f.airAt(3))
        assertTrue(f.due(113).isEmpty())
        assertEquals(listOf(FallStep(3, BodyBlocks.AIR)), f.due(114))
        assertFalse(f.isFalling(3)); assertTrue(f.due(200).isEmpty())
    }

    @Test fun aStaggeredFallWritesTheFirstBlockThenTheRest() {
        val f = DoorFalls()
        assertEquals(listOf(FallStep(1, BodyBlocks.BARRIER, onlyFirst = true)), f.begin(1, 10, 14, firstBlockToRestTicks = 1))
        assertEquals(listOf(FallStep(1, BodyBlocks.BARRIER)), f.restDue(11, 10))
        assertTrue(f.restDue(12, 11).isEmpty())
        assertEquals(listOf(FallStep(1, BodyBlocks.AIR)), f.due(25))
    }

    @Test fun checkRules() {
        assertEquals(CheckMark.GREEN, RoomChecks.onDiscovered(RoomType.FAIRY))
        assertEquals(CheckMark.NONE, RoomChecks.onDiscovered(RoomType.NORMAL))
        assertEquals(CheckMark.GREEN, RoomChecks.onObjectiveDone(0, 0), "no secrets: straight to green")
        assertEquals(CheckMark.WHITE, RoomChecks.onObjectiveDone(4, 3))
        assertEquals(CheckMark.GREEN, RoomChecks.onObjectiveDone(4, 4), "all secrets already found")
        assertTrue(RoomChecks.secretsComplete(2, 2)); assertFalse(RoomChecks.secretsComplete(0, 0)); assertFalse(RoomChecks.secretsComplete(2, 1))
    }

    @Test fun rulesDefaultToTheMeasuredFigures() {
        val r = StateRules.fromPack(null)
        assertEquals(20, r.startLeadTicks); assertEquals(22, r.entranceFallAfterStart1); assertEquals(-1, r.freeDoorOffset)
        assertEquals(14, r.barrierFor(DoorKind.WITHER)); assertEquals(14, r.barrierFor(DoorKind.BLOOD)); assertEquals(15, r.barrierFor(DoorKind.ENTRANCE))
        assertEquals(50, r.whiteDelay("Anything", "NORMAL", 0.5), "no table: the pooled median")
    }

    @Test fun rulesReadThePack() {
        val json = com.engineerclient.p3sim.pack.Fixtures.json("""
          {"format":"ads-runstate","version":1,"pct":[5,10,25,50,75,90,95],
           "doors":{"fall":{"barrierTicks":14,"firstBlockToRestTicks":0,
                      "barrierTicksByKind":{"wither":{"ticks":{"14":0.3,"15":0.7}},"entrance":{"ticks":{"16":1.0}}}},
                    "entrance":{"startLeadTicks":21,"fallAfterStart1Ticks":{"n":50,"q":[24,24,24,25,25,25,25]},
                                "freeFairyOffset":{"n":9,"pct":[5,25,50,75,95],"q":[-3,-3,-2,-2,-2]}},
                    "pickup":{"instantFrac":0.5,"autoCollectTicks":100}},
           "checkStates":{"types":{"normal":{"paths":{},"ticks":{"NtoW":{"n":99,"q":[10,20,30,40,50,60,70]}}}}}}""")
        val rules = StateRules.fromPack(com.engineerclient.p3sim.pack.runstate.RunStatePack.fromJson(json))
        assertEquals(21, rules.startLeadTicks); assertEquals(25, rules.entranceFallAfterStart1); assertEquals(-2, rules.freeDoorOffset)
        assertEquals(15, rules.barrierFor(DoorKind.WITHER), "the most common figure of the table"); assertEquals(16, rules.barrierFor(DoorKind.ENTRANCE))
        assertEquals(14, rules.barrierFor(DoorKind.BLOOD), "a kind the table lacks keeps the default")
        assertEquals(0.5, rules.pickupInstantFrac); assertEquals(100, rules.autoCollectTicks)
        assertEquals(40, rules.whiteDelay("Unlisted", "NORMAL", 0.5), "the normal-room NONE to WHITE median")
    }

    @Test fun theFindersRankSetsTheBracketAndTheNameColour() {
        assertEquals("§a[VIP] Ann§f §ehas obtained §8Wither Key§e!", StateText.keyObtained(KeyKind.WITHER, "Ann", "§a[VIP]"))
        assertEquals("[VIP] Ann has obtained Wither Key!", StateText.plain(StateText.keyObtained(KeyKind.WITHER, "Ann", "§a[VIP]")))
        assertTrue(StateText.witherOpened("Ann", "§6").startsWith("§6Ann §a"), "the opener's own colour")
        assertEquals(StateText.keyObtained(KeyKind.BLOOD, "Ann"), StateText.keyObtained(KeyKind.BLOOD, "Ann", StateText.KEY_RANK_PREFIX), "the default is the commonest rank")
    }

    @Test fun textMatchesHypixelsPlainLines() {
        assertEquals("Ann opened a WITHER door!", StateText.plain(StateText.witherOpened("Ann")))
        assertEquals("[MVP+] Ann has obtained Wither Key!", StateText.plain(StateText.keyObtained(KeyKind.WITHER, "Ann")))
        assertEquals("[MVP+] Ann has obtained Blood Key!", StateText.plain(StateText.keyObtained(KeyKind.BLOOD, "Ann")))
        assertEquals("RIGHT CLICK on a WITHER door to open it. This key can only be used to open 1 door!", StateText.plain(StateText.rightClick(KeyKind.WITHER)))
        assertEquals("RIGHT CLICK on the BLOOD DOOR to open it. This key can only be used to open 1 door!", StateText.plain(StateText.rightClick(KeyKind.BLOOD)))
        assertTrue(StateText.rightClick(KeyKind.BLOOD).startsWith("§e§lRIGHT CLICK "), "the instruction opens bold yellow")
        assertTrue(StateText.rightClick(KeyKind.WITHER).contains("§a1§7 door!"), "the count is green")
        assertTrue(StateText.witherOpened("Ann").startsWith("§bAnn §a"), "the opener's name is aqua")
        assertEquals(CueSource.HOSTILE, StateText.BLOOD_DOOR.source); assertEquals(CueSource.BLOCKS, StateText.DOOR_OPENED.source)
        assertEquals(CueSource.PLAYERS, StateText.KEY_SHUTTER.source)
        assertEquals("The BLOOD DOOR has been opened!", StateText.plain(StateText.BLOOD_OPENED))
        assertEquals("A shiver runs down your spine...", StateText.plain(StateText.SHIVER))
        assertEquals("You do not have the key for this door!", StateText.plain(StateText.NO_KEY_LINE))
        assertEquals("Starting in 1 second.", StateText.plain(StateText.startingIn(1)))
        assertEquals("Starting in 4 seconds.", StateText.plain(StateText.startingIn(4)))
        assertEquals("Ann is now ready!", StateText.plain(StateText.ready("Ann")))
    }
}

/** A test helper: the same plan with a path of two rooms (no key rooms). */
private object DungeonPlanShort {
    fun cut(p: DungeonPlan) = DungeonPlan(p.layoutId, p.floor, p.seed, p.rooms, p.doors, listOf(0, 1), p.recordedKeys, p.puzzleNames)
}
