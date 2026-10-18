package com.engineerclient.p3sim.clear.hud

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The action bar against the regexes of Odin's `ActionBarListener`. */
class ActionBarModelTest {
    private val health = Regex("([\\d|,]+)/([\\d|,]+)\\uE010")
    private val mana = Regex("([\\d|,]+)/([\\d|,]+)\\uE003")
    private val overflow = Regex("([\\d|,]+)\\uE017")
    private val defense = Regex("([\\d|,]+)\\uE008( Defense)?")
    private val vitality = Regex("([\\d.,]+)/([\\d.,]+)\\uE028")
    private val secrets = Regex("(\\d+)/(\\d+) Secrets")

    private fun bar(extras: List<BarSegment> = emptyList(), stats: HudStats = HudStats.DEFAULT, overflowMana: Int = ActionBarModel.OVERFLOW_MANA_DEFAULT): Pair<String, com.engineerclient.p3sim.run.RunState> {
        val st = HudFixtures.state(); HudFixtures.party(st)
        return HudFormat.strip(ActionBarModel.compose(stats, st.players[HudFixtures.me], st, extras, overflowMana = overflowMana)) to st
    }

    @Test fun baselineHasHealthDefenseAndMana() {
        val (s, _) = bar()
        assertEquals("11,351/11,351\uE010     3,681\uE008     13,444/13,444\uE003 600\uE017     127/127\uE028     ", s)
        assertEquals(listOf("11,351", "11,351"), health.find(s)!!.destructured.toList())
        assertEquals("3,681", defense.find(s)!!.groupValues[1])
        assertEquals("13,444", mana.find(s)!!.groupValues[1])
    }

    @Test fun healthFollowsThePercentLedger() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        st.players[HudFixtures.me]!!.hpPercent = 25.0
        val s = HudFormat.strip(ActionBarModel.compose(HudStats.DEFAULT, st.players[HudFixtures.me], st, emptyList()))
        assertEquals("2,838", health.find(s)!!.groupValues[1])
    }

    @Test fun overflowManaIs600AndVitalityShowsCurrentOverMaximum() {
        val (s, _) = bar(stats = HudStats.DEFAULT.copy(vitality = 115))
        assertEquals("600", overflow.find(s)!!.groupValues[1])
        assertEquals(listOf("115", "115"), vitality.find(s)!!.destructured.toList())
        val st = HudFixtures.state(); HudFixtures.party(st)
        val ramp = HudFormat.strip(ActionBarModel.compose(HudStats.DEFAULT, st.players[HudFixtures.me], st, emptyList(), vitalityNow = 116))
        assertEquals(listOf("116", "127"), vitality.find(ramp)!!.destructured.toList())
        val none = bar(overflowMana = 0, stats = HudStats.DEFAULT.copy(vitality = null)).first
        assertTrue(!none.contains("\uE028") && !none.contains("\uE017"))
    }

    @Test fun aGhostKeepsFullHealthOnTheBar() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        val me = st.players[HudFixtures.me]!!
        me.hpPercent = 0.0; me.ghost = true
        val s = HudFormat.strip(ActionBarModel.compose(HudStats.DEFAULT, me, st, emptyList()))
        assertEquals(listOf("11,351", "11,351"), health.find(s)!!.destructured.toList())
    }

    @Test fun secretsSegmentUsesTheRoomsMaximumAndFollowsTheBaselineGap() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        val me = st.players[HudFixtures.me]!!
        // The entrance has no secrets: no segment.
        assertEquals(null, ActionBarModel.secretsSegment(st, me))
        // The wide hall (tiles (3,1) and (3,2)) has 3.
        me.x = -89.0; me.z = -150.0
        st.rooms[1].secretsFound = 1
        assertEquals("§71/3 Secrets", ActionBarModel.secretsSegment(st, me))
        val s = HudFormat.strip(ActionBarModel.compose(HudStats.DEFAULT, me, st, emptyList()))
        val (found, max) = secrets.find(s)!!.destructured
        assertEquals("1" to "3", found to max)
        // Recorded: ten spaces before the segment, none after.
        assertTrue(s.endsWith("\uE028          1/3 Secrets"), s)
        // In a gap column (the door slot between two tiles) no segment.
        me.x = -169.0
        assertEquals(null, ActionBarModel.secretsSegment(st, me))
    }

    @Test fun aStrongSegmentTakesTheDefenseSlotAndAWeakOneIsAppended() {
        val now = 100
        val seg = BarSegments()
        seg.set("essence", "§d+10 Gold Essence", 100, now, 20, null)
        seg.set("note", "§eNote", 1, now, 20, null)
        val (s, _) = bar(seg.live(now + 1, HudFixtures.me))
        assertTrue(s.contains("\uE010     +10 Gold Essence     13,444"), s)
        assertTrue(!s.contains(""))
        assertTrue(s.contains("\uE028     Note"), s)
    }

    @Test fun segmentsExpireAreReplacedAndAreAudienceScoped() {
        val seg = BarSegments()
        seg.set("a", "one", 0, 0, 10, null)
        seg.set("a", "two", 0, 0, 10, null)
        assertEquals(listOf("two"), seg.live(5, null).map { it.text })
        assertEquals(emptyList(), seg.live(10, null).map { it.text })
        val mine = UUID(0, 5)
        seg.set("p", "private", 0, 0, 10, mine)
        assertEquals(listOf("private"), seg.live(1, mine).map { it.text })
        assertEquals(emptyList(), seg.live(1, UUID(0, 6)).map { it.text })
        seg.clear("p")
        assertEquals(emptyList(), seg.live(1, mine).map { it.text })
        seg.set("x", "hi", 0, 0, 10, null); seg.clearAll()
        assertEquals(emptyList(), seg.live(1, null).map { it.text })
    }

    @Test fun higherPriorityComesFirst() {
        val seg = BarSegments()
        seg.set("lo", "lo", 1, 0, 10, null)
        seg.set("hi", "hi", 5, 0, 10, null)
        assertEquals(listOf("hi", "lo"), seg.live(1, null).map { it.text })
    }
}
