// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.map

/** The map the HUD editor shows (Odin's example map): a made-up F7 clear, half done. */
object MinimapExample {
    private fun room(type: RoomType, x: Int, z: Int, rotation: RoomRotation, checkmark: MapCheckmark, walkedInto: Boolean, core: Int? = null): DungeonRoom {
        val data = core?.let { RoomData.of(it) }
        return DungeonRoom(type, GridPos(x, z), data).apply {
            data?.let { shape = it.shape }
            this.rotation = rotation
            this.checkmark = checkmark
            this.walkedInto = walkedInto
            mapSeen = true
        }
    }

    val rooms: List<DungeonRoom> by lazy {
        listOf(
            room(RoomType.ENTRANCE, 2, 0, RoomRotation.NORTH, MapCheckmark.GREEN, true),
            room(RoomType.NORMAL, 0, 0, RoomRotation.WEST, MapCheckmark.NONE, true, 1051405699),
            room(RoomType.PUZZLE, 1, 0, RoomRotation.NORTH, MapCheckmark.NONE, true, 379499781),
            room(RoomType.NORMAL, 3, 0, RoomRotation.WEST, MapCheckmark.WHITE, true, 90256084),
            room(RoomType.NORMAL, 4, 0, RoomRotation.SOUTH, MapCheckmark.NONE, true, 1024359556),
            room(RoomType.NORMAL, 1, 1, RoomRotation.SOUTH, MapCheckmark.WHITE, true, 1250712883),
            room(RoomType.NORMAL, 4, 1, RoomRotation.SOUTH, MapCheckmark.NONE, true, 950592972),
            room(RoomType.NORMAL, 1, 3, RoomRotation.NORTH, MapCheckmark.WHITE, true, -333637832),
            room(RoomType.FAIRY, 2, 3, RoomRotation.SOUTH, MapCheckmark.GREEN, true),
            room(RoomType.CHAMPION, 4, 3, RoomRotation.SOUTH, MapCheckmark.GREEN, false, -1334473473),
            room(RoomType.TRAP, 5, 3, RoomRotation.NORTH, MapCheckmark.GREEN, false, 1590699551),
            room(RoomType.PUZZLE, 0, 4, RoomRotation.WEST, MapCheckmark.NONE, true, 799715466),
            room(RoomType.NORMAL, 1, 4, RoomRotation.EAST, MapCheckmark.WHITE, true, 1484939648),
            room(RoomType.NORMAL, 2, 4, RoomRotation.WEST, MapCheckmark.WHITE, true, -1764045332),
            room(RoomType.NORMAL, 3, 4, RoomRotation.NORTH, MapCheckmark.NONE, false, 76347246),
            room(RoomType.NORMAL, 4, 4, RoomRotation.WEST, MapCheckmark.NONE, false, -1899702429),
            room(RoomType.NORMAL, 5, 4, RoomRotation.WEST, MapCheckmark.WHITE, false, -151940807),
            room(RoomType.NORMAL, 0, 5, RoomRotation.SOUTH, MapCheckmark.WHITE, true, 259768244),
            room(RoomType.BLOOD, 2, 5, RoomRotation.EAST, MapCheckmark.NONE, true),
            room(RoomType.NORMAL, 3, 5, RoomRotation.SOUTH, MapCheckmark.NONE, false, 222124420),
        )
    }

    val doors: List<DungeonDoor> = listOf(
        DungeonDoor(GridPos(2, 0), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(1, 0), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(0, 1), DoorRotation.Horizontal, DoorType.Normal),
        DungeonDoor(GridPos(2, 2), DoorRotation.Horizontal, DoorType.Normal),
        DungeonDoor(GridPos(1, 2), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(4, 0), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(3, 1), DoorRotation.Horizontal, DoorType.Normal),
        DungeonDoor(GridPos(3, 3), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(1, 3), DoorRotation.Horizontal, DoorType.Fairy),
        DungeonDoor(GridPos(2, 3), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(4, 3), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(5, 3), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(0, 4), DoorRotation.Horizontal, DoorType.Normal),
        DungeonDoor(GridPos(1, 4), DoorRotation.Horizontal, DoorType.Normal),
        DungeonDoor(GridPos(1, 4), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(3, 4), DoorRotation.Horizontal, DoorType.Normal),
        DungeonDoor(GridPos(3, 4), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(5, 4), DoorRotation.Vertical, DoorType.Normal),
        DungeonDoor(GridPos(1, 5), DoorRotation.Horizontal, DoorType.Blood),
    )
}
