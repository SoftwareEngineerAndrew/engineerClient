package com.engineerclient.p3sim.clear.hud

import com.engineerclient.p3sim.run.CheckMark
import com.engineerclient.p3sim.run.DoorPhase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The map raster against Odin's `MapScan` reading rules, re-implemented here from its documented behaviour: the entrance is a 16 wide run
 * of colour 30, a room's type is its corner pixel, its check mark the pixel at (8, 8), a door the pixel at (18, 8) (or (8, 18)) with an
 * empty pixel four rows to the side, and two tiles are one room when the gap between them is filled.
 */
class MapModelTest {
    /** Somebody has stood in the room, so the map shows it in its own colour. */
    private fun visit(st: com.engineerclient.p3sim.run.RunState, vararg rooms: Int) { for (r in rooms) st.rooms[r].visitors += HudFixtures.me }

    private fun px(b: ByteArray, x: Int, y: Int) = b[y * 128 + x].toInt()

    private fun tile(tx: Int, tz: Int) = (5 + 20 * tx) to (5 + 20 * tz)

    private fun corner(b: ByteArray, tx: Int, tz: Int): Int { val (x, y) = tile(tx, tz); return px(b, x, y) }
    private fun centre(b: ByteArray, tx: Int, tz: Int): Int { val (x, y) = tile(tx, tz); return px(b, x + 8, y + 8) }

    /** Odin's door test for the gap east of / south of a tile; 0 when no door is seen. */
    private fun doorEast(b: ByteArray, tx: Int, tz: Int): Int { val (x, y) = tile(tx, tz); return if (px(b, x + 18, y + 4) == 0) px(b, x + 18, y + 8) else 0 }
    private fun doorSouth(b: ByteArray, tx: Int, tz: Int): Int { val (x, y) = tile(tx, tz); return if (px(b, x + 4, y + 18) == 0) px(b, x + 8, y + 18) else 0 }
    private fun joinedEast(b: ByteArray, tx: Int, tz: Int): Boolean { val (x, y) = tile(tx, tz); return px(b, x + 18, y) != 0 }

    @Test fun theFirstFrameIsTheEntranceAndWhatItTouches() {
        val st = HudFixtures.bigState()
        val b = MapModel.render(st)
        assertEquals(16384, b.size)
        assertEquals(0, px(b, 0, 0), "Odin needs the first pixel empty")
        // The entrance is a 16 wide run of 30 at the first row of its tile.
        val (x, y) = tile(2, 0)
        assertEquals((0 until 16).map { 30 }, (0 until 16).map { px(b, x + it, y) })
        assertEquals(0, px(b, x + 16, y))
        assertEquals(30, px(b, x + 15, y + 15))
        // The room next to the entrance is known: grey with a question mark; the rest of the map is empty.
        assertEquals(85, corner(b, 2, 1))
        assertEquals(119, centre(b, 2, 1))
        assertEquals(0, corner(b, 3, 1))
        assertEquals(0, corner(b, 2, 2))
        // The door between them is grey.
        assertEquals(85, doorSouth(b, 2, 0))
    }

    @Test fun anOpenedRoomTakesItsColourAndAWitherDoorShowsBlack() {
        val st = HudFixtures.bigState()
        st.setCheck(0, CheckMark.GREEN)
        st.setCheck(1, CheckMark.NONE); visit(st, 1)
        val b = MapModel.render(st)
        assertEquals(66, corner(b, 2, 1), "puzzle colour")
        assertEquals(66, centre(b, 2, 1), "a room with no mark has its own colour in the middle: Odin reads NONE")
        // The wither door east of the room and the blood door south of it are visible as soon as the room is.
        assertEquals(119, doorEast(b, 2, 1))
        assertEquals(18, doorSouth(b, 2, 1))
        // The rooms behind them are known but unopened.
        assertEquals(85, corner(b, 3, 1))
        assertEquals(85, corner(b, 2, 2))
        // The door between the entrance and the room is now the room's colour.
        assertEquals(66, doorSouth(b, 2, 0))
    }

