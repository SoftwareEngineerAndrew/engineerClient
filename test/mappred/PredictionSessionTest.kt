package com.engineerclient.mappred

import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The pool of samples carried from map to map, the lanes and the session the worker drives ([PredictionSession]), on a made-up F7 map only. */
class PredictionSessionTest {

    // the same made-up map as GameSamplerTest's (drawn by the game-process model itself, not a recorded one): each tile's room as a letter
    private val grid = listOf("IABDDJ", "KGBDDL", "MGBEEN", "CCOEEP", "HQRSTU", "HHFFFF")
    private val kinds = "EnnnnnnnYBoooFooPPoTP"
    private val doors = "0-6 4-5B 6-12 1-7 10-11W 11-17W 12-13 13-14W 14-15W 16-17W 19-20 14-20 22-23 18-24 19-25 20-26 21-27 22-28 23-29 27-33"

    private fun tilesOf(room: Char) = (0 until 36).filter { grid[it / 6][it % 6] == room }.toIntArray()

    private fun kindOf(room: Char) = when (kinds[room - 'A']) {
        'E' -> Kind.ENTRANCE; 'P' -> Kind.PUZZLE; 'T' -> Kind.TRAP; 'Y' -> Kind.YELLOW; 'B' -> Kind.BLOOD; 'F' -> Kind.FAIRY
        else -> Kind.NORMAL
    }

    /** The map with [hidden]'s rooms not on it yet, [names] on the rooms someone has been in, as the game shows it. */
    private fun shown(hidden: String, names: Map<Char, String> = emptyMap(), totals: IntArray = IntArray(0), slots: List<String?> = emptyList()): MapView {
        val v = MapView(6, 6, 3, FloorRules.F7)
        for (r in 'A' until 'A' + kinds.length) if (r !in hidden) assertNotNull(v.addRoom(kindOf(r), tilesOf(r), names[r]))
        val byPair = doors.split(' ').associate { d ->
            val k = when (d.last()) { 'W' -> Edge.WITHER; 'B' -> Edge.BLOOD; else -> Edge.DOOR }
            val (a, b) = d.trimEnd('W', 'B').split('-').map { it.toInt() }
            (a to b) to k
        }
        v.closeEdges { a, b -> byPair[minOf(a, b) to maxOf(a, b)] }
        v.secretTotals = totals
        v.puzzleSlots = slots
        return v.connectedPart()
    }

    // the way to blood on the map, then M (a 1x1 off the first big room), O (off the first 1x3), R (off O): each a room more
    private val h0 = "ICKMOPHQRSTUF"
    private val h1 = "ICKOPHQRSTUF"
    private val h2 = "ICKPHQRSTUF"
    private val h3 = "ICKPHQSTUF"
    // few rooms left: the last ones are quick to work out, the pool large
    private val late = "QTU"

    /** A sampler for [v] that stores what it keeps; it has run [particles] started. */
    private fun lane(v: MapView, particles: Int, seed: Int = 1): GameSampler {
        val g = assertNotNull(MapPredictor(v).game)
        g.store = ArrayList()
        g.runBatches(particles, Random(seed))
        return g
    }

    private fun freshGame(v: MapView) = assertNotNull(MapPredictor(v).game)

    private fun maxDiff(a: Prediction, b: Prediction): Float {
        var m = 0f
        for (t in 0 until 36) {
            val x = a.raw[t]; val y = b.raw[t]
            if (x == null || y == null) { assertEquals(x == null, y == null, "tile $t"); continue }
            for (c in x.indices) m = maxOf(m, abs(x[c] - y[c]))
        }
        return m
    }

    // ---- the refinement check ----

    @Test fun aViewWithMoreRoomsAndDoorsAndNamesRefinesTheOneBefore() {
        val a = shown(h0); val b = shown(h1); val c = shown(h2, mapOf('M' to "Logs"))
        assertTrue(a.refines(a)); assertTrue(b.refines(a)); assertTrue(c.refines(b)); assertTrue(c.refines(a))
        assertFalse(a.refines(b)); assertFalse(b.refines(c))
        // names, the secret totals and the tab's puzzles may be added, and a total may change
        assertTrue(shown(h0, mapOf('G' to "Mushroom")).refines(a))
        assertTrue(shown(h0, totals = intArrayOf(50, 51)).refines(shown(h0, totals = intArrayOf(51, 52))))
        assertTrue(shown(h0).also { it.puzzleSlots = listOf(null, "Quiz", null) }.refines(a))
    }

    @Test fun aViewThatChangesWhatTheOneBeforeSaidIsNoRefinement() {
        val a = shown(h1, mapOf('G' to "Mushroom"))
        // a known room with another kind, another name, or gone; a different floor or puzzle count
        val kind = MapView(6, 6, 3, FloorRules.F7)
        for (r in 'A' until 'A' + kinds.length) if (r !in h1) kind.addRoom(if (r == 'K' + 1) Kind.PUZZLE else kindOf(r), tilesOf(r), if (r == 'G') "Mushroom" else null)
        val doorsOf = doors.split(' ').associate { d ->
            val (x, y) = d.trimEnd('W', 'B').split('-').map { it.toInt() }
            (x to y) to when (d.last()) { 'W' -> Edge.WITHER; 'B' -> Edge.BLOOD; else -> Edge.DOOR }
        }
        kind.closeEdges { x, y -> doorsOf[minOf(x, y) to maxOf(x, y)] }
        assertFalse(kind.connectedPart().refines(a))
        assertFalse(shown(h1, mapOf('G' to "Dome")).refines(a))
        assertFalse(shown(h0, mapOf('G' to "Mushroom")).refines(a))
        assertFalse(shown(h1).refines(shown(h1, mapOf('G' to "Mushroom"))))
        val otherPuzzles = MapView(6, 6, 4, FloorRules.F7).also { v -> for (r in 'A' until 'A' + kinds.length) if (r !in h1) v.addRoom(kindOf(r), tilesOf(r)) }
        assertFalse(otherPuzzles.refines(shown(h1)))
        // a door that changed kind, a wall that became a door
        val wither = MapView(6, 6, 3, FloorRules.F7)
        for (r in 'A' until 'A' + kinds.length) if (r !in h0) wither.addRoom(kindOf(r), tilesOf(r))
        wither.closeEdges { x, y -> doorsOf[minOf(x, y) to maxOf(x, y)]?.let { k -> if (minOf(x, y) == 1 && maxOf(x, y) == 7) Edge.WITHER else k } }
        assertFalse(wither.connectedPart().refines(shown(h0)))
        // the tab's puzzle lines gone or named differently
        val named = shown(h0).also { it.puzzleSlots = listOf(null, "Quiz", null) }
        assertFalse(shown(h0).refines(named))
        assertFalse(shown(h0).also { it.puzzleSlots = listOf(null, "Boulder", null) }.refines(named))
        assertTrue(shown(h0).also { it.puzzleSlots = listOf("Weirdos", "Quiz", null) }.refines(named))
    }

