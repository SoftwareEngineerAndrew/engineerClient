package com.engineerclient.p3sim.puzzles

import com.engineerclient.p3sim.clear.puzzles.BlazePuzzle
import com.engineerclient.p3sim.clear.puzzles.PuzzleDraw
import com.engineerclient.p3sim.clear.puzzles.PuzzleKinds
import com.engineerclient.p3sim.clear.puzzles.PuzzleLines
import com.engineerclient.p3sim.clear.puzzles.PuzzlesModule
import com.engineerclient.p3sim.clear.puzzles.SolveTiming
import com.engineerclient.p3sim.pack.Fixtures
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import com.engineerclient.p3sim.run.DungeonPlan
import com.engineerclient.p3sim.run.PuzzleFailed
import com.engineerclient.p3sim.run.PuzzleSolved
import com.engineerclient.p3sim.run.PuzzleStatus
import com.engineerclient.p3sim.run.RoomEntered
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunConfig
import com.engineerclient.p3sim.run.RunEvent
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.TestPlans
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The puzzle frame without a world: records, timing of the solve, the fail path, the tab words, the draw. */
class PuzzleFrameTest {
    private fun layoutJson(extra: String) = """
    {"format":"ads-layouts","version":1,"layouts":[
     {"id":"p-1","floor":"F7","doorsComplete":true,"path":[0,1,2],"playable":true,"party":[],
      "rooms":[
       {"i":0,"name":"Entrance","family":"Entrance","type":"entrance","shape":"1x1","tiles":[[3,0]],"rot":"NORTH","secretsMax":0},
       {"i":1,"name":"Wide Hall","family":"Wide_Hall","type":"normal","shape":"1x1","tiles":[[3,1]],"rot":"WEST","secretsMax":3},
       {"i":2,"name":"Last Stop","family":"Blood","type":"blood","shape":"1x1","tiles":[[3,2]],"rot":"SOUTH","secretsMax":0},
       {"i":3,"name":"Boulder","family":"Boulder","type":"puzzle","shape":"1x1","tiles":[[2,1]],"rot":"EAST","secretsMax":0},
       {"i":4,"name":"Lower Blaze","family":"Blaze_Lower","type":"puzzle","shape":"1x1","tiles":[[4,1]],"rot":"WEST","secretsMax":0},
       {"i":5,"name":"Three Weirdos","family":"Three_Weirdos","type":"puzzle","shape":"1x1","tiles":[[2,2]],"rot":"EAST","secretsMax":0}$extra
      ],
      "doors":[
       {"axis":"S","a":[3,0],"b":[3,1],"ra":0,"rb":1,"kind":"entrance"},
       {"axis":"S","a":[3,1],"b":[3,2],"ra":1,"rb":2,"kind":"blood"},
       {"axis":"E","a":[2,1],"b":[3,1],"ra":3,"rb":1,"kind":"normal"},
       {"axis":"E","a":[3,1],"b":[4,1],"ra":1,"rb":4,"kind":"normal"},
       {"axis":"E","a":[2,2],"b":[3,2],"ra":5,"rb":2,"kind":"none"}
      ],
      "keys":[],"puzzles":["Boulder","Lower Blaze","Three Weirdos"]}
    ]}
    """.trimIndent()

    private val player = UUID(0, 7)

    private fun newRun(): Run = newRunWith(null, null)

    /** The run of three puzzle rooms, plus (as room 6) one more puzzle room by [name] and [family] when given. */
    private fun newRunWith(name: String?, family: String?): Run {
        val extra = if (name == null) "" else """,
       {"i":6,"name":"$name","family":"$family","type":"puzzle","shape":"1x1","tiles":[[4,2]],"rot":"EAST","secretsMax":0}"""
        val pack = LayoutPack.fromJson(Fixtures.json(layoutJson(extra)))!!
        val plan = (DungeonPlan.build(pack.layout("p-1")!!, 1, null) as DungeonPlan.Result.Ok).plan
        val run = Run("r", RunConfig(seed = 3), plan, TestPlans.emptyPacks(), systems = listOf(PuzzlesModule))
        run.begin(100)
        run.setT0(100)
        return run
    }