    @Test fun checkMarksAreTheRecordedColoursAtTheCentre() {
        val st = HudFixtures.bigState()
        st.setCheck(1, CheckMark.WHITE)
        assertEquals(34, centre(MapModel.render(st), 2, 1))
        st.setCheck(1, CheckMark.GREEN)
        assertEquals(30, centre(MapModel.render(st), 2, 1))
        st.setCheck(1, CheckMark.RED)
        assertEquals(18, centre(MapModel.render(st), 2, 1))
        // The glyph is the recorded one: a green check on a 63 room has its rows 4 to 12 set.
        val s2 = HudFixtures.bigState(); s2.setCheck(1, CheckMark.GREEN)
        val b = MapModel.render(s2)
        val (x, y) = tile(2, 1)
        assertEquals(30, px(b, x + 11, y + 4)); assertEquals(30, px(b, x + 12, y + 4))
        assertEquals(66, px(b, x + 0, y + 4)); assertEquals(30, px(b, x + 5, y + 12))
    }

    @Test fun anOpenedDoorTakesTheColourOfTheRoomBehindIt() {
        val st = HudFixtures.bigState()
        st.setCheck(0, CheckMark.GREEN); st.setCheck(1, CheckMark.NONE); st.setCheck(2, CheckMark.NONE); visit(st, 1, 2)
        assertEquals(119, doorEast(MapModel.render(st), 2, 1))
        st.doors[1].phase = DoorPhase.OPEN
        assertEquals(74, doorEast(MapModel.render(st), 2, 1), "the big yellow room")
    }

    @Test fun aTwoByTwoRoomIsOnePieceWithFilledGaps() {
        val st = HudFixtures.bigState()
        st.setCheck(2, CheckMark.NONE); visit(st, 2)
        val b = MapModel.render(st)
        for ((tx, tz) in listOf(3 to 1, 4 to 1, 3 to 2, 4 to 2)) assertEquals(74, corner(b, tx, tz))
        assertTrue(joinedEast(b, 3, 1))
        assertTrue(joinedEast(b, 3, 2))
        val (x, y) = tile(3, 1)
        assertEquals(74, px(b, x + 17, y + 17), "the junction of the four tiles is filled")
        // Odin's door test sees a connector, not a door, because the side pixel is set.
        assertFalse(doorEast(b, 3, 1) != 0)
    }

    @Test fun aOneByTwoRoomOfTheTestDungeonIsJoinedToo() {
        val st = HudFixtures.state()
        st.setCheck(0, CheckMark.GREEN); st.setCheck(1, CheckMark.WHITE); visit(st, 1)
        val b = MapModel.render(st)
        assertEquals(63, corner(b, 3, 1))
        assertEquals(63, corner(b, 3, 2))
        val (x, y) = tile(3, 1)
        assertEquals(63, px(b, x + 4, y + 18), "the connector south of the first tile")
        assertEquals(63, px(b, x + 8, y + 18))
        // The mark is drawn once, on the first tile, so the second tile's middle is plain.
        assertEquals(34, centre(b, 3, 1)); assertEquals(63, centre(b, 3, 2))
    }

    @Test fun decorationsAreOneBlueMarkerPerLivingTeammateAndTheViewersFrameLast() {
        val st = HudFixtures.state(); HudFixtures.party(st)
        st.players[HudFixtures.a]!!.x = -200.0; st.players[HudFixtures.a]!!.z = -200.0
        val ctx = HudFixtures.ctx(st)
        val m = MapModel.decorations(ctx, HudFixtures.me)
        assertEquals(listOf(false, false, true), m.map { it.frame })
        st.players[HudFixtures.b]!!.ghost = true
        val m2 = MapModel.decorations(ctx, HudFixtures.me)
        assertEquals(2, m2.size, "a ghost has no marker, so the count stays equal to the living")
        assertTrue(m2.last().frame)
        st.players[HudFixtures.me]!!.ghost = true
        assertEquals(2, MapModel.decorations(ctx, HudFixtures.me).size, "a dead viewer keeps their own frame marker: the recorded count did not drop")
    }