    /** A 5-puzzle opening (as GameSamplerTest's): the entrance, a 2x2 behind it, a brown 1x1 of [kind] east of it (one door), [extra] doors more. */
    private fun brownOneByOne(kind: Kind, extra: Map<Pair<Int, Int>, Byte> = emptyMap(), boxKind: Kind = Kind.NORMAL): MapView {
        val v = MapView(6, 6, 5, FloorRules.F7)
        assertNotNull(v.addRoom(Kind.ENTRANCE, intArrayOf(1))); assertNotNull(v.addRoom(boxKind, intArrayOf(7, 8, 13, 14)))
        assertNotNull(v.addRoom(kind, intArrayOf(9)))
        val d = mapOf((1 to 7) to Edge.DOOR, (14 to 20) to Edge.WITHER, (8 to 9) to Edge.DOOR, (6 to 7) to Edge.DOOR) + extra
        v.closeEdges { a, b -> d[minOf(a, b) to maxOf(a, b)] }
        return v
    }

    @Test fun aBrownDeadEndReadNormalThenRareIsNoChangeToTheGameProcess() {
        // Odin has a brown room it has only seen on the map as rare until it scans its core (OdinMapPrediction reads that as
        // normal, then rare once the core says so): a 1x1 with one door is a rare room to the game process either way
        val normal = brownOneByOne(Kind.NORMAL); val rare = brownOneByOne(Kind.RARE)
        assertTrue(rare.refines(normal)); assertTrue(normal.refines(rare))
        val a = lane(normal, 20_000, 9); val b = lane(rare, 20_000, 9)
        assertTrue(a.kept > 50, "${a.kept} kept")
        assertEquals(a.kept, b.kept); assertEquals(a.tally.weight, b.tally.weight)
        assertEquals(0f, maxDiff(assertNotNull(a.predictionOf(a.tally, a.kept)), assertNotNull(b.predictionOf(b.tally, b.kept))))
        // the samples carried from one reading to the other all fit, at the same weight
        val r = freshGame(rare).reuse(a.store!!)
        assertEquals(a.kept, r.survivors.size); assertEquals(a.tally.weight, r.part.tally.weight, a.tally.weight * 1e-12)
        // not so for a 1x1 with two doors (a rare room is a dead end), nor a big room
        val two = mapOf((9 to 10) to Edge.DOOR)
        assertFalse(brownOneByOne(Kind.RARE, two).refines(brownOneByOne(Kind.NORMAL, two)))
        assertFalse(brownOneByOne(Kind.NORMAL, boxKind = Kind.RARE).refines(normal))
        assertFalse(brownOneByOne(Kind.PUZZLE).refines(normal))
    }

    @Test fun twoViewsWithTheSameRoomsAndDoorsDrawFromTheSameLaw() {
        assertEquals(shown(h1).structureKey(), shown(h1, mapOf('G' to "Mushroom"), intArrayOf(40)).structureKey())
        assertTrue(shown(h1).structureKey() != shown(h2).structureKey())
        assertTrue(shown(h1).key() != shown(h1, mapOf('G' to "Mushroom")).key())
    }

    // ---- reuse ----

    @Test fun samplesCarriedIntoAnUnchangedViewMakeTheTallyAgain() {
        // a view that only got a name (nothing a sample's weight reads): every sample fits, with the same weight, in the same order
        val g = lane(shown(h0), 20_000)
        assertTrue(g.kept > 100)
        val again = freshGame(shown(h0, mapOf('G' to "Mushroom")))
        val r = again.reuse(g.store!!)
        assertEquals(g.kept, r.survivors.size)
        assertEquals(g.kept, r.part.kept)
        assertEquals(g.tally.weight, r.part.tally.weight)
        assertEquals(g.part().w1, r.part.w1); assertEquals(g.part().w2, r.part.w2)
        for (i in g.tally.counts.indices) assertTrue(g.tally.counts[i].contentEquals(r.part.tally.counts[i]), "tile $i")
        assertEquals(g.tally.bigCounts, r.part.tally.bigCounts)
        assertEquals(g.tally.doorCounts.keys, r.part.tally.doorCounts.keys)
        val p = assertNotNull(g.predictionOf(g.tally, g.kept)); val q = assertNotNull(again.predictionOf(r.part.tally, r.part.kept))
        assertEquals(0f, maxDiff(p, q))
    }

    @Test fun samplesCarriedInTwoCutsAtABatchAddUpToTheSameAsInOne() {
        // the lanes of a session share a big import cut at batch boundaries; the cuts' parts add up
        val g = lane(shown(h1), 40_000, 51)
        val st = g.store!!
        var cut = st.size / 2
        while (st[cut].src == st[cut - 1].src && st[cut].batch == st[cut - 1].batch) cut++
        val b = shown(h2)
        val whole = freshGame(b).reuse(st)
        val x = freshGame(b).reuse(st.subList(0, cut)); val y = freshGame(b).reuse(st.subList(cut, st.size))
        assertEquals(whole.survivors.size, x.survivors.size + y.survivors.size)
        assertEquals(whole.part.kept, x.part.kept + y.part.kept)
        assertEquals(whole.part.tally.weight, x.part.tally.weight + y.part.tally.weight, whole.part.tally.weight * 1e-9)
        assertEquals(whole.part.w1, x.part.w1 + y.part.w1, whole.part.w1 * 1e-9)
        assertEquals(whole.part.w2, x.part.w2 + y.part.w2, whole.part.w2 * 1e-9)
        assertEquals(whole.part.s2, x.part.s2 + y.part.s2, whole.part.s2 * 1e-9)
    }

