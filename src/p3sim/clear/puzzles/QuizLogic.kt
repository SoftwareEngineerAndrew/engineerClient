package com.engineerclient.p3sim.clear.puzzles

import kotlin.random.Random

/**
 * The Quiz as a state machine: three questions, three options each, the statue speaks, the party answers by pressing a button on
 * one of three pillars. Questions, answers and the "SkyBlock year" computation are Odin's `QuizSolver` data (OdinFabric 0.3.4,
 * BSD 3-Clause, copyright (c) 2025 odtheking, see [OdinTables]). The wrong options are the sim's: the data has only right answers, so
 * each wrong option is another question's answer of the same kind. Pure: no Minecraft; the glue sends the lines.
 *
 * The statue's words, colours and pauses are measured over 118 real quizzes: four intro lines (0, 40, 81 and 121 ticks after the
 * first), the first question 40 ticks after the fourth, a remark 40 ticks after each correct answer and the next question 40 ticks
 * after that, "bestow" 19 ticks after the final answer. A question is one burst of chat lines on a single tick (not statue lines).
 */
class QuizLogic(val questions: List<Question>) {
    /** One question as asked: [correct] is the index of the right option. */
    class Question(val text: String, val options: List<String>, val correct: Int)

    /** A chat line and the ticks to wait after the previous line before saying it. */
    class Line(val delay: Int, val text: String)

    /** Pauses, in ticks (medians of the real quizzes). */
    object Pace {
        /** Between the intro lines: 40, 41 and 40. */
        val INTRO_GAPS = listOf(40, 41, 40)
        const val AFTER_INTRO = 40
        /** Correct answer to the remark ("2 questions left..."). */
        const val ANSWER_TO_REMARK = 40
        /** Remark to the next question. */
        const val REMARK_TO_QUESTION = 40
        /** Final answer to "I bestow upon you". */
        const val FINAL_TO_BESTOW = 19
        /** "Yikes" to the wrong-answer line. */
        const val YIKES_TO_WRONG = 29
        /** The wrong-answer line to the intro starting over. */
        const val WRONG_TO_RESTART = 56
        /** "Yikes" to the tab showing Failed. */
        const val YIKES_TO_TAB_FAILED = 16
    }

    enum class Phase { IDLE, ASKING, SOLVED }

    class Outcome(val correct: Boolean, val lines: List<Line>, val solved: Boolean)

    var phase = Phase.IDLE
        private set

    /** Index of the question being asked (0 based). */
    var current = 0
        private set

    /** The intro and the first question. */
    fun start(): List<Line> {
        check(phase == Phase.IDLE) { "the quiz already started" }
        phase = Phase.ASKING
        current = 0
        val g = Pace.INTRO_GAPS
        return listOf(
            Line(0, STATUE + "I am §r§4Oruo the Omniscient§r§f. I have lived many lives. I have learned all there is to know."),
            Line(g[0], STATUE + "Though I sit stationary in this prison that is §r§cThe Catacombs§r§f, my knowledge knows no bounds."),
            Line(g[1], STATUE + "Prove your knowledge by answering 3 questions and I shall reward you in ways that transcend time!"),
            Line(g[2], STATUE + "Answer incorrectly, and your moment of ineptitude will live on for generations."),
        ) + ask(Pace.AFTER_INTRO)
    }

    /** The question as Hypixel sends it: one burst of eight chat lines on the same tick (the first of them after [delay]). */
    private fun ask(delay: Int): List<Line> {
        val q = questions[current]
        val out = ArrayList<Line>()
        out += Line(delay, "")
        out += Line(0, "§f" + " ".repeat(32) + "§r§6§lQuestion #${current + 1}")
        for (row in wrap(q.text)) out += Line(0, "§f" + " ".repeat(maxOf(1, CENTRE - row.length / 2)) + "§r§6" + row)
        out += Line(0, "")
        q.options.forEachIndexed { i, o -> out += Line(0, "    §6 ${LETTERS[i]} §a$o") }
        out += Line(0, "")
        return out
    }