    @Test fun worldPositionMapsToTheMapPixelsOdinInverts() {
        // Odin: pixel = (byte + 128) / 2 - 5 is the offset from the grid origin, and the world offset is that * 32 / 20.
        val m = MapModel.mark(false, -200.0, -200.0, 0f)
        assertEquals(2 * 9 - 128, m.x, "pixel floor(5 + 4.9)")
        val c = MapModel.mark(false, -185.0, -185.0, 0f)
        assertEquals(2 * Math.floor(5 + 15 * 0.625 + 4.9).toInt() - 128, c.x)
        val inv = ((c.x + 128) / 2.0 - 5 - 4.9) * 32 / 20 - 200
        assertTrue(Math.abs(inv - -185.0) < 1.7, "inverse is within one pixel: $inv")
        assertEquals(126, MapModel.mark(false, 500.0, 500.0, 0f).x, "off the map clamps (to an even byte)")
        assertEquals(-128, MapModel.mark(false, -5000.0, 0.0, 0f).x)
    }

    @Test fun markerBytesAreAlwaysEven() {
        for (i in -250..250 step 7) for (z in listOf(-190.5, -37.25, 12.0)) {
            val m = MapModel.mark(true, i * 0.9, z, 0f)
            assertEquals(0, Math.floorMod(m.x, 2)); assertEquals(0, Math.floorMod(m.y, 2))
        }
    }

    @Test fun anUnenteredRoomIsOneGreyTileWithTheRecordedQuestionMark() {
        val st = HudFixtures.bigState()
        st.setCheck(0, CheckMark.GREEN); st.setCheck(1, CheckMark.NONE); visit(st, 1)
        val b = MapModel.render(st)
        // The 2x2 room behind the wither door: only the tile that touches the door, no other tile and no connector.
        assertEquals(85, corner(b, 3, 1))
        assertEquals(0, corner(b, 4, 1)); assertEquals(0, corner(b, 3, 2)); assertEquals(0, corner(b, 4, 2))
        assertFalse(joinedEast(b, 3, 1))
        val (x, y) = tile(3, 1)
        val set = (0 until 16).flatMap { dy -> (0 until 16).map { dx -> dx to dy } }.filter { (dx, dy) -> px(b, x + dx, y + dy) == 119 }
        assertEquals(listOf(6 to 5, 7 to 5, 8 to 5, 5 to 6, 9 to 6, 9 to 7, 8 to 8, 7 to 9, 7 to 10, 7 to 12, 7 to 13).sortedWith(compareBy({ it.second }, { it.first })), set)
        // Once somebody is inside, the whole room appears at once.
        visit(st, 2); st.setCheck(2, CheckMark.NONE)
        val c = MapModel.render(st)
        for ((tx, tz) in listOf(3 to 1, 4 to 1, 3 to 2, 4 to 2)) assertEquals(74, corner(c, tx, tz))
    }

    @Test fun rotationIsSixteenthsOfATurn() {
        assertEquals(0, MapModel.mark(false, 0.0, 0.0, 0f).rot)
        assertEquals(4, MapModel.mark(false, 0.0, 0.0, 90f).rot)
        assertEquals(8, MapModel.mark(false, 0.0, 0.0, 180f).rot)
        assertEquals(12, MapModel.mark(false, 0.0, 0.0, -90f).rot)
        assertEquals(0, MapModel.mark(false, 0.0, 0.0, 360f).rot)
    }

    @Test fun contributorsPaintOnTopThroughTheCanvas() {
        val st = HudFixtures.bigState()
        val c = ByteMapCanvas()
        MapModel.renderInto(st, c)
        c.set(60, 60, 99)
        c.set(-1, 0, 5); c.set(0, 128, 5)
        assertEquals(99, c.get(60, 60))
        assertEquals(0, c.get(-3, 4))
        assertEquals(128, c.size)
    }
}