    @Test fun aSampleThatDoesntFitTheViewIsLeftOutAndNeverComesBack() {
        val a = shown(h1)
        val g = lane(a, 40_000)
        val b = shown(h2)
        val r = freshGame(b).reuse(g.store!!)
        assertTrue(r.survivors.size > 0 && r.survivors.size < g.kept, "${r.survivors.size} of ${g.kept}")
        // again, into a view that shows yet more: only the survivors can fit, and the same ones do
        val c = shown(h3)
        val viaB = freshGame(c).reuse(r.survivors)
        val direct = freshGame(c).reuse(g.store!!)
        assertEquals(direct.survivors.size, viaB.survivors.size)
        assertEquals(direct.part.tally.weight, viaB.part.tally.weight)
        // every survivor has O where the map shows it
        for (s in r.survivors) {
            val rooms = tilesOf('O')
            assertTrue(rooms.all { s.whole.owner[it] == s.whole.owner[rooms[0]] })
        }
    }

    /** Every sample drawn for [later] (a view that says all [earlier] did and more) fits [earlier] and has a finish there: nothing the pool kept of [earlier] is missing for [later]. */
    private fun everySampleOfTheLaterViewIsOneOfTheEarlier(label: String, earlier: MapView, later: MapView, particles: Int = 30_000) {
        assertTrue(later.refines(earlier), "$label: not a refinement")
        val st = lane(later, particles, 7).store!!
        assertTrue(st.size > 50, "$label: only ${st.size} samples")
        // the roles of the totals' check are the other way round here
        for (s in st) { s.names = false }
        val r = freshGame(earlier).reuse(st)
        assertEquals(st.size, r.survivors.size, label)
    }

    @Test fun whatTheViewBeforeLetThroughHoldsEverySampleOfTheViewAfter() {
        // the pool of the earlier view is complete for the later one only if no map the later view allows was thrown away for the earlier
        everySampleOfTheLaterViewIsOneOfTheEarlier("1x1 off the first big room", shown(h0), shown(h1))
        everySampleOfTheLaterViewIsOneOfTheEarlier("1x1 off the 1x3", shown(h1), shown(h2))
        everySampleOfTheLaterViewIsOneOfTheEarlier("puzzle", shown(h2), shown(h3))
        everySampleOfTheLaterViewIsOneOfTheEarlier("yellow", shown("ICPHQSTUF"), shown("CPHQSTUF"))
        everySampleOfTheLaterViewIsOneOfTheEarlier("trap", shown(h3), shown("ICKPHQSUF"))
        everySampleOfTheLaterViewIsOneOfTheEarlier("2x2 and the blood door", shown("DJMOPHQRSTUF"), shown("JMOPHQRSTUF"))
        everySampleOfTheLaterViewIsOneOfTheEarlier("blood", shown("JMOPHQRSTUF"), shown("MOPHQRSTUF"))
        everySampleOfTheLaterViewIsOneOfTheEarlier("2x2 on the way", shown("ENLDJPUSFT"), shown("NLDJPUSFT"))
        everySampleOfTheLaterViewIsOneOfTheEarlier("1x3", shown("BENLDJPUSFTOCHQR"), shown("ENLDJPUSFTOCHQR"))
        everySampleOfTheLaterViewIsOneOfTheEarlier("two rooms at once", shown(h0), shown(h2))
        // the tab's puzzle lines, and a puzzle room's name
        val (nm, _) = namedRooms("")
        val base = "IKPHSTUF"
        everySampleOfTheLaterViewIsOneOfTheEarlier("a puzzle named in the tab", shown(base, mapOf('R' to nm['R']!!)),
            shown(base, mapOf('R' to nm['R']!!), slots = listOf(null, nm['R'], null)))
        everySampleOfTheLaterViewIsOneOfTheEarlier("a second puzzle named", shown(base, mapOf('R' to nm['R']!!), slots = listOf(null, nm['R'], null)),
            shown(base, mapOf('R' to nm['R']!!, 'Q' to nm['Q']!!), slots = listOf(nm['Q'], nm['R'], null)))
        // the last rooms with the secret total's names: more rooms named, the total narrowed
        val (names, known) = namedRooms("KI")
        val nameK = MapStats.NAMES.first { it.pool == MapStats.POOL_1X1 && it.name !in names.values }
        val nameI = MapStats.NAMES.first { it.pool == MapStats.POOL_YELLOW && it.name !in names.values }
        val total = known + nameK.secrets + nameI.secrets
        everySampleOfTheLaterViewIsOneOfTheEarlier("a room named, the total narrower", shown("KI", names, intArrayOf(total - 1, total, total + 1)),
            shown("I", names + ('K' to nameK.name), intArrayOf(total, total + 1)))
    }

    @Test fun reusedSamplesAgreeWithAFreshRunOfTheNewView() {
        // M and then O open: the samples drawn before they did, the ones that agree with them, give the prediction of a fresh run
        val a = shown(h1); val b = shown(h2)
        val g = lane(a, 150_000, 11)
        val gb = freshGame(b)
        val r = gb.reuse(g.store!!)
        assertTrue(r.part.batches > 20, "batches' worth of the survivors: ${r.part.batches}")
        val reused = assertNotNull(gb.predictionOf(r.part.tally, r.part.kept))
        val fresh = lane(b, 150_000, 12)
        val f = assertNotNull(fresh.predictionOf(fresh.tally, fresh.kept))
        assertTrue(fresh.effectiveBatches() > 20)
        val d = maxDiff(reused, f)
        assertTrue(d < 0.08f, "the largest difference of a tile's chances: $d")
        var sum = 0.0; var n = 0
        for (t in 0 until 36) { val x = reused.raw[t] ?: continue; val y = f.raw[t]!!; for (c in x.indices) { sum += abs(x[c] - y[c]); n++ } }
        assertTrue(sum / n < 0.01, "mean difference ${sum / n}")
    }