    private fun events(run: Run): MutableList<RunEvent> = ArrayList<RunEvent>().also { l -> run.bus.subscribe { l += it } }

    @Test fun oneRecordPerPuzzleRoomWithTheTabName() {
        val run = newRun()
        assertEquals(listOf("BOULDER" to "Boulder", "BLAZE" to "Higher Or Lower", "WEIRDOS" to "Three Weirdos"), run.state.puzzles.map { it.kind to it.tabName })
        assertEquals(listOf(3, 4, 5), run.state.puzzles.map { it.roomIndex })
        assertTrue(run.state.puzzles.all { it.status == PuzzleStatus.UNSOLVED && !it.revealed })
        assertNotNull(PuzzlesModule.puzzleOf(run, 3))
        assertEquals(null, PuzzlesModule.puzzleOf(run, 1))
    }

    @Test fun enteringTheRoomRevealsTheRow() {
        val run = newRun()
        run.bus.post(RoomEntered(0, 3, player))
        assertTrue(run.state.puzzles[0].revealed)
        assertFalse(run.state.puzzles[1].revealed)
    }

    @Test fun aSolveTurnsTheRoomGreenAndCompletedOnTheSameStep() {
        val run = newRun()
        val seen = events(run)
        val p = PuzzlesModule.puzzleOf(run, 3)!!
        run.tick(200)
        PuzzlesModule.reportSolved(p, player)
        assertEquals(1, seen.count { it is PuzzleSolved })
        run.tick(201); assertEquals(PuzzleStatus.SOLVED, p.status)
        assertEquals(player, run.state.puzzles[0].solvedBy)
        PuzzlesModule.reportSolved(p, player)
        assertEquals(1, seen.count { it is PuzzleSolved }, "a second solve is ignored")
    }

    @Test fun higherOrLowerIsCompletedAtTheLastKillAndGreenWhenTheChestStops() {
        val run = newRun()
        val seen = events(run)
        val p = PuzzlesModule.puzzleOf(run, 4) as BlazePuzzle
        run.tick(300)
        p.rideStartedAt(300)                 // the first correct kill; the chest stops at 540, the room turns at 544
        run.tick(380)
        PuzzlesModule.reportSolved(p, player) // the last kill
        assertEquals(PuzzleStatus.SOLVED, p.status, "Completed at the last kill")
        assertEquals(0, seen.count { it is PuzzleSolved })
        run.tick(543); assertEquals(0, seen.count { it is PuzzleSolved })
        run.tick(544); assertEquals(1, seen.count { it is PuzzleSolved })
    }

    @Test fun higherOrLowerIsGreenAtOnceWhenTheChestHasAlreadyStopped() {
        val run = newRun()
        val seen = events(run)
        val p = PuzzlesModule.puzzleOf(run, 4) as BlazePuzzle
        run.tick(300)
        p.rideStartedAt(300)
        run.tick(700)                        // a slow clear: the chest stopped long ago
        PuzzlesModule.reportSolved(p, player)
        assertEquals(PuzzleStatus.SOLVED, p.status)
        assertEquals(1, seen.count { it is PuzzleSolved })
    }

    @Test fun ticTacToeSaysSolvedAtOnceTurnsGreenAt2AndCompletedAt13() {
        val run = newRunWith("Tic Tac Toe", "Tic_Tac_Toe")
        val seen = events(run)
        val p = PuzzlesModule.puzzleOf(run, 6)!!
        run.tick(500)
        PuzzlesModule.reportSolved(p, player)
        assertEquals(PuzzleStatus.UNSOLVED, p.status)
        run.tick(501); assertEquals(0, seen.count { it is PuzzleSolved })
        run.tick(502); assertEquals(1, seen.count { it is PuzzleSolved }, "green at +2")
        run.tick(512); assertEquals(PuzzleStatus.UNSOLVED, p.status)
        run.tick(513); assertEquals(PuzzleStatus.SOLVED, p.status, "Completed at +13")
    }