    /** Someone pressed a button of option [option] (0..2). Wrong: the quiz is over (the glue starts it again). */
    fun answer(option: Int, player: String): Outcome {
        if (phase != Phase.ASKING) return Outcome(false, emptyList(), false)
        val q = questions[current]
        if (option != q.correct) {
            phase = Phase.IDLE
            return Outcome(false, listOf(
                Line(0, STATUE + "§r§4Y§r§ci§r§6k§r§ee§r§as"),
                Line(Pace.YIKES_TO_WRONG, STATUE + "§r§b$player §r§cchose the wrong answer! I shall never forget this moment of misrememberance."),
            ), false)
        }
        val n = current + 1
        if (n == questions.size) {
            phase = Phase.SOLVED
            return Outcome(true, listOf(
                Line(0, STATUE + "§r§b$player §r§aanswered the final question correctly!"),
                Line(Pace.FINAL_TO_BESTOW, STATUE + "I bestow upon you all the power of a hundred years!"),
            ), true)
        }
        current = n
        val remark = if (n == 1) "2 questions left... Then you will have proven your worth to me!" else "One more question!"
        return Outcome(true, listOf(
            Line(0, STATUE + "§r§b$player §r§aanswered §r§6Question #$n §r§acorrectly!"),
            Line(Pace.ANSWER_TO_REMARK, STATUE + remark),
        ) + ask(Pace.REMARK_TO_QUESTION), false)
    }

    /** What the statue says to a button pressed while it is busy, or after the quiz is over. */
    fun rebuke(rnd: Random): String = STATUE + REBUKES[rnd.nextInt(REBUKES.size)]

    /** Back to the start (a wrong answer, the reset). */
    fun reset() { phase = Phase.IDLE; current = 0 }

    companion object {
        const val STATUE = "§4[STATUE] Oruo the Omniscient§r§f: "
        private val LETTERS = listOf("ⓐ", "ⓑ", "ⓒ")

        /** The column the centred lines are centred on (the "Question #1" heading starts 32 spaces in). */
        private const val CENTRE = 37

        /** A question longer than this is cut into two centred lines. */
        private const val WRAP_AT = 60

        private fun wrap(text: String): List<String> {
            if (text.length <= WRAP_AT) return listOf(text)
            val mid = text.length / 2
            val cut = (mid downTo 1).firstOrNull { text[it] == ' ' } ?: return listOf(text)
            return listOf(text.substring(0, cut), text.substring(cut + 1))
        }

        private val REBUKES = listOf(
            "You've already proven enough to me! No need to press more of my buttons!",
            "Enough! My buttons are not to be pressed with such lack of grace!",
            "I've had enough of you and your party fiddling with my buttons. Scram!",
            "You have already shown your vast knowledge of SkyBlock. Come back in another life to answer more questions!",
        )

        /** Odin's formula for the SkyBlock year question (it is time dependent). */
        fun skyblockYear(nowMillis: Long): Int = (((nowMillis / 1000) - 1560276000) / 446400).toInt() + 1

        private fun cluster(q: String) = q.split(' ').take(4).joinToString(" ").lowercase()

        /** Builds the three questions of one room from the Odin table plus the year question. */
        fun draw(rnd: Random, nowMillis: Long = System.currentTimeMillis(), table: Map<String, List<String>> = OdinTables.quiz): QuizLogic {
            // Variants of one question (Spiders Den / Spider's Den) share answers: ask each only once.
            val unique = table.entries.distinctBy { it.value.sorted() }.map { it.key to it.value } + listOf(YEAR to listOf("Year ${skyblockYear(nowMillis)}"))
            val picked = unique.shuffled(rnd).take(3)
            return QuizLogic(picked.map { (text, answers) -> build(rnd, text, answers, table, nowMillis) })
        }

        const val YEAR = "What SkyBlock year is it?"

        private fun build(rnd: Random, text: String, answers: List<String>, table: Map<String, List<String>>, nowMillis: Long): Question {
            val right = answers[rnd.nextInt(answers.size)]
            // Odin marks an option right when it ENDS WITH an accepted answer, so no wrong option may end with one.
            fun fine(o: String) = answers.none { o.endsWith(it) }
            val wrong: List<String> = if (text == YEAR) {
                val y = skyblockYear(nowMillis)
                listOf("Year ${y + 1}", "Year ${maxOf(1, y - 1).let { if (it == y) y + 2 else it }}")
            } else {
                val near = table.filterKeys { it != text && cluster(it) == cluster(text) }.values.flatten().distinct().filter(::fine)
                val any = table.filterKeys { it != text }.values.flatten().distinct().filter(::fine)
                (near.shuffled(rnd) + any.shuffled(rnd)).distinct().take(2)
            }
            val options = (wrong + right).shuffled(rnd)
            return Question(text, options, options.indexOf(right))
        }
    }
}