    @Test fun theWeightsOfTwoViewsAreOnOneScaleAfterTheGamesOwnChanceOfTheMap() {
        // C opens, a 1x2: the share of the samples' weight that fits the new view is its chance (new over old), found from
        // a fresh run of each with the scale of each ([GameSampler.scale]): their weights say the same thing
        // (one such share is good to about +-0.5 in its log; without the scales they would be a factor 100 apart)
        val a = shown("CHFQRS"); val b = shown("HFQRS")
        var sum = 0.0; var scales = 0.0
        for (seed in 21..23) {
            val ga = lane(a, 60_000, seed)
            val share = freshGame(b).reuse(ga.store!!).part.tally.weight / ga.tally.weight
            val fb = lane(b, 60_000, seed + 10)
            val viaScales = (fb.scale * fb.tally.weight / fb.tries) / (ga.scale * ga.tally.weight / ga.tries)
            assertTrue(share > 0 && viaScales > 0)
            sum += ln(share / viaScales)
            scales = abs(ln(ga.scale / fb.scale))
        }
        assertTrue(abs(sum / 3) < 0.6, "the log of the share of the weight that fits over the share by the scales, on average: ${sum / 3}")
        // and the scale is not 1: a view with a big room more shown draws the mixes by a smaller total, each shown room 1 / its spots
        assertTrue(scales > 1.0)
    }

    @Test fun aSampleWhoseNamesWeighedIsLeftOutByATotalTheOldOneDidntAllow() {
        // the last rooms with the secret total's names (GameSamplerTest.theSecretTotalNamesTheLastRoom's map): a total outside
        // the one the samples were kept with could have let samples through that were thrown away, so they don't count
        val used = HashSet<String>()
        fun free(pool: Int) = MapStats.NAMES.first { it.pool == pool && it.name !in used }.name
        val names = HashMap<Char, String>()
        for (r in 'A' until 'A' + kinds.length) {
            if (r == 'K' || r == 'I') continue
            val name = when (kinds[r - 'A']) {
                'E', 'B', 'F' -> continue
                'P' -> free(MapStats.POOL_PUZZLE); 'T' -> free(MapStats.POOL_TRAP); 'Y' -> free(MapStats.POOL_YELLOW)
                'o' -> if ("Logs" in used) free(MapStats.POOL_1X1) else "Logs"
                else -> free(assertNotNull(MapView.shapeOf(tilesOf(r), 6)).ordinal)
            }
            used += name; names[r] = name
        }
        val known = names.values.sumOf { n -> MapStats.NAMES.first { it.name == n }.secrets }
        val one = shown("KI", names, intArrayOf(known + 4))
        val g = lane(one, 20_000, 31)
        assertTrue(g.kept > 0 && g.store!!.all { it.names })
        // the same total, or one inside it: they all count (their weights are the same); a total outside it: none
        val same = freshGame(shown("KI", names, intArrayOf(known + 4))).reuse(g.store!!)
        assertEquals(g.kept, same.survivors.size)
        assertEquals(g.tally.weight, same.part.tally.weight, g.tally.weight * 1e-12)
        val wider = lane(shown("KI", names, intArrayOf(known + 3, known + 4)), 20_000, 32)
        val inside = freshGame(shown("KI", names, intArrayOf(known + 4))).reuse(wider.store!!)
        assertTrue(inside.survivors.size in 1..wider.kept, "${inside.survivors.size} of ${wider.kept}")
        assertEquals(0, freshGame(shown("KI", names, intArrayOf(known + 3, known + 4))).reuse(g.store!!).survivors.size)
        assertEquals(0, freshGame(shown("KI", names, intArrayOf(known + 5))).reuse(g.store!!).survivors.size)
        // no total at all any more: the names stop weighing, which the old samples can't say
        assertEquals(0, freshGame(shown("KI", names)).reuse(g.store!!).survivors.size)
    }

    /** Names for every room but [skip]'s, one each from its pool, and what their secrets add up to. */
    private fun namedRooms(skip: String): Pair<Map<Char, String>, Int> {
        val used = HashSet<String>()
        fun free(pool: Int) = MapStats.NAMES.first { it.pool == pool && it.name !in used }.name
        val names = HashMap<Char, String>()
        for (r in 'A' until 'A' + kinds.length) {
            if (r in skip) continue
            val name = when (kinds[r - 'A']) {
                'E', 'B', 'F' -> continue
                'P' -> free(MapStats.POOL_PUZZLE); 'T' -> free(MapStats.POOL_TRAP); 'Y' -> free(MapStats.POOL_YELLOW)
                'o' -> if ("Logs" in used) free(MapStats.POOL_1X1) else "Logs"
                else -> free(assertNotNull(MapView.shapeOf(tilesOf(r), 6)).ordinal)
            }
            used += name; names[r] = name
        }
        return names to names.values.sumOf { n -> MapStats.NAMES.first { it.name == n }.secrets }
    }

