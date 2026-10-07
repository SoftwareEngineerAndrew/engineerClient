package com.engineerclient.betterpf

import com.engineerclient.EngineerClient
import com.odtheking.odin.features.impl.dungeon.map.WorldScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomShape
import com.odtheking.odin.utils.IVec2
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Which copy in the room library a dungeon room is: "Name|ROTATION", plus "|core" for 1x1 rooms
 * that come in several variants (Odin lists more than one core for them: the Entrance, Default,
 * some traps and puzzles), so each variant gets its own copy instead of sharing one.
 */
object RoomKeys {
    private val cores = HashMap<Pair<Int, Int>, Int>()

    fun reset() = cores.clear()

    /**
     * The variant of a 1x1 room: Odin's core (a hash of the room's centre column) of its tile, once
     * that chunk is loaded; null for rooms with only one variant, or until it can be read.
     */
    fun core(room: DungeonRoom): Int? {
        val data = room.data ?: return null
        if (room.shape != RoomShape.OneByOne || data.cores.size < 2) return null
        val tile = room.tiles.firstOrNull() ?: return null
        cores[tile.x to tile.z]?.let { return it }
        val cx = (tile.x - 6) * 2; val cz = (tile.z - 6) * 2
        val chunk = EngineerClient.mc.level?.chunkSource?.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: return null
        val core = WorldScan.getRoomCore(chunk, IVec2(cx * 16 + 7, cz * 16 + 7)).first
        if (core !in data.cores) return null // not fully loaded yet
        cores[tile.x to tile.z] = core
        return core
    }

    /**
     * Whether Odin's rotation is the room's real one. 1x1 rooms: only once it found the blue clay
     * in the room's corner - otherwise the rotation is a placeholder (a room only seen on the map
     * gets WEST). Fairy rooms have no clay (Odin always says SOUTH): the viewer turns them to fit.
     */
    fun rotationKnown(room: DungeonRoom): Boolean =
        room.shape != RoomShape.OneByOne || room.clayPos != null || room.data?.name == "Fairy"

    /** The room's library key, or null while its rotation or variant isn't known yet. */
    fun key(room: DungeonRoom): String? {
        val name = room.data?.name ?: return null
        val rotation = room.rotation ?: return null
        if (!rotationKnown(room)) return null
        val multi = room.shape == RoomShape.OneByOne && (room.data?.cores?.size ?: 0) > 1
        if (!multi) return "$name|${rotation.name}"
        val core = core(room) ?: return null
        return "$name|${rotation.name}|$core"
    }
}
