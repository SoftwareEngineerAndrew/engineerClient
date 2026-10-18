package com.engineerclient.p3sim.clear.puzzles

import com.engineerclient.p3sim.pack.rooms.WorldPos
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.api.PuzzleContext
import java.util.UUID

/**
 * The Quiz in the world: when a player walks in the statue speaks (chat lines Odin's quiz solver keys on), three questions follow,
 * and the party answers by pressing a stone button on one of three pillars (a button beside the pillar's anchor block). Three
 * right answers solve it at the moment of the final answer (the statue's closing line follows). A wrong one makes the statue say
 * "Yikes", the tab shows Failed a little later, the statue names the culprit and the whole quiz starts over with new questions.
 * Buttons pressed while the statue is busy, or after the quiz is over, get a random rebuke.
 */
class QuizPuzzle(context: PuzzleContext) : WorldPuzzle(PuzzleKinds.QUIZ, context) {
    private var logic: QuizLogic? = null
    private var pillars: List<WorldPos> = emptyList()
    private val queue = ArrayList<Pair<Int, String>>()
    private var starts = 0
    private var restartAt = -1
    private var failAt = -1
    private var failBy: UUID? = null
    private val rebukes = kotlin.random.Random(0x0A11)

    override val printsFailLine: Boolean get() = false

    override fun build() {
        pillars = (1..3).mapNotNull { context.anchor("pillar$it").firstOrNull() }
        fresh()
    }

    private fun fresh() {
        logic = QuizLogic.draw(run.subRandom("quiz:${context.room.index}:${starts++}"))
        queue.clear(); restartAt = -1; failAt = -1
    }

    private fun enqueue(lines: List<QuizLogic.Line>, from: Int) {
        var t = from
        for (l in lines) { t += l.delay; queue += t to l.text }
    }

    override fun onTick() {
        val q = logic ?: return
        if (pillars.size < 3) return
        if (status == PuzzleStatus.UNSOLVED && q.phase == QuizLogic.Phase.IDLE && restartAt < 0 && queue.isEmpty()) enqueue(q.start(), now)
        while (queue.isNotEmpty() && queue[0].first <= now) say(queue.removeAt(0).second)
        if (failAt in 0..now) { failAt = -1; fail(failBy, "answered wrong") }
        if (restartAt in 0..now) { restartAt = -1; PuzzlesModule.resetPuzzle(this) }
    }

    override fun onInteract(player: UUID, x: Int, y: Int, z: Int): Boolean {
        val q = logic ?: return false
        val option = pillars.indexOfFirst { it.y == y && Math.abs(it.x - x) + Math.abs(it.z - z) == 1 }
        if (option < 0) return false
        if (status != PuzzleStatus.UNSOLVED || q.phase != QuizLogic.Phase.ASKING || queue.isNotEmpty()) {
            say(q.rebuke(rebukes))
            return true
        }
        val out = q.answer(option, nameOf(player))
        enqueue(out.lines, now)
        if (out.solved) solve(player)
        else if (!out.correct) {
            failAt = now + QuizLogic.Pace.YIKES_TO_TAB_FAILED; failBy = player
            restartAt = now + QuizLogic.Pace.YIKES_TO_WRONG + QuizLogic.Pace.WRONG_TO_RESTART
        }
        return true
    }

    override fun onReset() {
        // fresh() is also what a new start needs: new questions, nothing queued.
        fresh()
    }
}
