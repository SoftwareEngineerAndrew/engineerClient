package com.engineerclient.p3sim.puzzles

import com.engineerclient.p3sim.clear.puzzles.OdinTables
import com.engineerclient.p3sim.clear.puzzles.QuizLogic
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuizLogicTest {
    private val now = 1_790_000_000_000L

    /** What Odin's quiz solver would mark right for a question and its options (its `triviaAnswers` and `endsWith` rule). */
    private fun odinRight(q: QuizLogic.Question): Set<Int> {
        val answers = if (q.text.trim() == QuizLogic.YEAR) listOf("Year ${QuizLogic.skyblockYear(now)}")
            else OdinTables.quiz.entries.find { q.text.contains(it.key) }!!.value
        return q.options.indices.filter { i -> answers.any { q.options[i].endsWith(it) } }.toSet()
    }

    @Test fun odinFindsExactlyTheRightOption() {
        for (seed in 0 until 300) {
            val quiz = QuizLogic.draw(Random(seed), now)
            assertEquals(3, quiz.questions.size)
            assertEquals(3, quiz.questions.map { it.text }.toSet().size, "the same question twice (seed $seed)")
            for (q in quiz.questions) {
                assertEquals(3, q.options.size)
                assertEquals(3, q.options.toSet().size, "options repeat in '${q.text}'")
                assertEquals(setOf(q.correct), odinRight(q), "seed $seed: ${q.text} / ${q.options}")
            }
        }
    }

    @Test fun everyTableQuestionResolvesToItsOwnAnswersInOdin() {
        // Odin takes the FIRST table key that the question text contains; a key that contains an earlier key would get the wrong answers.
        for ((k, v) in OdinTables.quiz) assertEquals(v, OdinTables.quiz.entries.find { k.contains(it.key) }!!.value, k)
    }

    @Test fun theYearQuestionIsInThePoolAndComputed() {
        val seen = (0 until 400).any { s -> QuizLogic.draw(Random(s), now).questions.any { it.text == QuizLogic.YEAR } }
        assertTrue(seen)
        assertEquals(QuizLogic.skyblockYear(now), (((now / 1000) - 1560276000) / 446400).toInt() + 1)
    }

    private fun plain(s: String) = s.replace(Regex("§."), "")

    /** The ticks each line is said at, counting the delays from [from]. */
    private fun times(lines: List<QuizLogic.Line>, from: Int = 0): List<Int> { var t = from; return lines.map { t += it.delay; t } }

    @Test fun theIntroIsFourStatueLinesThenTheFirstQuestion() {
        val quiz = QuizLogic.draw(Random(3), now)
        val intro = quiz.start()
        assertEquals(listOf(0, 40, 81, 121, 161), times(intro).let { listOf(it[0], it[1], it[2], it[3], it[4]) })
        assertEquals("[STATUE] Oruo the Omniscient: I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.", plain(intro[0].text))
        assertEquals("[STATUE] Oruo the Omniscient: Though I sit stationary in this prison that is The Catacombs, my knowledge knows no bounds.", plain(intro[1].text))
        assertTrue(plain(intro[2].text).endsWith("Prove your knowledge by answering 3 questions and I shall reward you in ways that transcend time!"))
        assertTrue(plain(intro[3].text).endsWith("Answer incorrectly, and your moment of ineptitude will live on for generations."))
        assertTrue(intro[0].text.startsWith("§4[STATUE] Oruo the Omniscient§r§f: "))
    }

    @Test fun aQuestionIsOneBurstOfEightPlainLines() {
        val quiz = QuizLogic.draw(Random(3), now)
        val burst = quiz.start().drop(4)
        assertEquals(0, burst.drop(1).sumOf { it.delay }, "all on the same tick")
        assertTrue(burst.none { it.text.contains("[STATUE]") })
        assertEquals(8, burst.size.let { if (quiz.questions[0].text.length > 60) it - 1 else it })
        assertEquals("", burst[0].text)
        assertEquals("§f" + " ".repeat(32) + "§r§6§lQuestion #1", burst[1].text)
        val opts = burst.filter { it.text.startsWith("    §6 ") }
        assertEquals(3, opts.size)
        assertEquals("    §6 ⓐ §a${quiz.questions[0].options[0]}", opts[0].text)
        assertEquals("", burst.last().text)
    }

    @Test fun threeRightAnswersSolveIt() {
        val quiz = QuizLogic.draw(Random(3), now)
        quiz.start()
        val a = quiz.answer(quiz.questions[0].correct, "Pat")
        assertTrue(a.correct && !a.solved)
        assertEquals("[STATUE] Oruo the Omniscient: Pat answered Question #1 correctly!", plain(a.lines[0].text))
        assertEquals("§r§b" + "Pat" + " §r§aanswered §r§6Question #1 §r§acorrectly!", a.lines[0].text.removePrefix(QuizLogic.STATUE))
        assertEquals("2 questions left... Then you will have proven your worth to me!", a.lines[1].text.removePrefix(QuizLogic.STATUE))
        assertEquals(40, a.lines[1].delay)
        assertEquals(40, a.lines[2].delay, "the next question comes 40 ticks after the remark")
        val b = quiz.answer(quiz.questions[1].correct, "Pat")
        assertEquals("One more question!", b.lines[1].text.removePrefix(QuizLogic.STATUE))
        val c = quiz.answer(quiz.questions[2].correct, "Pat")
        assertTrue(c.solved)
        assertEquals("[STATUE] Oruo the Omniscient: Pat answered the final question correctly!", plain(c.lines[0].text))
        assertEquals(19, c.lines[1].delay)
        assertEquals(QuizLogic.Phase.SOLVED, quiz.phase)
    }

    @Test fun aWrongAnswerSaysYikesThenNamesThePlayerThenItCanStartAgain() {
        val quiz = QuizLogic.draw(Random(4), now)
        quiz.start()
        val wrong = (0..2).first { it != quiz.questions[0].correct }
        val out = quiz.answer(wrong, "Pat")
        assertFalse(out.correct)
        assertEquals("[STATUE] Oruo the Omniscient: Yikes", plain(out.lines[0].text))
        assertEquals(29, out.lines[1].delay)
        assertEquals("[STATUE] Oruo the Omniscient: Pat chose the wrong answer! I shall never forget this moment of misrememberance.", plain(out.lines[1].text))
        assertEquals(QuizLogic.Phase.IDLE, quiz.phase)
        assertTrue(quiz.answer(quiz.questions[0].correct, "Pat").lines.isEmpty(), "no answer counts while the statue is not asking")
        quiz.start()
        assertEquals(QuizLogic.Phase.ASKING, quiz.phase)
    }

    @Test fun aPressWhileBusyGetsOneOfFourRebukes() {
        val quiz = QuizLogic.draw(Random(1), now)
        val seen = (0 until 60).map { plain(quiz.rebuke(Random(it))).removePrefix("[STATUE] Oruo the Omniscient: ") }.toSet()
        assertEquals(4, seen.size)
        assertTrue("Enough! My buttons are not to be pressed with such lack of grace!" in seen)
    }

    @Test fun optionLinesStartWithTheCircledLetterAfterTrimmingAndEndWithTheAnswer() {
        val quiz = QuizLogic.draw(Random(9), now)
        val lines = quiz.start().map { it.text.replace(Regex("§."), "") }
        val options = lines.filter { it.trim().firstOrNull() in listOf('ⓐ', 'ⓑ', 'ⓒ') }
        assertEquals(3, options.size)
        for (i in 0..2) assertTrue(options[i].endsWith(quiz.questions[0].options[i]))
    }
}