    @Test fun aGroupWhoseNamesWeighedForSomeOfItIsLeftOutWholeByATotalTheOldOneDidntAllow() {
        // A group is the maps with few rooms to name (the secret total's names weighed them) and those with more (they didn't):
        // the total it was kept with let through only some of the first, so with a total outside it the first are missing, and
        // the second alone are no fair sample of the new view's maps (they are the ones with more rooms): none of the group is reused
        val (names, known) = namedRooms("KI")
        val wide = shown("KI", names, intArrayOf(known + 3, known + 4))
        val st = lane(wide, 20_000, 33).store!!
        assertTrue(st.size > 200 && st.all { it.names })
        val plain = HashSet<Stored>()
        fun group(): List<Stored> = st.mapIndexed { i, s ->
            Stored(s.whole, s.parent, s.w, s.src, s.batch).also { c -> if (i % 2 == 1) { c.names = true; c.totals = s.totals } else plain += c }
        }
        // the same total, or one inside it: the whole group counts, the samples without names too
        plain.clear()
        val same = freshGame(shown("KI", names, intArrayOf(known + 3, known + 4))).reuse(group())
        assertEquals(st.size, same.survivors.size)
        assertTrue(same.survivors.any { it in plain })
        plain.clear()
        val inside = freshGame(shown("KI", names, intArrayOf(known + 4))).reuse(group())
        assertTrue(inside.survivors.size in 1..st.size, "${inside.survivors.size} of ${st.size}")
        assertTrue(inside.survivors.any { it in plain } && inside.survivors.any { it !in plain })
        // a total outside it, or none: nothing, not even the samples that had no names weigh
        for (totals in listOf(intArrayOf(known + 4, known + 5), intArrayOf(known + 5), IntArray(0))) {
            val gs = freshGame(shown("KI", names, totals))
            val g = group()
            assertTrue(gs.namesBlocked(g))
            val r = gs.reuse(g)
            assertEquals(0, r.survivors.size, "total ${totals.toList()}")
            assertEquals(0, r.part.kept)
        }
        // no names weighed anywhere: any total does
        val none = st.map { Stored(it.whole, it.parent, it.w, it.src, it.batch) }
        assertFalse(freshGame(shown("KI", names, intArrayOf(known + 5))).namesBlocked(none))
    }

    @Test fun aTotalOutsideTheOldOneLeavesNothingToReuseOnSeveralLanesToo() {
        // a group big enough to be imported in cuts by several lanes: all of it or none, the same on any number of lanes
        val (names, known) = namedRooms("KI")
        for (lanes in listOf(1, 2)) {
            fun filled(): PredictionSession {
                val s = session(threads = lanes, config = PredictionSession.Config(cap = 8_000))
                s.begin(shown("KI", names, intArrayOf(known + 4)))
                var n = 0
                while (s.poolSize < PredictionSession.PARALLEL_IMPORT + 500 && s.step()) assertTrue(++n < 3_000)
                assertTrue(s.poolSize >= PredictionSession.PARALLEL_IMPORT + 500, "pool ${s.poolSize} ($lanes lanes)")
                return s
            }
            val s = filled()
            val pool = s.poolSize
            s.begin(shown("KI", names, intArrayOf(known + 3, known + 4)))
            assertEquals(0, assertNotNull(s.reveal).fit, "a total more: $lanes lanes")
            s.close()
            val t = filled()
            t.begin(shown("KI", names, intArrayOf(known + 4)))
            while (t.importing) assertTrue(t.step())
            val rev = assertNotNull(t.reveal)
            assertEquals(pool, rev.seen, "$lanes lanes")
            assertEquals(rev.seen, rev.fit, "the same total ($lanes lanes)")
            t.close()
        }
    }

    @Test fun twoPartsOfOneLawMakeTheirMeanByTheirWeights() {
        val v = shown(h0)
        val a = lane(v, 20_000, 41); val b = lane(v, 20_000, 42)
        val ga = freshGame(v)
        val pa = assertNotNull(ga.predictionOf(a.tally, a.kept)); val pb = assertNotNull(ga.predictionOf(b.tally, b.kept))
        val mix = assertNotNull(ga.predictionOf(listOf(a.part(), b.part()), doubleArrayOf(3.0, 1.0)))
        assertEquals(a.kept + b.kept, mix.samples)
        for (t in 0 until 36) {
            val x = pa.raw[t] ?: continue; val y = pb.raw[t]!!; val m = mix.raw[t]!!
            for (c in x.indices) assertEquals(0.75f * x[c] + 0.25f * y[c], m[c], 1e-4f, "tile $t class $c")
        }
        // one part alone is its own prediction; a part with no weight isn't one
        assertEquals(0f, maxDiff(pa, assertNotNull(ga.predictionOf(listOf(a.part(), b.part()), doubleArrayOf(2.0, 0.0)))))
        assertEquals(null, ga.predictionOf(listOf(a.part()), doubleArrayOf(0.0)))
    }

    // ---- the session ----

    private fun session(threads: Int = 1, ahead: Boolean = true, seed: Long = 5, config: PredictionSession.Config = PredictionSession.Config()) =
        PredictionSession(threads, ahead, seed, config)

    private fun runUntilDone(s: PredictionSession, limit: Int = 5_000) {
        var n = 0
        while (!s.isDone) { assertTrue(s.step(), "ended before it was done"); assertTrue(++n < limit, "still running after $n steps") }
    }

    @Test fun aSessionOfOneThreadIsAsGoodAsAFreshRunAndStopsWhereItDid() {
        val s = session(ahead = false)
        s.begin(shown(h0))
        assertTrue(s.usesGameProcess)
        assertEquals(null, s.prediction())
        runUntilDone(s)
        assertFalse(s.step())
        val p = assertNotNull(s.prediction())
        assertTrue(p.samples >= GameSampler.MIN_SAMPLES && s.batches >= GameSampler.MIN_BATCHES)
        assertEquals(0, p.level)
        // the same law as the sampler's own run ([MapPredictor], the same calibration): 120 batches' worth, two runs agree
        val mp = MapPredictor(shown(h0))
        val rnd = Random(9)
        while (mp.accepted < 2000) { val kept = mp.run(100, 40_000, rnd); if (kept == 0 && (mp.accepted > 0 || mp.level > 2)) break }
        val q = assertNotNull(mp.prediction())
        var sum = 0.0; var n = 0
        for (t in 0 until 36) { val x = p.probs[t] ?: continue; val y = q.probs[t]!!; for (c in x.indices) { sum += abs(x[c] - y[c]); n++ } }
        assertTrue(sum / n < 0.01, "mean difference ${sum / n}")
    }