    @Test fun solveTimingKeepsTheOldLeadRules() {
        val early = SolveTiming.fromLead(5)
        assertEquals(Triple(5, 0, 5), Triple(early.chat, early.green, early.complete))
        val late = SolveTiming.fromLead(-3)
        assertEquals(Triple(0, 3, 0), Triple(late.chat, late.green, late.complete))
    }

    @Test fun aFailMarksTheRowCountsAndCanBeResetUntilSolved() {
        val run = newRun()
        val seen = events(run)
        val p = PuzzlesModule.puzzleOf(run, 5)!!
        PuzzlesModule.reportFailed(p, null, "was fooled by Someone")
        assertEquals(PuzzleStatus.FAILED, p.status)
        assertEquals(1, run.state.puzzles[2].failures)
        val failed = seen.filterIsInstance<PuzzleFailed>().single()
        assertEquals(2, failed.puzzle); assertEquals(5, failed.room); assertEquals("was fooled by Someone", failed.reason)
        PuzzlesModule.resetPuzzle(p)
        assertEquals(PuzzleStatus.UNSOLVED, p.status)
        PuzzlesModule.reportSolved(p, player); run.tick(1000)
        PuzzlesModule.reportFailed(p, player, "late")
        assertEquals(PuzzleStatus.SOLVED, p.status, "a solved puzzle cannot fail")
    }

    @Test fun savepointKeepsTheRows() {
        val run = newRun()
        val p = PuzzlesModule.puzzleOf(run, 3)!!
        run.bus.post(RoomEntered(0, 3, player))
        PuzzlesModule.reportFailed(p, null, "x")
        val snap = PuzzlesModule.snapshot(run)!!
        run.state.puzzles.forEach { it.status = PuzzleStatus.UNSOLVED; it.revealed = false; it.failures = 0 }
        PuzzlesModule.restore(run, snap)
        assertEquals(PuzzleStatus.FAILED, run.state.puzzles[0].status)
        assertTrue(run.state.puzzles[0].revealed)
        assertEquals(1, run.state.puzzles[0].failures)
    }

    @Test fun endingTheRunLetsGo() {
        val run = newRun()
        run.end(com.engineerclient.p3sim.run.EndReason.STOPPED)
        assertEquals(null, PuzzlesModule.puzzleOf(run, 3))
    }

    // ---- the words ----------------------------------------------------------------------------------------------

    @Test fun tabRowsMatchOdinsRegex() {
        val odin = Regex("^ (\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?$")
        val unseen = odin.find(PuzzleLines.tabRow("Boulder", false, PuzzleStatus.UNSOLVED, null))!!.destructured
        assertEquals(Triple("???", "✦", ""), Triple(unseen.component1(), unseen.component2(), unseen.component3()))
        val done = odin.find(PuzzleLines.tabRow("Higher Or Lower", true, PuzzleStatus.SOLVED, "Pat"))!!.destructured
        assertEquals(Triple("Higher Or Lower", "✔", "Pat"), Triple(done.component1(), done.component2(), done.component3()))
        val bad = odin.find(PuzzleLines.tabRow("Quiz", true, PuzzleStatus.FAILED, "Pat"))!!.destructured
        assertEquals("✖", bad.component2())
        assertTrue(Regex("^Puzzles: \\((\\d+)\\)$").matches(PuzzleLines.tabHeadline(3)))
        for (name in PuzzleKinds.tabNames.values) assertTrue(odin.matches(PuzzleLines.tabRow(name, true, PuzzleStatus.UNSOLVED, null)), name)
    }

