package com.engineerclient.p3sim.clear.hud

import com.engineerclient.ads.map.DoorRotation
import com.engineerclient.ads.map.DoorType
import com.engineerclient.ads.map.DungeonScan
import com.engineerclient.ads.map.GridPos
import com.engineerclient.ads.map.MapCheckmark
import com.engineerclient.ads.map.MapScan
import com.engineerclient.ads.map.RoomType
import com.engineerclient.p3sim.run.CheckMark
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADS's minimap reads the map ADS's own worlds draw ([MapModel]) the way Odin reads Hypixel's: rooms, check marks and doors. */
class MapScanTest {
    @BeforeEach fun clear() = DungeonScan.reset()

    private fun room(x: Int, z: Int) = DungeonScan.tiles[x + z * 6].room

    @Test fun theFirstFrameIsTheEntranceAndADoorToAnUnknownRoom() {
        MapScan.read(MapModel.render(HudFixtures.bigState()))
        assertEquals(16, DungeonScan.roomSize)
        assertEquals(5, DungeonScan.startX)
        assertEquals(5, DungeonScan.startY)

        val entrance = assertNotNull(room(2, 0))
        assertEquals(RoomType.ENTRANCE, entrance.type)
        assertEquals(MapCheckmark.NONE, entrance.checkmark)
        assertNull(room(2, 1), "a grey room is only a door's far side until it's opened")

        val door = DungeonScan.doors.values.single()
        assertEquals(GridPos(2, 0), door.position)
        assertEquals(DoorRotation.Vertical, door.rotation)
        assertTrue(DungeonScan.pathHints.any { it.position == GridPos(2, 1) }, "the tile behind the door is a path hint")
    }

    @Test fun anOpenedRoomHasItsTypeCheckMarkAndDoors() {
        val st = HudFixtures.bigState()
        st.setCheck(0, CheckMark.GREEN)
        st.setCheck(1, CheckMark.NONE); st.rooms[1].visitors += HudFixtures.me
        MapScan.read(MapModel.render(st))

        val puzzle = assertNotNull(room(2, 1))
        assertEquals(RoomType.PUZZLE, puzzle.type)
        assertEquals(MapCheckmark.NONE, puzzle.checkmark)

        fun door(x: Int, z: Int, rotation: DoorRotation) = DungeonScan.doors.values.single { it.position == GridPos(x, z) && it.rotation == rotation }
        assertEquals(DoorType.Wither, door(2, 1, DoorRotation.Horizontal).type)
        assertEquals(DoorType.Blood, door(2, 1, DoorRotation.Vertical).type)
    }

    @Test fun roomDataFindsARoomByItsCore() {
        val water = assertNotNull(com.engineerclient.ads.map.RoomData.of(379499781))
        assertEquals("Water Board", water.name)
        assertEquals(RoomType.PUZZLE, water.type)
    }

    @Test fun aRoomsCheckMarkFollowsTheMap() {
        val st = HudFixtures.bigState()
        st.setCheck(1, CheckMark.WHITE)
        MapScan.read(MapModel.render(st))
        assertEquals(MapCheckmark.WHITE, room(2, 1)?.checkmark)
        st.setCheck(1, CheckMark.GREEN)
        MapScan.read(MapModel.render(st))
        assertEquals(MapCheckmark.GREEN, room(2, 1)?.checkmark)
    }
}