    @Test fun oneThreadWithTheSameSeedGivesTheSameSamples() {
        fun run(): Prediction {
            val s = session(seed = 17)
            s.begin(shown(h1)); repeat(40) { s.step() }
            s.begin(shown(h2)); repeat(40) { s.step() }
            return assertNotNull(s.prediction())
        }
        val a = run(); val b = run()
        assertEquals(a.samples, b.samples)
        assertEquals(0f, maxDiff(a, b))
    }

    @Test fun severalThreadsGiveTheSameAnswerEveryTimeToo() {
        fun run(): Pair<Prediction, Int> {
            val s = session(threads = 3, seed = 17)
            s.begin(shown(h1)); repeat(30) { s.step() }
            s.begin(shown(h2)); repeat(30) { s.step() }
            val p = assertNotNull(s.prediction()); val kept = s.kept; s.close()
            return p to kept
        }
        val (a, ka) = run(); val (b, kb) = run()
        assertEquals(ka, kb)
        assertEquals(0f, maxDiff(a, b))
        // three lanes of 600 particles a step: 1800 started a step
        val s = session(threads = 3)
        s.begin(shown(h1)); s.step(); s.step()
        assertEquals(3600L, s.tries)
        s.close()
    }

    @Test fun theNextRoomsPredictionIsThereAtOnce() {
        val s = session()
        s.begin(shown(h1))
        runUntilDone(s)
        // sampling ahead, the pool fills
        repeat(60) { s.step() }
        assertTrue(s.poolSize > 5_000, "pool ${s.poolSize}")
        s.begin(shown(h2))
        val rev = assertNotNull(s.reveal)
        assertTrue(rev.carried)
        assertTrue(rev.lastFit > 0 && rev.lastFit < rev.lastSeen)
        assertTrue(rev.lastShare > 0.0 && rev.lastShare < 1.0)
        val p = s.prediction()
        assertNotNull(p, "nothing to show right after the reveal: ${s.kept} samples, ${s.batches} batches' worth")
        assertTrue(s.batches >= GameSampler.MIN_SHOW)
        assertEquals(0, p.level)
        // and it goes on to the target, with fresh samples of its own on top
        runUntilDone(s)
        assertTrue(s.batches >= GameSampler.MIN_BATCHES)
        assertTrue(s.tries < 200_000)
    }

    @Test fun aBigPoolComesInAShareAtATimeAndAddsUpToTheSame() {
        // the first few thousand samples (whole batches, the newest view's first) in begin, more only until something can
        // be shown, the rest in the steps after it and before any fresh batch: something is up at once, and once the pool
        // is in it is what carrying it in one go makes
        fun filled(chunk: Int): PredictionSession {
            val s = session(seed = 23, config = PredictionSession.Config(importChunk = chunk))
            s.begin(shown(late)); runUntilDone(s); repeat(30) { s.step() }
            return s
        }
        val a = filled(1_000); val b = filled(1_000_000)
        assertEquals(b.poolSize, a.poolSize)
        assertTrue(a.poolSize > 8_000, "pool ${a.poolSize}")
        val next = shown("QT")
        a.begin(next); b.begin(next)
        assertTrue(a.importing); assertFalse(b.importing)
        assertTrue(assertNotNull(a.reveal).fit < assertNotNull(b.reveal).fit)
        assertNotNull(a.prediction(), "nothing up after the first share: ${a.kept} samples, ${a.batches} batches' worth")
        val tries = a.tries
        var steps = 0
        while (a.importing) { assertTrue(a.step()); assertTrue(++steps < 1_000) }
        assertTrue(steps > 1)
        assertEquals(tries, a.tries, "no fresh batch while the pool comes in")
        assertEquals(b.kept, a.kept)
        assertEquals(assertNotNull(b.reveal).fit, assertNotNull(a.reveal).fit)
        assertEquals(b.poolSize, a.poolSize)
        assertEquals(b.batches, a.batches, 1e-6 * b.batches)
        val d = maxDiff(assertNotNull(a.prediction()), assertNotNull(b.prediction()))
        assertTrue(d < 1e-5f, "differs by $d")
        // a new view before the pool is in: what was not looked at yet comes along as it is, nothing is lost
        val c = filled(1_000)
        c.begin(next)
        assertTrue(c.importing)
        val pool = c.poolSize
        c.begin(shown("Q"))
        assertTrue(assertNotNull(c.reveal).carried)
        assertEquals(pool, assertNotNull(c.reveal).seen)
        while (c.importing) assertTrue(c.step())
        assertTrue(assertNotNull(c.reveal).fit > 0)
        assertNotNull(c.prediction())
        a.close(); b.close(); c.close()
    }

    @Test fun aViewThatIsNoRefinementStartsFromNothing() {
        val s = session()
        s.begin(shown(h1)); repeat(30) { s.step() }
        assertTrue(s.poolSize > 0)
        // fewer rooms than before (a misread corrected): nothing carried
        s.begin(shown(h0))
        assertFalse(assertNotNull(s.reveal).carried)
        assertEquals(0, s.kept)
        assertEquals(null, s.prediction())
        repeat(30) { s.step() }
        // then a refinement of that
        s.begin(shown(h1))
        assertTrue(assertNotNull(s.reveal).carried)
        // pooling off: never carried
        val off = session(config = PredictionSession.Config(pool = false))
        off.begin(shown(h0)); repeat(10) { off.step() }
        off.begin(shown(h1))
        assertFalse(assertNotNull(off.reveal).carried)
        assertEquals(0, off.kept)
        assertEquals(0, off.poolSize)
    }

    @Test fun aViewThatLostItsTabPinsDoesntTakeSamplesKeptByThem() {
        // the tab's Quiz is R, the second puzzle in the tab's order, and the samples are kept by that pin; a read that names Q
        // Quiz too (both can't be) still says all the view before said, but has no pins, so samples kept by R's can't be its own
        val slots = listOf(null, "Quiz", null)
        val pinned = shown(late, mapOf('R' to "Quiz"), slots = slots)
        val unpinned = shown("T", mapOf('R' to "Quiz", 'Q' to "Quiz"), slots = slots)
        val control = shown("T", mapOf('R' to "Quiz"), slots = slots)
        assertEquals(1, MapPredictor(pinned).pinCount)
        assertEquals(0, MapPredictor(unpinned).pinCount)
        assertEquals(1, MapPredictor(control).pinCount)
        assertTrue(unpinned.refines(pinned) && control.refines(pinned))
        for ((later, carries) in listOf(unpinned to false, control to true)) {
            val s = session()
            s.begin(pinned); repeat(30) { s.step() }
            assertTrue(s.poolSize > 0)
            s.begin(later)
            assertEquals(carries, assertNotNull(s.reveal).carried)
        }
    }