    @Test fun chatLinesReadAsHypixelWordsThem() {
        fun plain(s: String) = s.replace(Regex("§."), "")
        assertEquals("PUZZLE SOLVED! Pat tied Tic Tac Toe! Good job!", plain(PuzzleLines.solved("Pat", "tied Tic Tac Toe!")))
        assertEquals("PUZZLE FAIL! Pat killed a Blaze in the wrong order! Yikes!", plain(PuzzleLines.failed("Pat", "killed a Blaze in the wrong order!")))
        assertEquals("PUZZLE FAIL! Pat was fooled by Bob! Yikes!", plain(PuzzleLines.failed("Pat", "was fooled by §r§cBob§r§e!")))
        assertEquals("PUZZLE FAIL! Pat pushed the silverfish into a Trap! That's a whoopsie!", plain(PuzzleLines.failed("Pat", "pushed the silverfish into a Trap! That's a whoopsie!")))
        assertTrue(PuzzleLines.failed("Pat", "x!").endsWith("§r" + PuzzleLines.YIKES))
        assertTrue(PuzzleLines.solved("Pat", "x!").endsWith("§r" + PuzzleLines.GOOD_JOB))
        assertEquals("You used the Architect's First Draft to reset Three Weirdos!", plain(PuzzleLines.reset("Three Weirdos")))
        assertEquals("§r§aYou used the §r§5Architect's First Draft §r§ato reset §r§6Tic Tac Toe§r§a!", PuzzleLines.reset("Tic Tac Toe"))
        assertEquals("Moved 1 Architect's First Draft from your Sacks to your inventory.", plain(PuzzleLines.SACK_MOVED))
        assertEquals("Oops! You stepped on the wrong block!", plain(PuzzleLines.ICE_WRONG_BLOCK))
        assertEquals("Don't move diagonally! Bad!", plain(PuzzleLines.ICE_DIAGONAL))
        assertTrue(PuzzleLines.ICE_WRONG_BLOCK.startsWith("§c") && PuzzleLines.ICE_DIAGONAL.startsWith("§c"))
        // Odin's failure prompt keys on this shape.
        assertTrue(Regex("^PUZZLE FAIL! (\\w{1,16}) .+$").matches(plain(PuzzleLines.failed("Pat", "lost Tic Tac Toe!"))))
    }

    @Test fun packKindsMapToCodes() {
        assertEquals("BLAZE", PuzzleKinds.fromPackKind("blaze_upper"))
        assertEquals("BEAMS", PuzzleKinds.fromPackKind("creeper_beams"))
        assertEquals("ICE_FILL", PuzzleKinds.fromFamily("Ice_Fill"))
        assertEquals(null, PuzzleKinds.fromPackKind("bomb_defuse"))
        assertEquals(10, PuzzleKinds.tabNames.size)
    }

    // ---- the draw -----------------------------------------------------------------------------------------------

    @Test fun puzzleCountFollowsTheMeasuredOdds() {
        val rnd = Random(11)
        val n = 20000
        val counts = IntArray(6)
        repeat(n) { counts[PuzzleDraw.drawCount(rnd)]++ }
        val expected = mapOf(2 to 0.287, 3 to 0.313, 4 to 0.252, 5 to 0.149)
        for ((k, p) in expected) assertTrue(Math.abs(counts[k].toDouble() / n - p) < 0.015, "count $k: ${counts[k].toDouble() / n} vs $p")
        assertEquals(0, counts[0] + counts[1])
    }

    @Test fun theDrawNeverTakesBothBlazeRoomsAndHigherOrLowerIsTwiceAsLikely() {
        val rnd = Random(5)
        var blaze = 0; var boulder = 0
        repeat(5000) {
            val d = PuzzleDraw.draw(rnd)
            assertTrue(d.size in 2..5)
            assertEquals(d.size, d.map { it.code }.toSet().size)
            assertEquals(d.size, d.map { it.room }.toSet().size)
            if (d.any { it.code == PuzzleKinds.BLAZE }) blaze++
            if (d.any { it.code == PuzzleKinds.BOULDER }) boulder++
        }
        assertTrue(blaze.toDouble() / boulder in 1.4..2.2, "blaze $blaze vs boulder $boulder")
    }

    @Test fun theStageGateIsTheDungeon() {
        assertTrue(RunStage.CLEAR.inDungeon)
    }
}
