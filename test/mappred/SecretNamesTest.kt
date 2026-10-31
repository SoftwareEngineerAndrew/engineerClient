package com.engineerclient.mappred

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecretNamesTest {

    private fun share(r: SecretNames.Result, sn: SecretNames, pool: Int, name: String): Double =
        r.byPool.getValue(pool)[sn.avail[pool].indexOfFirst { it.name == name }]

    @Test fun totalsFromTheTabList() {
        assertContentEquals(intArrayOf(120), SecretNames.totals(30, 25f))
        assertContentEquals(intArrayOf(57), SecretNames.totals(57, 100f))
        assertEquals(0, SecretNames.totals(0, 0f).size)
        // one secret found: the rounded percentage still allows a range of totals
        val wide = SecretNames.totals(1, 0.8f)
        assertTrue(125 in wide && wide.size > 1)
    }

    @Test fun theOnlyFreeNameThatFits() {
        // a 1x2 with 5 secrets is Crypt, Pedestal, Purple Flags or Doors; with the first three used, it's Doors
        val sn = SecretNames(setOf("Crypt", "Pedestal", "Purple Flags"), intArrayOf(5))
        val r = assertNotNull(sn.solve(intArrayOf(Shape.TWO.ordinal)))
        assertTrue(r.likelihood > 0)
        assertEquals(1.0, share(r, sn, Shape.TWO.ordinal, "Doors"), 1e-9)
    }

    @Test fun aNameIsUsedOnceAMap() {
        // two 1x2s with 10 secrets between them: Doors can't be both
        val sn = SecretNames(setOf("Crypt", "Pedestal", "Purple Flags"), intArrayOf(10))
        val r = assertNotNull(sn.solve(intArrayOf(Shape.TWO.ordinal, Shape.TWO.ordinal)))
        assertEquals(0.0, share(r, sn, Shape.TWO.ordinal, "Doors"), 1e-9)
        assertTrue(share(r, sn, Shape.TWO.ordinal, "Bridges") > 0)
    }

    @Test fun aFloorsMissingRoomsAreNoName() {
        // a puzzle with 1 secret is Tic Tac Toe or a Blaze, and F1 has no Blaze
        val sn = SecretNames(emptySet(), intArrayOf(1), FloorRules.F1.missingNames)
        val r = assertNotNull(sn.solve(intArrayOf(MapStats.POOL_PUZZLE)))
        assertEquals(1.0, share(r, sn, MapStats.POOL_PUZZLE, "Tic Tac Toe"), 1e-9)
        assertTrue(sn.avail[MapStats.POOL_PUZZLE].none { it.name == "Lower Blaze" })
    }

    @Test fun noNamesFitMeansThisCantBeTheMap() {
        val sn = SecretNames(emptySet(), intArrayOf(99))
        assertEquals(0.0, assertNotNull(sn.solve(intArrayOf(Shape.TWO.ordinal))).likelihood)
    }

    /**
     * A complete synthetic map (21 rooms, 7 big ones, the wither path a 9-step walk with the fairy 4 before
     * blood), all of it discovered and named but the 1x2 at (4,4)-(5,4), whose door is from the L above it.
     */
    private fun lastRoom(total: Int): MapView {
        val v = MapView(6, 6, 4)
        val rooms = listOf(
            Triple(Kind.ENTRANCE, intArrayOf(1), "Entrance"), Triple(Kind.NORMAL, intArrayOf(7, 8, 13, 14), "Flags"),
            Triple(Kind.NORMAL, intArrayOf(2, 3, 4, 5), "Mossy"), Triple(Kind.NORMAL, intArrayOf(9, 10, 11), "Diagonal"),
            Triple(Kind.NORMAL, intArrayOf(18, 19, 20), "Wizard"), Triple(Kind.NORMAL, intArrayOf(16, 17, 23), "Altar"),
            Triple(Kind.NORMAL, intArrayOf(25, 26, 31), "Melon"),
            Triple(Kind.NORMAL, intArrayOf(6), "Cell"), Triple(Kind.NORMAL, intArrayOf(24), "Mural"), Triple(Kind.NORMAL, intArrayOf(27), "Dip"),
            Triple(Kind.NORMAL, intArrayOf(33), "Prison Cell"), Triple(Kind.NORMAL, intArrayOf(34), "Small Waterfall"),
            Triple(Kind.PUZZLE, intArrayOf(0), "Ice Fill"), Triple(Kind.PUZZLE, intArrayOf(12), "Quiz"), Triple(Kind.PUZZLE, intArrayOf(15), "Boulder"),
            Triple(Kind.PUZZLE, intArrayOf(30), "Creeper Beams"), Triple(Kind.TRAP, intArrayOf(22), "New Trap"), Triple(Kind.YELLOW, intArrayOf(32), "Default"),
            Triple(Kind.FAIRY, intArrayOf(21), "Fairy"), Triple(Kind.BLOOD, intArrayOf(35), "Blood"),
        )
        for ((k, t, name) in rooms) assertNotNull(v.addRoom(k, t, name))
        val doors = listOf(
            Triple(1, 7, Edge.DOOR), Triple(14, 20, Edge.WITHER), Triple(20, 21, Edge.WITHER), Triple(21, 27, Edge.WITHER),
            Triple(27, 33, Edge.WITHER), Triple(33, 34, Edge.WITHER), Triple(34, 35, Edge.BLOOD),
            Triple(2, 8, Edge.DOOR), Triple(6, 7, Edge.DOOR), Triple(0, 6, Edge.DOOR), Triple(12, 18, Edge.DOOR), Triple(8, 9, Edge.DOOR),
            Triple(9, 15, Edge.DOOR), Triple(10, 16, Edge.DOOR), Triple(16, 22, Edge.DOOR), Triple(23, 29, Edge.DOOR), Triple(18, 24, Edge.DOOR),
            Triple(24, 30, Edge.DOOR), Triple(19, 25, Edge.DOOR), Triple(31, 32, Edge.DOOR),
        ).associate { (a, b, k) -> (minOf(a, b) to maxOf(a, b)) to k }
        v.closeEdges { a, b -> doors[minOf(a, b) to maxOf(a, b)] }
        v.secretTotals = intArrayOf(total) // the named rooms hold 42
        return v
    }

    private fun predict(v: MapView): Prediction {
        // as the map prediction worker runs it (OdinMapPrediction): chunks until enough, or one keeps none once some are
        // (the game-process sampler shows nothing before several batches back it, so a single call isn't enough)
        val p = MapPredictor(v)
        val rnd = Random(3)
        var calls = 0
        while (p.accepted < 300) {
            assertTrue(++calls < 5_000, "still running after $calls chunks")
            if (p.run(100, 40_000, rnd) == 0 && (p.accepted > 0 || p.level > 2)) break
        }
        return assertNotNull(p.prediction())
    }

    @Test fun oneSecretLeftInTwoTilesIsGold() {
        val p = predict(lastRoom(43))
        val room = p.namedRooms.first()
        assertContentEquals(intArrayOf(28, 29), room.tiles)
        assertEquals("Gold", room.name.name)
        assertEquals(1f, room.chance, 1e-4f)
        // a single 1x2 left: no door between unknown rooms (its own door is on the map already)
        assertTrue(p.doors.isEmpty())
    }

    @Test fun fiveSecretsLeftPinTheRoomButNotItsName() {
        val p = predict(lastRoom(47))
        assertTrue(p.namedRooms.all { it.tiles.contentEquals(intArrayOf(28, 29)) && it.name.secrets == 5 })
        assertEquals(1.0, p.namedRooms.sumOf { it.chance.toDouble() }, 1e-4)
        assertEquals(setOf("Crypt", "Pedestal", "Purple Flags", "Doors"), p.namedRooms.map { it.name.name }.toSet())
    }

    /** [SecretNames.solve] by every assignment of names to rooms, as it was worked out before. */
    private fun brute(sn: SecretNames, targets: IntArray, pools: IntArray): Pair<Double, Map<Int, DoubleArray>> {
        val pw = DoubleArray(MapStats.POOLS) { p -> sn.avail[p].sumOf { it.seen.toDouble() } }
        val pick = IntArray(pools.size)
        val marg = Array(pools.size) { DoubleArray(sn.avail[pools[it]].size) }
        var total = 0.0
        fun dfs(i: Int, sum: Int, w: Double) {
            if (i == pools.size) { if (sum in targets) { total += w; for (j in pools.indices) marg[j][pick[j]] += w }; return }
            val names = sn.avail[pools[i]]
            for (k in names.indices) {
                if ((0 until i).any { pools[it] == pools[i] && pick[it] == k }) continue
                pick[i] = k; dfs(i + 1, sum + names[k].secrets, w * names[k].seen / pw[pools[i]])
            }
        }
        dfs(0, 0, 1.0)
        val by = HashMap<Int, DoubleArray>()
        for (j in pools.indices) if (pools[j] !in by) by[pools[j]] = DoubleArray(marg[j].size) { if (total > 0) marg[j][it] / total else 0.0 }
        return total to by
    }

    @Test fun anyNumberOfRoomsIsWorkedOutExactly() {
        val rnd = Random(7)
        val choice = intArrayOf(0, 1, 2, 3, 4, MapStats.POOL_1X1, MapStats.POOL_PUZZLE, MapStats.POOL_TRAP, MapStats.POOL_RARE)
        repeat(40) {
            val pools = IntArray(1 + rnd.nextInt(4)) { choice[rnd.nextInt(choice.size)] }
            val targets = intArrayOf(rnd.nextInt(3, 22), rnd.nextInt(3, 22)).distinct().toIntArray()
            val sn = SecretNames(setOf("Gold", "Mossy"), targets)
            val r = assertNotNull(sn.solve(pools))
            val (lik, by) = brute(sn, targets, pools)
            assertEquals(lik, r.likelihood, 1e-9 * maxOf(1.0, lik), "pools ${pools.toList()} targets ${targets.toList()}")
            if (lik > 0) for ((pool, a) in by) for (k in a.indices) assertEquals(a[k], r.byPool.getValue(pool)[k], 1e-9)
        }
        // far past what the enumeration could: 14 rooms at once
        val many = IntArray(14) { if (it < 8) MapStats.POOL_1X1 else it - 8 }
        val r = assertNotNull(SecretNames(emptySet(), intArrayOf(45)).solve(many))
        assertTrue(r.likelihood > 0)
        assertEquals(1.0, r.byPool.getValue(MapStats.POOL_1X1).sum(), 1e-9)
    }

    @Test fun aMapAllDiscoveredNamesEveryRoomHoweverMany() {
        // the last room on the map too (a 1x2 at 28-29, its door from the L above), and six rooms nobody has been in
        val v0 = lastRoom(0)
        val v = MapView(6, 6, 4)
        val unnamed = setOf("Flags", "Mossy", "Diagonal", "Cell", "Mural", "Quiz")
        var named = 0
        for (i in v0.kinds.indices) {
            val name = v0.names[i]!!
            if (name !in unnamed) named += MapStats.NAMES.first { it.name == name }.secrets
            assertNotNull(v.addRoom(v0.kinds[i], v0.roomTiles[i], name.takeIf { it !in unnamed }))
        }
        assertNotNull(v.addRoom(Kind.NORMAL, intArrayOf(28, 29)))
        v.closeEdges { a, b -> v0.edge(a, b).takeIf { Edge.isDoor(it) } }
        val total = named + 30
        v.secretTotals = intArrayOf(total)
        val names = assertNotNull(MapPredictor(v, gameProcess = false).certainNames(null))
        val rooms = names.groupBy { it.tiles.toList() }
        assertEquals(7, rooms.size)
        for ((tiles, ns) in rooms) assertEquals(1.0, ns.sumOf { it.chance.toDouble() }, 1e-4, "room $tiles")
        // every way the names can go adds up to the 30 the named rooms leave
        assertEquals(30.0, names.sumOf { it.chance.toDouble() * it.name.secrets }, 1e-3)
    }
}