    @Test fun aGlitchOfOneViewDoesntCostThePool() {
        // a room gone from the map for a tick isn't what the view before said; the view after it, with the room back, is
        val s = session()
        s.begin(shown(h1)); repeat(40) { s.step() }
        val pool = s.poolSize
        assertTrue(pool > 1_000)
        s.begin(shown(h0))
        assertFalse(assertNotNull(s.reveal).carried)
        assertEquals(0, s.kept)
        repeat(3) { s.step() }
        s.begin(shown(h2))
        val rev = assertNotNull(s.reveal)
        assertTrue(rev.carried, "the pool put by comes back for a view that says what it said")
        assertTrue(rev.seen >= pool && rev.fit > 0, "seen ${rev.seen} of $pool, fit ${rev.fit}")
        // a view that builds on the glitch instead leaves the pool put by behind: the one after that has nothing to take back
        s.begin(shown(h0)); s.step()
        s.begin(shown(h1)); s.step()
        assertTrue(assertNotNull(s.reveal).carried)
        s.begin(shown(h2, mapOf('M' to "Logs")))
        assertTrue(assertNotNull(s.reveal).carried)
    }

    @Test fun aGlitchTheGameProcessCantFitDoesntCostThePoolEither() {
        // a read with more 2x2 rooms than any game has: the classic sampler takes that view, and the view after it, with the glitch gone, has the pool
        val glitch = MapView(6, 6, 3, FloorRules.F7)
        assertNotNull(glitch.addRoom(Kind.ENTRANCE, intArrayOf(1)))
        for (box in listOf(intArrayOf(7, 8, 13, 14), intArrayOf(9, 10, 15, 16), intArrayOf(19, 20, 25, 26))) assertNotNull(glitch.addRoom(Kind.NORMAL, box))
        glitch.closeEdges { a, b -> if (minOf(a, b) == 1 && maxOf(a, b) == 7) Edge.DOOR else null }
        val s = session()
        s.begin(shown(h1)); repeat(40) { s.step() }
        val pool = s.poolSize
        assertTrue(pool > 1_000)
        s.begin(glitch)
        assertFalse(s.usesGameProcess)
        repeat(3) { s.step() }
        s.begin(shown(h2))
        val rev = assertNotNull(s.reveal)
        assertTrue(rev.carried && rev.seen >= pool && rev.fit > 0, "carried ${rev.carried}, seen ${rev.seen} of $pool, fit ${rev.fit}")
        // asked for the classic model, the pool isn't kept
        s.begin(shown(h2), game = false)
        assertFalse(s.usesGameProcess)
        s.begin(shown(h3))
        assertEquals(0, assertNotNull(s.reveal).seen)
    }

    @Test fun aRoomGettingItsNameKeepsEverySampleAndASecretTotalDoesToo() {
        val s = session()
        s.begin(shown(h1)); runUntilDone(s)
        val before = s.poolSize
        val k = s.kept
        val shownBefore = assertNotNull(s.prediction())
        s.begin(shown(h1, mapOf('G' to "Mushroom")))
        val rev = assertNotNull(s.reveal)
        assertTrue(rev.carried)
        assertEquals(before, rev.fit)
        assertEquals(k, s.kept)
        // nothing a sample's finish reads has changed: the same samples, the same prediction, none of the work again
        assertEquals(0f, maxDiff(shownBefore, assertNotNull(s.prediction())))
        s.begin(shown(h1, mapOf('G' to "Mushroom"), intArrayOf(80, 81)))
        assertEquals(k, s.kept)
        assertEquals(1, assertNotNull(s.reveal).groups)
    }

    @Test fun aNameOrASecretTotalCostsNoNewWork() {
        // the same rooms, doors and walls, finished as before (a name, or a secret total no finish weighs): the samples are the
        // new view's own law, whole, so it is done as the one before was, and a full pool stays full (no room cut for more)
        val s = session(ahead = false)
        s.begin(shown(late)); runUntilDone(s)
        assertFalse(s.step())
        val shownBefore = assertNotNull(s.prediction())
        s.begin(shown(late, mapOf('G' to "Mushroom"), intArrayOf(120, 121)))
        assertTrue(s.isDone)
        assertFalse(s.step())
        assertEquals(0L, s.tries)
        assertEquals(0f, maxDiff(shownBefore, assertNotNull(s.prediction())))
        val config = PredictionSession.Config(cap = 6_000)
        val t = session(config = config)
        t.begin(shown(late)); while (t.step()) {}
        assertTrue(t.poolSize >= 6_000, "pool ${t.poolSize}")
        t.begin(shown(late, mapOf('G' to "Mushroom")))
        while (t.importing) assertTrue(t.step())
        assertTrue(t.poolSize > 6_000 - 1_000, "pool ${t.poolSize}")
        var steps = 0
        while (t.step()) assertTrue(++steps <= 3, "drew $steps rounds more")
        // a room more is a new law: room is made for its own samples
        t.begin(shown("QT"))
        while (t.importing) assertTrue(t.step())
        assertTrue(t.poolSize <= (6_000 * PredictionSession.OLD_SHARE).toInt(), "pool ${t.poolSize}")
    }

