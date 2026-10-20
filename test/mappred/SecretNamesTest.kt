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
        val p = MapPredictor(v)
        p.run(300, 200_000, Random(3))
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
}
