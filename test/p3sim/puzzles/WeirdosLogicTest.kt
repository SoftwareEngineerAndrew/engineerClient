package com.engineerclient.p3sim.puzzles

import com.engineerclient.p3sim.clear.puzzles.WeirdosLogic
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Three Weirdos against Odin's own classification. The two regex lists are Odin's `WeirdosSolver` lists (OdinFabric 0.3.4,
 * BSD 3-Clause, copyright (c) 2025 odtheking), copied so the test can replay what Odin does with each sentence. A `.` in them
 * matches any character, as in Odin, so the `!` of Hypixel's sentences matches too.
 */
class WeirdosLogicTest {
    private val solutions = listOf(
        Regex("The reward is not in my chest!"),
        Regex("At least one of them is lying, and the reward is not in .+'s chest.?"),
        Regex("My chest doesn't have the reward. We are all telling the truth.?"),
        Regex("My chest has the reward and I'm telling the truth!"),
        Regex("The reward isn't in any of our chests.?"),
        Regex("Both of them are telling the truth. Also, .+ has the reward in their chest.?"),
    )
    private val wrong = listOf(
        Regex("One of us is telling the truth!"),
        Regex("They are both telling the truth. The reward isn't in .+'s chest."),
        Regex("We are all telling the truth!"),
        Regex(".+ is telling the truth and the reward is in his chest."),
        Regex("My chest doesn't have the reward. At least one of the others is telling the truth!"),
        Regex("One of the others is lying."),
        Regex("They are both telling the truth, the reward is in .+'s chest."),
        Regex("They are both lying, the reward is in my chest!"),
        Regex("The reward is in my chest."),
        Regex("The reward is not in my chest. They are both lying."),
        Regex(".+ is telling the truth."),
        Regex("My chest has the reward."),
    )
    private val names = listOf("Alpha", "Bravo", "Charlie")

    private fun plain(s: String) = s.replace(Regex("§."), "")

    /** What Odin does with one NPC line: the correct NPC, a wrong one, or nothing. */
    private fun odin(msg: String): String? =
        if (solutions.none { it.matches(msg) } && wrong.none { it.matches(msg) }) null
        else if (solutions.any { it.matches(msg) }) "correct" else "wrong"

    @Test fun thereAreFiveSetsAndThirtySettings() {
        assertEquals(5, WeirdosLogic.sets.size)
        assertEquals(30, WeirdosLogic.scenarios.size)
        assertTrue(WeirdosLogic.sets.all { set -> set.size == 3 && set.count { it.reward } == 1 })
    }

    @Test fun everyRewardIsReachedBySomeSetting() {
        for (r in 0..2) assertTrue(WeirdosLogic.scenarios.any { it.reward == r }, "no setting rewards chest $r")
    }

    @Test fun odinPicksTheRewardHolderAndOnlyThem() {
        for (sc in WeirdosLogic.scenarios) {
            val lines = sc.lines(names).map(::plain)
            for (i in 0..2) assertEquals(if (i == sc.reward) "correct" else "wrong", odin(lines[i]), "set ${sc.set} reward ${sc.reward}, line ${lines[i]}")
        }
    }

    @Test fun aNamedNpcIsTheSpeakerOfTheLineTheSetPointsAt() {
        val sc = WeirdosLogic.Scenario(0, listOf(2, 0, 1)) // set A: line 0 names the speaker of line 1 (the reward line)
        val said = sc.lines(names)
        assertEquals("They are both telling the truth. The reward isn't in Alpha's chest.", plain(said[2]))
        assertEquals(0, sc.reward)
    }

    @Test fun referencedNamesAreRedWithAResetAfter() {
        val sc = WeirdosLogic.Scenario(2, listOf(0, 1, 2)) // set C
        assertEquals("§c§cAlpha §ris telling the truth.", sc.lines(names)[1])
        assertEquals("Both of them are telling the truth. Also, §c§cBravo §rhas the reward in their chest!", sc.lines(names)[2])
    }

    @Test fun noSentenceEndsWithAFullStopWhereHypixelUsesABang() {
        val all = WeirdosLogic.scenarios.flatMap { it.lines(names) }.map(::plain).toSet()
        assertTrue("The reward is in my chest!" in all && "My chest has the reward!" in all && "One of the others is lying!" in all)
        assertTrue("The reward is in my chest." !in all && "My chest has the reward." !in all && "One of the others is lying." !in all)
    }

    @Test fun aDrawIsRepeatableFromTheSeedAndNamesAreDistinct() {
        val a = WeirdosLogic.draw(Random(5)); val b = WeirdosLogic.draw(Random(5))
        assertEquals(a.lines(names), b.lines(names))
        assertEquals(3, WeirdosLogic.drawNames(Random(1)).toSet().size)
        assertEquals(21, WeirdosLogic.NAMES.toSet().size)
    }
}
