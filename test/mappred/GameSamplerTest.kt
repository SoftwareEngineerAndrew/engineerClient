package com.engineerclient.mappred

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The game-process sampler (F7's default), on synthetic maps only. */
class GameSamplerTest {

    /**
     * A whole F7 map, made up: drawn by the game-process model itself (tools/mappred sampler3.mjs, from a map showing
     * only the entrance), not a recorded one. Each tile's room as a letter (tile = z * 6 + x); each room's kind in letter
     * order (E entrance, n big, o normal 1x1, P puzzle, T trap, Y yellow, B blood, F fairy); the doors between tiles,
     * the wither path's marked W and blood's B. Big rooms 2, 1, 1, 2, 1; 3 puzzles; blood (5) 10 steps out through the
     * 2x2 D, the fairy (17) 4 before it.
     */
    private val grid = listOf("IABDDJ", "KGBDDL", "MGBEEN", "CCOEEP", "HQRSTU", "HHFFFF")
    private val kinds = "EnnnnnnnYBoooFooPPoTP"
    private val doors = "0-6 4-5B 6-12 1-7 10-11W 11-17W 12-13 13-14W 14-15W 16-17W 19-20 14-20 22-23 18-24 19-25 20-26 21-27 22-28 23-29 27-33"

    private fun tilesOf(room: Char) = (0 until 36).filter { grid[it / 6][it % 6] == room }.toIntArray()

    private fun kindOf(room: Char) = when (kinds[room - 'A']) {
        'E' -> Kind.ENTRANCE; 'P' -> Kind.PUZZLE; 'T' -> Kind.TRAP; 'Y' -> Kind.YELLOW; 'B' -> Kind.BLOOD; 'F' -> Kind.FAIRY
        else -> Kind.NORMAL
    }

    /**
     * The whole map with [hidden]'s rooms not on it yet, [names] on the rooms someone has been in; as the game's map
     * shows it ([MapView.connectedPart]: rooms only reached through a hidden one are left out too).
     */
    private fun shown(hidden: String, names: Map<Char, String> = emptyMap()): MapView {
        val v = MapView(6, 6, 3, FloorRules.F7)
        for (r in 'A' until 'A' + kinds.length) if (r !in hidden) assertNotNull(v.addRoom(kindOf(r), tilesOf(r), names[r]))
        val byPair = doors.split(' ').associate { d ->
            val k = when (d.last()) { 'W' -> Edge.WITHER; 'B' -> Edge.BLOOD; else -> Edge.DOOR }
            val (a, b) = d.trimEnd('W', 'B').split('-').map { it.toInt() }
            (a to b) to k
        }
        v.closeEdges { a, b -> byPair[minOf(a, b) to maxOf(a, b)] }
        return v.connectedPart()
    }

    /** Entrance at (1,0), a 2x2 behind it, a door of kind [down] from (2,2) to (2,3), plain doors east and west. */
    private fun opening(down: Byte = Edge.WITHER, rules: FloorRules = FloorRules.F7, puzzles: Int = 3): MapView {
        val v = MapView(6, 6, puzzles, rules)
        assertNotNull(v.addRoom(Kind.ENTRANCE, intArrayOf(1))); assertNotNull(v.addRoom(Kind.NORMAL, intArrayOf(7, 8, 13, 14)))
        val d = mapOf((1 to 7) to Edge.DOOR, (14 to 20) to down, (8 to 9) to Edge.DOOR, (6 to 7) to Edge.DOOR)
        v.closeEdges { a, b -> d[minOf(a, b) to maxOf(a, b)] }
        return v
    }

    /** As OdinMapPrediction's worker runs a predictor (a chunk at a time until enough, or nothing more to get); the chunks it took. */
    private fun drive(p: MapPredictor, samples: Int, rnd: Random): Int {
        var calls = 0
        while (p.accepted < samples) {
            calls++
            assertTrue(calls < 5_000, "still running after $calls chunks")
            val kept = p.run(100, 40_000, rnd)
            if (kept == 0 && (p.accepted > 0 || p.level > 2)) break
        }
        return calls
    }

    private fun predict(v: MapView, gameProcess: Boolean = true, samples: Int = 2000): Prediction {
        val p = MapPredictor(v, gameProcess)
        assertEquals(gameProcess, p.usesGameProcess)
        drive(p, samples, Random(7))
        val pr = assertNotNull(p.prediction())
        assertEquals(gameProcess, p.usesGameProcess)
        return pr
    }

    private fun total(p: Prediction, c: Int) = p.raw.sumOf { (it?.get(c) ?: 0f).toDouble() }

    @Test fun f7GoesByTheGameProcessAndTheOtherFloorsDont() {
        assertTrue(FloorRules.F7.game)
        for (f in 1..6) assertFalse(assertNotNull(FloorRules.of(f)).game)
        assertTrue(MapPredictor(opening()).usesGameProcess)
        assertFalse(MapPredictor(opening(), gameProcess = false).usesGameProcess)
        for (rules in listOf(FloorRules.F1, FloorRules.F3, FloorRules.F5, FloorRules.F6)) {
            assertFalse(MapPredictor(opening(rules = rules)).usesGameProcess)
            assertFalse(MapPredictor(opening(rules = rules), gameProcess = true).usesGameProcess)
        }
    }

    @Test fun fivePuzzleMapsGoByTheClassicSampler() {
        for (n in 2..4) assertTrue(MapPredictor(opening(puzzles = n)).usesGameProcess, "$n puzzles")
        assertFalse(MapPredictor(opening(puzzles = 5)).usesGameProcess)
        assertFalse(MapPredictor(opening(puzzles = 5), gameProcess = true).usesGameProcess)
    }

    @Test fun bothModelsKeepEverySpecialOnce() {
        for (game in listOf(true, false)) {
            val p = predict(opening(), game, 400)
            assertEquals(0, p.level)
            for (c in intArrayOf(Cls.TRAP, Cls.YELLOW, Cls.BLOOD, Cls.FAIRY)) assertEquals(1.0, total(p, c), 1e-4, "game $game class $c")
            assertEquals(3.0, total(p, Cls.PUZZLE), 1e-4)
        }
    }

    @Test fun everyTilesChancesAddUpToOne() {
        for (v in listOf(opening(), shown("ICKMOPHQRSTUF"), shown("QTU"), shown("CHFQRS"))) {
            val p = predict(v)
            for (t in 0 until 36) {
                if (v.roomAt[t] >= 0) continue
                val pr = assertNotNull(p.probs[t], "tile $t")
                assertEquals(1f, pr.sum(), 1e-4f, "tile $t")
                assertTrue(pr.contentEquals(assertNotNull(p.raw[t])), "nothing calibrated")
            }
        }
    }

    @Test fun theLastRoomIsWhatTheRestLeave() {
        // a puzzle: the one special the map doesn't show yet
        assertEquals(1f, assertNotNull(predict(shown("Q")).probs[25])[Cls.PUZZLE], 1e-4f)
        // a normal 1x1 between M and the yellow room in the corner, which is only reached through it
        val k = predict(shown("K"))
        assertEquals(1f, assertNotNull(k.probs[6])[Cls.NORMAL], 1e-4f)
        assertEquals(1f, assertNotNull(k.probs[0])[Cls.YELLOW], 1e-4f)
        // the 1x4: every other big room shown, and the only mix with them (no 6-room one) has a 1x4 more
        val f = predict(shown("F"))
        for (t in 32..35) assertEquals(1f, assertNotNull(f.probs[t])[Cls.BIG], 1e-4f)
        assertEquals(listOf(32, 33, 34, 35), f.bigRooms.single().tiles.toList())
    }

    @Test fun aShownBloodRoomLeavesNoBloodOrFairyToPredict() {
        // the way to blood on the map (entrance, G, B, E, the fairy N, L, D, blood J), the rest not
        val v = shown("ICKMOPHQRSTUF")
        val p = predict(v)
        assertEquals(0, p.level)
        for (t in 0 until 36) {
            val pr = p.probs[t] ?: continue
            assertEquals(0f, pr[Cls.BLOOD], "tile $t"); assertEquals(0f, pr[Cls.FAIRY], "tile $t")
        }
        assertEquals(1.0, total(p, Cls.TRAP), 1e-4); assertEquals(1.0, total(p, Cls.YELLOW), 1e-4)
        assertEquals(3.0, total(p, Cls.PUZZLE), 1e-4)
        // nothing on the way to blood is left, so no predicted door is a wither or blood door
        for (d in p.doors) { assertEquals(0f, d.wither, 1e-4f); assertEquals(0f, d.blood, 1e-4f) }
        // each side of the map behind a door holds its rooms' share
        assertEquals(19f, p.splits.sumOf { it.tiles.toDouble() }.toFloat(), 1e-3f)
    }

    @Test fun bloodIsWhereTheWitherPathCanEnd() {
        // the 2x2 E and what's past it hidden, the wither door into it shown: blood 9-10 steps out, the fairy 4 before
        val v = shown("ENLDJ")
        val p = predict(v)
        assertEquals(1.0, total(p, Cls.BLOOD), 1e-4)
        assertEquals(1.0, total(p, Cls.FAIRY), 1e-4)
        val wither = p.splits.single { it.door == Edge.WITHER }
        assertEquals(15, wither.tile)
        assertEquals(1f, wither.expect[Cls.BLOOD], 1e-4f)
        assertTrue(assertNotNull(p.probs[5])[Cls.BLOOD] > 0f, "the real blood is among the samples'")
        assertTrue(p.doors.any { it.wither > 0.5f * it.chance })
    }

    @Test fun theTabsOrderPinsTheHiddenPuzzles() {
        // the trap and two puzzles hidden at (1,4), (4,4), (5,4); the puzzle at (2,4) named and on the map. In the tab's
        // order (column by column) (1,4) comes before it, the other two after
        val names = mapOf('R' to "Quiz")
        val free = predict(shown("QTU", names))
        for (t in intArrayOf(25, 28, 29)) assertEquals(2f / 3, assertNotNull(free.probs[t])[Cls.PUZZLE], 1e-4f)
        val second = predict(shown("QTU", names).also { it.puzzleSlots = listOf(null, "Quiz", null) })
        assertEquals(1f, assertNotNull(second.probs[25])[Cls.PUZZLE], 1e-4f)
        for (t in intArrayOf(28, 29)) {
            assertEquals(0.5f, assertNotNull(second.probs[t])[Cls.PUZZLE], 1e-4f)
            assertEquals(0.5f, assertNotNull(second.probs[t])[Cls.TRAP], 1e-4f)
        }
        val first = predict(shown("QTU", names).also { it.puzzleSlots = listOf("Quiz", null, null) })
        assertEquals(1f, assertNotNull(first.probs[25])[Cls.TRAP], 1e-4f)
        for (t in intArrayOf(28, 29)) assertEquals(1f, assertNotNull(first.probs[t])[Cls.PUZZLE], 1e-4f)
    }

    @Test fun theSecretTotalNamesTheLastRoom() {
        // every room named but the hidden 1x1 at (0,1) and the yellow room behind it in the corner; what the total
        // leaves is 4 secrets (yellow rooms have none), and of the 1x1 names still free only Raccoon has 4 (Logs, the
        // other, is on the map)
        val used = HashSet<String>()
        fun free(pool: Int) = MapStats.NAMES.first { it.pool == pool && it.name !in used }.name
        val names = HashMap<Char, String>()
        for (r in 'A' until 'A' + kinds.length) {
            if (r == 'K' || r == 'I') continue
            val tiles = tilesOf(r)
            val name = when (kinds[r - 'A']) {
                'E', 'B', 'F' -> continue  // named by their kind
                'P' -> free(MapStats.POOL_PUZZLE); 'T' -> free(MapStats.POOL_TRAP); 'Y' -> free(MapStats.POOL_YELLOW)
                'o' -> if ("Logs" in used) free(MapStats.POOL_1X1) else "Logs"
                else -> free(assertNotNull(MapView.shapeOf(tiles, 6)).ordinal)
            }
            used += name; names[r] = name
        }
        val v = shown("KI", names)
        val secrets = names.values.sumOf { n -> MapStats.NAMES.first { it.name == n }.secrets }
        v.secretTotals = intArrayOf(secrets + 4)
        val p = predict(v)
        val named = p.namedRooms.filter { it.tiles.contentEquals(intArrayOf(6)) }
        assertEquals("Raccoon", named.maxBy { it.chance }.name.name)
        assertEquals(1f, named.maxBy { it.chance }.chance, 1e-3f)
        // a total nothing fits (a misread one): the names are dropped rather than the map
        val corner = p.namedRooms.filter { it.tiles.contentEquals(intArrayOf(0)) }
        assertEquals(1f, corner.sumOf { it.chance.toDouble() }.toFloat(), 1e-3f)
        assertTrue(corner.all { it.name.pool == MapStats.POOL_YELLOW })
        val wrong = shown("KI", names).also { it.secretTotals = intArrayOf(secrets + 40) }
        assertEquals(1f, assertNotNull(predict(wrong).probs[6])[Cls.NORMAL], 1e-4f)
    }

    @Test fun aMapTheGameCantMakeFallsBackToTheClassicSampler() {
        // a blood door 3-4 steps from the entrance: the game's blood is 9-10 steps out, only the classic sampler's
        // looser levels fit it; the worker's loop still ends, with blood behind the door
        val p = MapPredictor(opening(Edge.BLOOD))
        assertTrue(p.usesGameProcess)
        drive(p, 400, Random(3))
        assertFalse(p.usesGameProcess)
        assertEquals(1f, assertNotNull(assertNotNull(p.prediction()).probs[20])[Cls.BLOOD], 1e-4f)
    }

    @Test fun aMapTheGameBarelyFitsFallsBackTooRatherThanShowingAHandfulOfSamples() {
        // the budget out after the first batch, as on a hard map where 120,000 particles keep a handful (11 to 21
        // samples from one or two ancestors scored -33 on two held-out maps the classic sampler got -10 and -18 on)
        val p = MapPredictor(shown("ICKMOPHQRSTUF"))
        val g = assertNotNull(p.game)
        g.triesCap = GameSampler.PARTICLES.toLong()
        drive(p, 400, Random(3))
        assertTrue(g.kept > 0, "some were kept, just too few batches' worth: ${g.kept} samples, ${g.effectiveBatches()} batches")
        assertTrue(g.failed)
        assertFalse(p.usesGameProcess)
        assertEquals(0, assertNotNull(p.prediction()).level)
        assertTrue(p.accepted >= 400)
    }

    @Test fun aGameSamplerThatThrowsIsDroppedForTheClassicOne() {
        // nothing in the sampler is meant to throw; if something does the worker shouldn't be left with no prediction
        val boom = object : Random() { override fun nextBits(bitCount: Int): Int = throw IllegalStateException("boom") }
        val p = MapPredictor(shown("ICKMOPHQRSTUF"))
        assertTrue(p.usesGameProcess)
        assertEquals(0, p.run(100, 40_000, boom))
        assertFalse(p.usesGameProcess)
        assertEquals("boom", p.gameError?.message)
        assertEquals(null, p.prediction())
        drive(p, 400, Random(3))
        assertTrue(p.accepted >= 400)
        assertNotNull(p.prediction())
    }

    @Test fun nothingIsShownUntilSeveralBatchesBackTheSamples() {
        // the first batch's samples come from few ancestors; a prediction from them would show rooms as near certain
        val p = MapPredictor(shown("ICKMOPHQRSTUF"))
        val g = assertNotNull(p.game)
        val rnd = Random(5)
        var calls = 0
        while (g.kept == 0) { p.run(1, 40_000, rnd); assertTrue(++calls < 1_000) }
        assertEquals(1.0, g.effectiveBatches(), 1e-9)
        assertEquals(null, p.prediction())
        assertEquals(0, p.accepted)
        while (p.prediction() == null) { p.run(1, 40_000, rnd); assertTrue(++calls < 10_000) }
        assertTrue(g.done || g.effectiveBatches() >= GameSampler.MIN_SHOW)
        assertTrue(p.usesGameProcess)
    }

    @Test fun theSecretTotalIsOnlyGivenUpOnIfTheNamesWereInTheWay() {
        // nothing fits (a blood door 3-4 steps out), and a secret total is known. The names only come in with 4 unknown
        // rooms or fewer and this map has dozens, so taking the total away couldn't help: one round of failed tries, not two
        val v = opening(Edge.BLOOD).also { it.secretTotals = intArrayOf(60) }
        val p = MapPredictor(v)
        val g = assertNotNull(p.game)
        assertTrue(g.secretsOn)
        drive(p, 400, Random(3))
        assertFalse(p.usesGameProcess)
        assertTrue(g.tries < 2 * GameSampler.EMPTY_TRIES, "${g.tries} tries")
        assertEquals(0, g.kept)
    }

    @Test fun theGameProcessWorksInChunksAndThenStops() {
        val p = MapPredictor(shown("ICKMOPHQRSTUF"))
        val rnd = Random(11)
        val calls = drive(p, 2000, rnd)
        assertTrue(calls > 1, "all in one chunk")
        assertTrue(p.accepted > 0)
        // on to the end: a chunk that adds nothing, soon
        var more = 0
        while (p.run(100, 40_000, rnd) > 0) assertTrue(++more < 1_000)
        assertTrue(p.usesGameProcess)
    }

    @Test fun nothingToPredictEndsAtOnce() {
        // every room on the map, or no door into the unknown yet: no samples, and the worker's loop stops (level 3)
        for (v in listOf(shown(""), MapView(6, 6, 3, FloorRules.F7).also { it.addRoom(Kind.ENTRANCE, intArrayOf(1)); it.closeEdges { _, _ -> null } })) {
            val p = MapPredictor(v)
            assertEquals(1, drive(p, 2000, Random(1)))
            assertEquals(3, p.level)
            assertEquals(null, p.prediction())
        }
    }

    @Test fun aMixWeighsByTheWaysItsRoomsCanBeTheOnesShown() {
        // a 1x3 on the map: a mix with two 1x3s twice as likely as it was (either could be the one shown), one with none ruled out
        val none = IntArray(5); val one = intArrayOf(0, 1, 0, 0, 0); val two = intArrayOf(0, 2, 0, 0, 0)
        for (i in GameStats.MIXES.indices) {
            val c = GameStats.MIXES[i][1]; val base = GameSampler.mixWeight(i, none)
            assertTrue(base > 0)
            assertEquals(base * c, GameSampler.mixWeight(i, one), base * 1e-9)
            assertEquals(base * c * (c - 1).coerceAtLeast(0), GameSampler.mixWeight(i, two), base * 1e-9)
        }
        // the 6-room mixes are there too (1% of maps), each with its success rate
        assertTrue(GameStats.MIXES.any { m -> (0 until 5).sumOf { m[it] } == 6 })
        assertEquals(GameStats.MIXES.size, GameStats.MIX_RATE.size)
    }
}
