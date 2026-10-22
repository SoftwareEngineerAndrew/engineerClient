package com.engineerclient.p3sim.secrets

import com.engineerclient.p3sim.clear.secrets.Beats
import com.engineerclient.p3sim.clear.secrets.BonusBat
import com.engineerclient.p3sim.clear.secrets.BonusBatTracker
import com.engineerclient.p3sim.clear.secrets.LockedChest
import com.engineerclient.p3sim.clear.secrets.LeverWall
import com.engineerclient.p3sim.clear.secrets.SecretChestFinale
import com.engineerclient.p3sim.clear.secrets.SecretRules
import com.engineerclient.p3sim.clear.secrets.SecretSounds
import com.engineerclient.p3sim.clear.secrets.SkullFinale
import com.engineerclient.p3sim.clear.secrets.TimedQueue
import com.engineerclient.p3sim.clear.secrets.TrappedChest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretFinalesTest {
    @Test fun aPlainChestOnlyOpensItsLid() {
        val s = SecretChestFinale.schedule(reward = false)
        assertEquals(listOf(SecretChestFinale.Step.LID_AND_CENTRE_OPEN), s.map { it.step })
        assertEquals(1, s.single().at)
    }

    @Test fun aRewardChestRollsFiveNotesAndRevealsOnTheLast() {
        val s = SecretChestFinale.schedule(reward = true)
        // The corner sound, then the lid and the centre sound one tick later, the five notes five ticks apart, the reveal with the fifth.
        assertEquals(0, s.first { it.step == SecretChestFinale.Step.CORNER_OPEN }.at)
        assertEquals(1, s.first { it.step == SecretChestFinale.Step.LID_AND_CENTRE_OPEN }.at)
        val harps = s.filter { it.step == SecretChestFinale.Step.HARP }
        assertEquals(listOf(4, 9, 14, 19, 24), harps.map { it.at })
        assertEquals(listOf(0, 1, 2, 3, 4), harps.map { it.note })
        assertEquals(SecretChestFinale.REVEAL_AT, s.single { it.step == SecretChestFinale.Step.REVEAL }.at)
        assertEquals(harps.last().at, SecretChestFinale.REVEAL_AT)
        assertTrue(s.indexOfFirst { it.step == SecretChestFinale.Step.HARP && it.note == 4 } < s.indexOfFirst { it.step == SecretChestFinale.Step.REVEAL }, "the last note plays before the reveal on the same tick")
    }

    @Test fun anEssenceSkullPlingsRollsFiveNotesAndPaysOutOnTheLast() {
        val s = SkullFinale.schedule(withPling = true)
        assertEquals(1, s.single { it.step == SkullFinale.Step.PLING }.at)
        assertEquals(listOf(1, 5, 10, 15, 20), s.filter { it.step == SkullFinale.Step.HARP }.map { it.at })
        assertEquals(SkullFinale.REWARD_AT, s.single { it.step == SkullFinale.Step.REWARD }.at)
        // A teammate's find has no pling (only the finder hears it).
        assertTrue(SkullFinale.schedule(withPling = false).none { it.step == SkullFinale.Step.PLING })
        assertEquals(2, SkullFinale.orbPings(isFinder = true)); assertEquals(1, SkullFinale.orbPings(isFinder = false))
    }

    @Test fun pitchesAreMultiplesOfOneSixtyThird() {
        val r = Random(4)
        repeat(300) {
            val k = SecretSounds.chestOpen(r) * 63f
            assertEquals(Math.round(k).toFloat(), k, 1e-3f)
            assertTrue(Math.round(k) in 56..62, "chest open pitch $k")
        }
        for (p in SecretSounds.HARP) assertEquals(Math.round(p * 63f).toFloat(), p * 63f, 1e-3f)
        assertEquals(listOf(50, 56, 63, 69, 75), SecretSounds.HARP.map { Math.round(it * 63f) })
        assertEquals(4.047619f, SecretSounds.SKULL_PLING_PITCH, 1e-6f)
        assertEquals(1.6984127f, SecretSounds.ANVIL_BREAK_PITCH, 1e-6f)
        assertEquals(1.4920635f, SecretSounds.ORB_PITCH, 1e-6f)
        assertEquals(0.4920635f, SecretSounds.WALL_TICK_PITCH, 1e-6f)
        assertEquals(0.5873016f, SecretSounds.LEVER_CLICK_PITCH, 1e-6f)
        assertEquals(59f / 63f, SecretSounds.quantise(0.95f), 1e-6f)
    }

    @Test fun aBatDiesWithAPitchScaledByItsVoice() {
        val r = Random(8)
        val ps = (1..4000).map { SecretSounds.batDeath(r) }.sorted()
        assertEquals(0.95f, ps[ps.size / 2], 0.02f)
        assertTrue(ps.first() >= 0.76f && ps.last() <= 1.14f, "range ${ps.first()}..${ps.last()}")
        assertTrue(ps.all { Math.abs(it * 63f - Math.round(it * 63f)) < 1e-3f })
    }

    @Test fun pickupPitchesSpreadAroundTwo() {
        val r = Random(6)
        val ps = (1..3000).map { SecretSounds.pickup(r) }
        assertEquals(2.0f, ps.average().toFloat(), 0.05f)
        assertTrue(ps.min() >= 0.6f && ps.max() <= 3.4f)
        val b = (1..3000).map { SecretSounds.bonusPing(r) }
        assertTrue(b.min() >= 1.70f && b.max() <= 2.68f + 1e-4f)
    }

    @Test fun theQueueHandsOutDueThingsInOrderAndCanDropAnOwner() {
        val q = TimedQueue<String>()
        q.add(10, "c"); q.add(5, "a", "x"); q.add(5, "b", "y"); q.add(20, "late", "x")
        assertTrue(q.takeDue(4).isEmpty())
        assertEquals(listOf("a", "b"), q.takeDue(5))
        q.dropOwner("x")
        assertEquals(listOf("c"), q.takeDue(30))
        assertTrue(q.isEmpty())
        q.add(1, "z"); q.clear()
        assertTrue(q.takeDue(100).isEmpty())
    }

    @Test fun theBonusBatIsOnTheFirstOrSecondCountedKillMostOfTheTime() {
        val r = Random(12)
        val n = 20000
        val d = (1..n).map { BonusBat.draw(r) }.groupingBy { it }.eachCount()
        assertEquals(0.70, d[1]!! / n.toDouble(), 0.02); assertEquals(0.24, d[2]!! / n.toDouble(), 0.02); assertEquals(0.05, d[3]!! / n.toDouble(), 0.01)
        assertTrue((d[4] ?: 0) < n * 0.02)
        assertTrue(d.keys.all { it in 1..4 })
    }

    @Test fun theTrappedChestIsOneOfTheCandidatesWithTheStatedChance() {
        val sites = (0 until 6).map { SecretFixtures.chestSite(it) }
        assertNull(TrappedChest.pick(emptyList(), 1.0, Random(1)))
        assertTrue(TrappedChest.pick(sites, 1.0, Random(1)) in sites)
        assertNull(TrappedChest.pick(sites, 0.0, Random(1)))
        val hits = (1..4000).count { TrappedChest.pick(sites, 0.72, Random(it)) != null }
        assertEquals(0.72, hits / 4000.0, 0.03)
        val picks = (1..3000).mapNotNull { TrappedChest.pick(sites, 1.0, Random(it)) }.groupingBy { it.key }.eachCount()
        assertEquals(6, picks.size, "every chest can be the trapped one")
        assertEquals(TrappedChest.pick(sites, 0.9, Random(77))?.key, TrappedChest.pick(sites, 0.9, Random(77))?.key, "the same seed picks the same chest")
    }

    @Test fun leverWallsTickNineTimesFiveTicksApartExceptInThreeRooms() {
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30, 35, 40), LeverWall.TICKS.toList())
        assertTrue(LeverWall.ticks("Doors") && LeverWall.ticks("End"))
        assertTrue(!LeverWall.ticks("Buttons") && !LeverWall.ticks("old trap") && !LeverWall.ticks(" Bridges "))
    }

    @Test fun beatsPlayOnceInOrderAndCanBePickedUpMidWay() {
        val ats = SkullFinale.schedule(withPling = true).map { it.at }
        val done = HashSet<Int>()
        assertTrue(Beats.due(ats, -3, done).isEmpty(), "not started yet")
        assertEquals(listOf(0, 1), Beats.due(ats, 1, done), "the pling and the first note come together")
        assertTrue(Beats.due(ats, 1, done).isEmpty(), "a beat plays once")
        assertTrue(Beats.due(ats, 4, done).isEmpty())
        assertEquals(listOf(2), Beats.due(ats, 5, done))
        // A tick that was skipped plays everything that fell due in between, earliest first.
        assertEquals(listOf(10, 15), Beats.due(ats, 18, done).map { ats[it] })
        assertEquals(listOf(20, 20), Beats.due(ats, 25, done).map { ats[it] }, "the fifth note and the reward fall on the same tick")
        assertEquals(setOf(0, 1, 2), Beats.playedBy(ats, 5))
        assertEquals(ats.indices.toSet(), Beats.playedBy(ats, SkullFinale.REWARD_AT))
        assertTrue(Beats.playedBy(ats, -1).isEmpty())
    }

    @Test fun aLockedChestAnswersOnceThenStaysQuietWhileTheClickRepeats() {
        assertTrue(LockedChest.answers(null, 100))
        assertTrue(!LockedChest.answers(100, 104) && !LockedChest.answers(100, 111))
        assertTrue(LockedChest.answers(100, 112))
    }

    @Test fun theBonusPointIsOnTheNthCountedBatAndNeverOnOthers() {
        val second = BonusBatTracker(2)
        assertEquals(listOf(false, true, false, false), (1..4).map { second.count() })
        val first = BonusBatTracker(1)
        assertEquals(listOf(true, false), (1..2).map { first.count() })
        val none = BonusBatTracker(0)
        assertTrue((1..6).none { none.count() }, "a run without a bonus bat never scores one")
        // A savepoint puts the count back where it was.
        val back = BonusBatTracker(3, counted = 2)
        assertTrue(back.count()); assertTrue(!back.count())
    }

    @Test fun overAThousandRunsWithOneCountedBatSevenInTenShowTheLine() {
        val r = Random(21)
        val showed = (1..4000).count { BonusBatTracker(BonusBat.draw(r)).count() }
        assertEquals(0.70, showed / 4000.0, 0.025)
        val twoBats = (1..4000).count { val t = BonusBatTracker(BonusBat.draw(r)); t.count() || t.count() }
        assertEquals(0.94, twoBats / 4000.0, 0.02)
    }

    @Test fun aSecondClickOnAChestIsSilentWhileTheHarpRollsAndOnTheMimicsChest() {
        assertEquals(SecretRules.Repeat.SILENT, SecretRules.repeatClick(trapped = false, reward = true, finished = false))
        assertEquals(SecretRules.Repeat.SEARCHED, SecretRules.repeatClick(trapped = false, reward = true, finished = true))
        assertEquals(SecretRules.Repeat.SEARCHED, SecretRules.repeatClick(trapped = false, reward = false, finished = false), "a plain chest says so at once")
        assertEquals(SecretRules.Repeat.SEARCHED, SecretRules.repeatClick(trapped = false, reward = false, finished = true))
        assertEquals(SecretRules.Repeat.SILENT, SecretRules.repeatClick(trapped = true, reward = false, finished = true))
        // The reward chest's roll is over exactly when its schedule is: the reveal is its last beat.
        assertEquals(SecretChestFinale.REVEAL_AT, SecretChestFinale.schedule(true).maxOf { it.at })
    }

    @Test fun aLeverPullPrintsTheOpeningLineAndAUsedLineForASneakingPuller() {
        assertEquals(listOf(SecretRules.LEVER_OPENING), SecretRules.leverLines(alreadyUsed = false, sneaking = false))
        assertEquals(listOf(SecretRules.LEVER_OPENING, SecretRules.LEVER_USED), SecretRules.leverLines(alreadyUsed = false, sneaking = true))
        // A used lever always reads the same, whoever pulled it first and whether or not the puller sneaks.
        assertEquals(listOf(SecretRules.LEVER_USED), SecretRules.leverLines(alreadyUsed = true, sneaking = false))
        assertEquals(listOf(SecretRules.LEVER_USED), SecretRules.leverLines(alreadyUsed = true, sneaking = true))
        val all = listOf(false, true).flatMap { u -> listOf(false, true).flatMap { s -> SecretRules.leverLines(u, s) } }
        assertTrue(all.none { it.contains("Someone has already activated") })
        assertEquals("You hear the sound of something opening...", SecretRules.LEVER_OPENING.replace(Regex("§."), ""))
        assertEquals("You hear something open...", SecretRules.SOCKET_USED.replace(Regex("§."), ""))
        assertTrue(SecretRules.SOCKET_USED.startsWith("§7§o"), "gray italics, not the red lever line")
    }
}
