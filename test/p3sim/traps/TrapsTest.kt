package com.engineerclient.p3sim.clear.traps

import com.engineerclient.p3sim.run.RoomType
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrapsTest {
    private val timing = CrusherTiming(5, 30)

    private fun crusher(sweep: Int = 3, t: CrusherTiming = timing) = Crusher("x/crusher-1", intArrayOf(10, 70, 5, 10, 71, 6), 'x', -1, sweep, "minecraft:polished_granite", t)

    @Test fun aCrusherStepsOutAtOnceWhenTheRoomWakesThenBackAndRestsBeforeTheNextCycle() {
        val c = crusher(3)
        assertEquals((2 * 3 - 1) * 5 + 30, timing.period(3))
        val period = timing.period(3)
        val s = Crusher.START_LAG
        // At rest until the room's 4th tick, then one layer every 5 ticks out to 3, and back to 0 on the last move.
        assertEquals(List(s) { 0 }, (0 until s).map { c.extensionAt(it) })
        assertEquals(1, c.extensionAt(s)); assertEquals(1, c.extensionAt(s + 4)); assertEquals(2, c.extensionAt(s + 5)); assertEquals(3, c.extensionAt(s + 10))
        assertEquals(2, c.extensionAt(s + 15)); assertEquals(1, c.extensionAt(s + 20)); assertEquals(0, c.extensionAt(s + 25))
        // The rest is 30 ticks from the last move back to the next move out.
        assertEquals(0, c.extensionAt(s + 25 + 29)); assertEquals(1, c.extensionAt(s + 25 + 30))
        assertEquals(1, c.extensionAt(s + period), "the cycle repeats")
        assertEquals(c.extensionAt(s + 11), c.extensionAt(s + 11 + period))
    }

    @Test fun aCrusherThatNeverLingersHasTheSameNumberForMoveAndRest() {
        val c = crusher(2, CrusherTiming(5, 5))
        assertEquals((2 * 2 - 1) * 5 + 5, c.timing.period(2))
        assertEquals(1, c.extensionAt(Crusher.START_LAG)); assertEquals(2, c.extensionAt(Crusher.START_LAG + 5))
        assertEquals(1, c.extensionAt(Crusher.START_LAG + 10)); assertEquals(0, c.extensionAt(Crusher.START_LAG + 15))
        assertEquals(1, c.extensionAt(Crusher.START_LAG + 20))
    }

    @Test fun aCrusherGrowsLayersAndNeverRemovesItsHomeSlab() {
        val c = crusher(3)
        assertEquals(0, c.cells(0).size)
        assertEquals(4, c.cells(1).size, "layer 1 only: the slab is 2 x 2 cells")
        assertEquals(12, c.cells(3).size)
        assertEquals(listOf(9, 70, 5), c.cells(1)[0].toList(), "layer 1 is one cell out from the slab (x = 10) along the sign")
        // Growing adds only the next layer; the earlier ones stay.
        val grow = c.edits(1, 2)
        assertEquals(setOf("8,70,5", "8,70,6", "8,71,5", "8,71,6"), grow.map { "${it.x},${it.y},${it.z}" }.toSet())
        assertTrue(grow.all { it.state == "minecraft:polished_granite" })
        // Shrinking clears only the tip layer.
        val shrink = c.edits(3, 2)
        assertEquals(setOf("7,70,5", "7,70,6", "7,71,5", "7,71,6"), shrink.map { "${it.x},${it.y},${it.z}" }.toSet())
        assertTrue(shrink.all { it.state == "minecraft:air" })
        assertTrue(c.edits(1, 1).isEmpty())
        // The first step out never touches the slab's own cells (x = 10).
        assertTrue(c.edits(0, 1).none { it.x == 10 })
        // Out three and back to nothing restores exactly the cells that were placed.
        val out = c.edits(0, 3); val back = c.edits(3, 0)
        assertEquals(out.map { "${it.x},${it.y},${it.z}" }.toSet(), back.map { "${it.x},${it.y},${it.z}" }.toSet())
        assertEquals(12, out.size)
    }

    @Test fun anArmIsOneSolidBoxFromTheFirstLayerToTheTip() {
        val c = crusher(3)
        assertEquals(listOf(8, 70, 5, 9, 71, 6), c.armBox(2).toList(), "x = 9 and 8 for a crusher that grows toward -x from x = 10")
        val east = Crusher("e", intArrayOf(0, 60, 4, 1, 61, 5), 'x', 1, 5, "minecraft:stone", timing)
        assertEquals(listOf(1, 60, 4, 4, 61, 5), east.armBox(3).toList(), "a slab at x 0..1 growing toward +x covers x 1..4 at three layers")
        for (k in 1..3) {
            val box = c.armBox(k)
            val cells = c.cells(k).map { "${it[0]},${it[1]},${it[2]}" }.toSet()
            val inBox = (box[0]..box[3]).flatMap { x -> (box[1]..box[4]).flatMap { y -> (box[2]..box[5]).map { z -> "$x,$y,$z" } } }.toSet()
            assertEquals(cells, inBox, "k = $k")
        }
    }

    @Test fun theTipFaceIsWhereTheArmSlams() {
        val c = Crusher("w", intArrayOf(22, 69, 10, 22, 76, 12), 'x', -1, 11, "minecraft:polished_granite", timing)
        val f = c.tipFace(11)
        assertEquals(11.0, f[0]); assertEquals(69.0, f[1]); assertEquals(11.5, f[2])
        val z = Crusher("z", intArrayOf(18, 81, 23, 21, 86, 23), 'z', -1, 14, "minecraft:polished_granite", timing)
        val g = z.tipFace(14)
        assertEquals(20.0, g[0]); assertEquals(9.0, g[2])
    }

    @Test fun hooksPairIntoLinesAcrossTheirGap() {
        val lines = TripLine.pair(listOf(Hook(1, 81, 10, "east"), Hook(9, 81, 10, "west"), Hook(16, 81, 0, "south"), Hook(16, 81, 6, "north"), Hook(3, 70, 3, "east")))
        assertEquals(2, lines.size, "a lone hook makes no line")
        val x = lines.first { it.axis == 'x' }
        assertEquals(1, x.from); assertEquals(9, x.to); assertEquals(10, x.fixed); assertEquals(81, x.y)
        val z = lines.first { it.axis == 'z' }
        assertEquals(16, z.fixed); assertEquals(0, z.from); assertEquals(6, z.to)
    }

    @Test fun aPlayerWalkingAcrossTheStringTouchesItAndOneJumpingOverDoesNot() {
        val l = TripLine('x', 81, 10, 1, 9)
        assertTrue(l.touches(5.5, 81.0, 10.5))
        assertTrue(!l.touches(5.5, 81.4, 10.5), "feet above the string")
        assertTrue(!l.touches(5.5, 81.0, 12.5), "beside it")
        assertTrue(!l.touches(0.5, 81.0, 10.5), "past the hook")
        assertTrue(!l.touches(5.5, 79.0, 10.5), "a floor below: the head is under the string")
    }

    private val me = UUID(0, 1)

    @Test fun aCrusherThatReachesAPlayerHitsThemEveryTickItStaysThere() {
        val h = RoomHazards(listOf(crusher(3)), emptyList())
        val standing = PlayerPos(me, 8.5, 70.0, 5.5) // x = 8 is layer 2 of an arm that starts beside x = 10
        val hitTicks = ArrayList<Int>()
        for (t in 1..100) {
            val r = h.tick(listOf(standing))
            if (r.hits.any { it.player == me && it.kind == HazardKind.CRUSHER }) hitTicks += t
        }
        val s = Crusher.START_LAG
        assertTrue(hitTicks.isNotEmpty(), "the arm came through x = 8")
        assertEquals(s + 5, hitTicks.first(), "layer 2 lands 5 ticks after the first step")
        assertEquals((s + 5..s + 5 + 14).toList() + (s + 5 + 55..s + 5 + 55 + 14).toList(), hitTicks, "layer 2 stands from its step out to its step back (15 ticks), once a cycle (55 ticks)")
    }

    @Test fun aPlayerStandingBehindTheFrontOfAnArmIsHitToo() {
        val h = RoomHazards(listOf(crusher(3)), emptyList())
        val behind = PlayerPos(me, 9.5, 70.0, 5.5) // layer 1 stands from the first step to the last step back
        val hitTicks = (1..50).filter { t -> h.tick(listOf(behind)).hits.isNotEmpty() }
        assertEquals((Crusher.START_LAG..Crusher.START_LAG + 24).toList(), hitTicks)
    }

    @Test fun aPlayerInTheHomeSlabIsNotTheArmsToHit() {
        val h = RoomHazards(listOf(crusher(3)), emptyList())
        val inSlab = PlayerPos(me, 10.5, 70.0, 5.5)
        for (t in 1..120) assertTrue(h.tick(listOf(inSlab)).hits.isEmpty(), "the slab is the arm's home, not part of the arm")
    }

    @Test fun anArmReachingFullExtensionSlamsOnceAtItsTip() {
        val h = RoomHazards(listOf(crusher(3)), emptyList())
        val slams = ArrayList<Pair<Int, SoundCue>>()
        for (t in 1..120) h.tick(emptyList()).cues.filter { it.sound == HazardSound.CRUSHER_SLAM }.forEach { slams += t to it }
        assertEquals(listOf(Crusher.START_LAG + 10, Crusher.START_LAG + 10 + 55), slams.map { it.first }, "at the third layer, once a cycle")
        assertEquals(7.0, slams[0].second.x, "the face of the tip layer at x = 7")
        assertEquals(1.0f, HazardSound.CRUSHER_SLAM.volume)
        assertEquals(0.4920635f, HazardSound.CRUSHER_SLAM.pitch)
    }

    @Test fun aCrusherThatHitsAPlayerSlamsAtItsTipOncePerImmunityWindow() {
        val h = RoomHazards(listOf(crusher(3)), emptyList())
        val standing = PlayerPos(me, 9.5, 70.0, 5.5) // inside layer 1 from the first step out to the last step back
        val sounds = (1..30).filter { t -> h.tick(listOf(standing)).cues.any { it.sound == HazardSound.CRUSHER_SLAM } }
        val s = Crusher.START_LAG
        assertEquals(listOf(s, s + 10, s + 20), sounds, "at the first hit and again once the 10-tick window is over")
    }

    @Test fun aPlayerOutsideTheSweepIsNeverHit() {
        val h = RoomHazards(listOf(crusher(3)), emptyList())
        val far = PlayerPos(me, 3.5, 70.0, 5.5)
        for (t in 1..300) assertTrue(h.tick(listOf(far)).hits.isEmpty())
    }

    @Test fun aTripwireHitsTheTouchingPlayerAtOnceAndAgainEveryTenTicksWhileTheyStay() {
        val h = RoomHazards(emptyList(), listOf(TripLine('x', 81, 10, 1, 9)), linesHurt = true)
        val on = PlayerPos(me, 5.5, 81.0, 10.5)
        val hits = ArrayList<Int>()
        for (t in 1..40) if (h.tick(listOf(on)).hits.any { it.kind == HazardKind.TRIPWIRE && it.player == me }) hits += t
        assertEquals(listOf(1, 1 + RoomHazards.HOOK_TICKS, 1 + 2 * RoomHazards.HOOK_TICKS, 1 + 3 * RoomHazards.HOOK_TICKS), hits, "no delay, then re-armed when the hooks release")
    }

    @Test fun aTripwireHitsOnlyThePlayerWhoTouchedItNotOthersNearby() {
        val h = RoomHazards(emptyList(), listOf(TripLine('x', 81, 10, 1, 9)), linesHurt = true)
        val touching = PlayerPos(me, 5.5, 81.0, 10.5)
        val near = PlayerPos(UUID(0, 2), 5.5, 81.0, 11.4) // one block beside the string, not on it
        val r = h.tick(listOf(touching, near))
        assertEquals(listOf(me), r.hits.map { it.player })
    }

    @Test fun aPlayerWhoWalkedPastTheStringBeforeAnythingHappenedIsNotHitLater() {
        val h = RoomHazards(emptyList(), listOf(TripLine('x', 81, 10, 1, 9)), linesHurt = true)
        assertEquals(1, h.tick(listOf(PlayerPos(me, 5.5, 81.0, 10.5))).hits.size)
        for (t in 2..20) assertTrue(h.tick(listOf(PlayerPos(me, 5.5, 81.0, 20.5))).hits.isEmpty())
    }

    @Test fun aTripwireClicksItsHooksOnAtTheTouchAndOffTenTicksLater() {
        val h = RoomHazards(emptyList(), listOf(TripLine('x', 81, 10, 1, 9)), linesHurt = true)
        val on = PlayerPos(me, 5.5, 81.0, 10.5)
        val first = h.tick(listOf(on))
        val clicks = first.cues.filter { it.sound == HazardSound.LEVER_ON }
        assertEquals(2, clicks.size, "one at each hook")
        assertEquals(setOf(1.5, 9.5), clicks.map { it.x }.toSet()); assertTrue(clicks.all { it.y == 81.0 && it.z == 10.5 })
        assertEquals(1, first.cues.count { it.sound == HazardSound.TRIPWIRE_BLAST }, "the blast on the player")
        var off = -1
        for (t in 2..20) if (h.tick(emptyList()).cues.any { it.sound == HazardSound.LEVER_OFF }) { off = t; break }
        assertEquals(1 + RoomHazards.HOOK_TICKS, off)
        assertEquals(0.5873016f, HazardSound.LEVER_ON.pitch); assertEquals(0.4920635f, HazardSound.LEVER_OFF.pitch); assertEquals(0.4f, HazardSound.LEVER_ON.volume)
    }

    @Test fun aStringInARoomWhereRealOnesNeverHitDoesNothingAtAll() {
        val h = RoomHazards(emptyList(), listOf(TripLine('x', 81, 10, 1, 9)), linesHurt = false, linesClick = false)
        val on = PlayerPos(me, 5.5, 81.0, 10.5)
        for (t in 1..60) { val r = h.tick(listOf(on)); assertTrue(r.hits.isEmpty() && r.cues.isEmpty() && r.edits.isEmpty()) }
    }

    @Test fun anOldTrapStyleStringClicksButDoesNotHit() {
        val h = RoomHazards(emptyList(), listOf(TripLine('x', 81, 10, 1, 9)), linesHurt = false, linesClick = true)
        val r = h.tick(listOf(PlayerPos(me, 5.5, 81.0, 10.5)))
        assertTrue(r.hits.isEmpty()); assertEquals(2, r.cues.count { it.sound == HazardSound.LEVER_ON }); assertEquals(0, r.cues.count { it.sound == HazardSound.TRIPWIRE_BLAST })
    }

    @Test fun theClockSnapshotRestoresTheCrushersExtensions() {
        val h = RoomHazards(listOf(crusher(3)), emptyList())
        repeat(15) { h.tick(emptyList()) }
        val snap = h.snapshot()
        val g = RoomHazards(listOf(crusher(3)), emptyList())
        g.restore(snap)
        assertEquals(15, g.clock)
        assertEquals(12, g.currentEdits().size, "the arm is 3 layers out at tick 15, so its 12 cells are re-applied")
        assertTrue(g.currentEdits().all { it.state == "minecraft:polished_granite" })
    }

    @Test fun timingsFileOverridesTheDefaultPerCrusher() {
        val t = TrapTimings.parse("""{"format":"ads-traps","version":1,"default":{"ticksPerBlock":4,"stillTicks":24},"crushers":{"a/crusher-1":{"ticksPerBlock":2,"stillTicks":20},"a/crusher-2":{"ticksPerBlock":0,"stillTicks":9}}}""")
        assertEquals(2, t.forCrusher("a/crusher-1").ticksPerBlock)
        assertEquals(20, t.forCrusher("a/crusher-1").stillTicks)
        assertEquals(4, t.forCrusher("a/crusher-2").ticksPerBlock, "an impossible entry falls back to the file's default")
        assertEquals(24, t.forCrusher("nobody").stillTicks)
        assertEquals(5, TrapTimings.NONE.forCrusher("anything").ticksPerBlock)
        assertEquals(30, TrapTimings.NONE.forCrusher("anything").stillTicks)
    }

    @Test fun aSweepInTheTimingsFileReplacesThePacksSweep() {
        val t = TrapTimings.parse("""{"format":"ads-traps","version":1,"crushers":{"a/crusher-1":{"ticksPerBlock":5,"stillTicks":30,"sweep":13},"a/crusher-2":{"ticksPerBlock":5,"stillTicks":30,"sweep":0}}}""")
        assertEquals(13, t.forCrusher("a/crusher-1").sweep)
        assertEquals(null, t.forCrusher("a/crusher-2").sweep, "an impossible sweep is ignored")
        assertEquals(null, t.forCrusher("nobody").sweep)
    }

    @Test fun theOldTrapPincerIsTwoArmsOf13LayersWithAPeriodOf155ThatMoveTogether() {
        val t = CrusherTiming(5, 30, 13)
        assertEquals(155, t.period(13))
        // The pack's slabs: left arm from x = 1 growing +x, right arm from x = 28 growing -x, both y 81..84 by z 11..15 (20 cells a layer).
        val left = Crusher("trap-hard/crusher-2", intArrayOf(1, 81, 11, 1, 84, 15), 'x', 1, 13, "minecraft:polished_granite", t)
        val right = Crusher("trap-hard/crusher-1", intArrayOf(28, 81, 11, 28, 84, 15), 'x', -1, 13, "minecraft:polished_granite", t)
        for (clock in 0..400) assertEquals(left.extensionAt(clock), right.extensionAt(clock), "same step on the same tick at $clock")
        assertEquals(13, (0..400).maxOf { left.extensionAt(it) })
        assertEquals(listOf(2, 14, 15, 27), listOf(left.armBox(13)[0], left.armBox(13)[3], right.armBox(13)[0], right.armBox(13)[3]), "left x 2..14, right x 15..27")
        assertEquals(520, left.cells(13).size + right.cells(13).size)
        // The first step out lands at the room's 4th tick, the last step out 60 ticks later, then 13 steps back and the 30-tick rest.
        val firsts = (0..400).filter { left.extensionAt(it) == 1 && left.extensionAt(it - 1) == 0 }
        assertEquals(listOf(4, 159, 314), firsts)
        assertEquals(13, left.extensionAt(4 + 60))
        assertEquals(1, left.extensionAt(4 + 60 + 5 * 12)); assertEquals(0, left.extensionAt(4 + 60 + 5 * 13))
    }

    @Test fun theNewTrapLowCrushersRunOneOfTwoPacesDrawnOncePerRun() {
        val json = """{"format":"ads-traps","version":1,"variants":[{"startSpread":40,"options":[
            {"sharedStart":true,"crushers":{"r/crusher-4":{"ticksPerBlock":40,"stillTicks":40},"r/crusher-5":{"ticksPerBlock":40,"stillTicks":40}}},
            {"sharedStart":false,"crushers":{"r/crusher-4":{"ticksPerBlock":30,"stillTicks":30},"r/crusher-5":{"ticksPerBlock":20,"stillTicks":20}}}]}]}"""
        val t = TrapTimings.parse(json)
        assertEquals(1, t.variants.size)
        val seen = HashSet<Pair<Int, Int>>()
        var sharedSeen = false
        for (seed in 0 until 200) {
            val plan = t.roll(kotlin.random.Random(seed))
            val a = plan.timing("r/crusher-4"); val b = plan.timing("r/crusher-5")
            seen += a.ticksPerBlock to b.ticksPerBlock
            assertTrue(plan.startAt("r/crusher-4") in 0..40 && plan.startAt("r/crusher-5") in 0..40)
            if (a.ticksPerBlock == 40) { assertEquals(40, b.ticksPerBlock); assertEquals(plan.startAt("r/crusher-4"), plan.startAt("r/crusher-5"), "in lockstep at 40 / 40"); sharedSeen = true }
            else { assertEquals(30, a.ticksPerBlock); assertEquals(20, b.ticksPerBlock) }
            // The same seed repeats.
            assertEquals(plan.startAt("r/crusher-4"), t.roll(kotlin.random.Random(seed)).startAt("r/crusher-4"))
        }
        assertEquals(setOf(40 to 40, 30 to 20), seen)
        assertTrue(sharedSeen)
        // A crusher the variants do not name keeps the file's pace and the standard start.
        val plan = t.roll(kotlin.random.Random(1))
        assertEquals(5, plan.timing("other/crusher-1").ticksPerBlock)
        assertEquals(Crusher.START_LAG, plan.startAt("other/crusher-1"))
    }

    @Test fun onlyTheRoomsWhereRealTripwiresHitHurtByDefault() {
        val t = TrapTimings.NONE
        for (r in listOf("Deathmite", "Cathedral", "Painting", "Chambers", "Pit", "Melon", "Lava Ravine", "Hall", "Golden Oasis", "Altar", "Atlas", "Spider", "Red Green"))
            assertTrue(t.tripwiresHurtIn(r), r)
        for (r in listOf("Entrance", "Old Trap", "New Trap", "Waterfall", "Mines", "Flags", "Dino Site", "Rails", "Staircase", "Sarcophagus", "Ritual", "Purple Flags", "Grand Library", "Lava Pit", "Rare Overgrown", "Trinity"))
            assertTrue(!t.tripwiresHurtIn(r), r)
        assertTrue(t.tripwiresHurtIn("red green"), "the name compares without case")
        assertTrue(t.tripwiresClickIn("Old Trap") && !t.tripwiresClickIn("New Trap") && !t.tripwiresClickIn("Entrance"))
        val over = TrapTimings.parse("""{"format":"ads-traps","version":1,"tripwireHurtRooms":["Ritual"],"tripwireClickRooms":[]}""")
        assertTrue(over.tripwiresHurtIn("Ritual") && !over.tripwiresHurtIn("Deathmite") && !over.tripwiresClickIn("Old Trap"))
    }

    @Test fun hypixelsTripwireLineHasACommaAndOneDecimal() {
        assertEquals("§cThe Tripwire Trap hit you for 1,702.7 damage!", TrapsModule.tripwireLine(1702.65))
        assertEquals("§cThe Tripwire Trap hit you for 987 damage!", TrapsModule.tripwireLine(987.0), "a whole number drops the .0")
        assertEquals("§cThe Crusher hit you for 3,837.3 damage!", TrapsModule.crusherLine(3837.3))
        assertEquals("§cThe Arrow Trap hit you for 1,639 damage!", TrapsModule.arrowTrapLine(1639.0))
        assertEquals("§cThe Flamethrower hit you for 1,337.4 damage!", TrapsModule.flamethrowerLine(1337.4))
        assertEquals(30.0, TrapsModule.CRUSHER_PERCENT)
    }

    @Test fun abilitiesAreOffInTrapRoomsAndOnElsewhereByDefault() {
        assertTrue(TrapsModule.abilitiesOffIn(RoomType.TRAP))
        for (t in RoomType.entries) if (t != RoomType.TRAP) assertTrue(!TrapsModule.abilitiesOffIn(t), "$t")
    }
}