    @Test fun aSessionKeepsDrawingForTheNextRoomUntilThePoolIsFull() {
        val config = PredictionSession.Config(cap = 35_000)
        val s = session(config = config)
        s.begin(shown(h1))
        var steps = 0
        while (s.step()) assertTrue(++steps < 5_000)
        assertTrue(s.isDone)
        assertTrue(s.poolSize >= 35_000, "pool ${s.poolSize}")
        assertTrue(s.poolSize < 35_000 + 600 * 4, "pool ${s.poolSize}")
        // not asked to: it stops at the target
        val t = session(ahead = false, config = config)
        t.begin(shown(h1))
        while (t.step()) {}
        assertTrue(t.isDone)
        assertTrue(t.poolSize < 35_000, "pool ${t.poolSize}")
        // a pool that is full by the time it is done draws no more
        val u = session(config = PredictionSession.Config(cap = 1_000))
        u.begin(shown(late))
        while (u.step()) {}
        assertTrue(u.isDone)
        val tries = u.tries
        assertFalse(u.step())
        assertEquals(tries, u.tries)
    }

    @Test fun theOldestSamplesGoWhenThePoolIsOverTheCap() {
        val s = session(config = PredictionSession.Config(cap = 4_000))
        s.begin(shown(late)); repeat(80) { s.step() }
        assertTrue(s.poolSize >= 4_000)
        s.begin(shown("QT"))
        while (s.importing) assertTrue(s.step())
        // what fits is kept up to three quarters of the cap, the new view has the rest
        assertTrue(s.poolSize <= (4_000 * PredictionSession.OLD_SHARE).toInt() + 1_000, "pool ${s.poolSize}")
        assertTrue(s.kept > 0)
    }

    @Test fun cancelStopsTheWorkAtTheNextBatch() {
        for (threads in listOf(1, 2)) {
            val s = session(threads = threads)
            s.begin(shown(h0))
            val t = thread { while (s.step(1_000_000)) {} }
            Thread.sleep(300)
            s.cancel()
            t.join(10_000)
            assertFalse(t.isAlive, "still running after cancel ($threads threads)")
            assertFalse(s.step())
            // a new view works again
            s.begin(shown(h1))
            assertTrue(s.step())
            s.close()
        }
    }

    @Test fun aWorkerCancelledAtRandomKeepsItsPoolAcrossManyViews() {
        // the worker as the game runs it: a new view every few rounds, the old one cancelled from another thread, lanes on helper threads
        val views = listOf(h0, h1, h2, h3, "ICKPHQST", "ICPHQST", "CPHQ", "PQ", "")
        val s = session(threads = 3, seed = 99)
        val rnd = Random(4)
        var carried = 0
        for (round in 0 until 40) {
            val v = shown(views[minOf(views.size - 1, round / 4)], if (round >= 3) mapOf('G' to "Mushroom") else emptyMap())
            val canceller = thread { Thread.sleep(rnd.nextLong(1, 30)); s.cancel() }
            s.begin(v)
            if (s.reveal?.carried == true) carried++
            var steps = 0
            while (steps++ < 50 && s.step()) { s.prediction() }
            canceller.join()
        }
        assertTrue(carried >= 25, "carried $carried times")
        s.close()
    }

    @Test fun otherFloorsAndTheClassicModelKeepTheOldLoop() {
        val f5 = MapView(6, 6, 3, FloorRules.F5)
        assertNotNull(f5.addRoom(Kind.ENTRANCE, intArrayOf(1))); assertNotNull(f5.addRoom(Kind.NORMAL, intArrayOf(7, 8, 13, 14)))
        val d = mapOf((1 to 7) to Edge.DOOR, (14 to 20) to Edge.WITHER, (8 to 9) to Edge.DOOR, (6 to 7) to Edge.DOOR)
        f5.closeEdges { a, b -> d[minOf(a, b) to maxOf(a, b)] }
        val s = session()
        s.begin(f5)
        assertFalse(s.usesGameProcess)
        var steps = 0
        while (s.step()) assertTrue(++steps < 1_000)
        assertTrue(s.isDone)
        assertNotNull(s.prediction())
        // F7 with the classic model asked for
        s.begin(shown(h1), game = false)
        assertFalse(s.usesGameProcess)
        assertEquals(null, s.reveal)
        steps = 0
        while (s.step()) assertTrue(++steps < 1_000)
        assertTrue(s.prediction() != null && s.prediction()!!.samples >= 2000)
    }

    @Test fun aMapTheGameCantMakeFallsBackToTheClassicSamplerAndLetsTheOldPoolGo() {
        // a blood door 3-4 steps from the entrance: only the classic sampler's looser levels fit it
        fun opening(down: Byte): MapView {
            val v = MapView(6, 6, 3, FloorRules.F7)
            assertNotNull(v.addRoom(Kind.ENTRANCE, intArrayOf(1))); assertNotNull(v.addRoom(Kind.NORMAL, intArrayOf(7, 8, 13, 14)))
            val d = mapOf((1 to 7) to Edge.DOOR, (14 to 20) to down, (8 to 9) to Edge.DOOR, (6 to 7) to Edge.DOOR)
            v.closeEdges { a, b -> d[minOf(a, b) to maxOf(a, b)] }
            return v
        }
        val s = session()
        s.begin(opening(Edge.BLOOD))
        assertTrue(s.usesGameProcess)
        var steps = 0
        while (s.step()) assertTrue(++steps < 5_000)
        assertFalse(s.usesGameProcess)
        assertTrue(s.fellBack)
        val p = assertNotNull(s.prediction())
        assertEquals(1f, assertNotNull(p.probs[20])[Cls.BLOOD], 1e-4f)
        assertEquals(0, s.poolSize)
    }

    @Test fun aSamplerThatThrowsLeavesTheClassicSamplerTheView() {
        // nothing in the sampler is meant to throw; if something does the worker shouldn't be left with no prediction
        val s = session()
        s.begin(shown(h0))
        assertTrue(s.usesGameProcess)
        // a lane whose random numbers throw
        s.rnds = arrayOf(object : Random() { override fun nextBits(bitCount: Int): Int = throw IllegalStateException("boom") })
        assertTrue(s.step())
        assertFalse(s.usesGameProcess)
        assertEquals("boom", s.error?.message)
        var steps = 0
        while (s.step()) assertTrue(++steps < 1_000)
        assertNotNull(s.prediction())
    }
}
