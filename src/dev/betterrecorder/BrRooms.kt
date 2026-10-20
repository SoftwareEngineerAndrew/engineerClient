package com.devgineerclient.betterrecorder

import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.WorldScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomShape
import net.minecraft.core.BlockPos

/**
 * Where something is in the dungeon, in the frame ADS builds rooms in: Odin's room (name, rotation, the blue clay corner) and the
 * position relative to that corner, turned to north (Odin's `getRelativeCoords`, the same frame as the rooms pack and the secrets'
 * canonical cells). Game thread only (Odin's scan is read here).
 */
object BrRooms {
    /** The 6x6 tile a block column is in (index x + 6z), or null outside the grid. */
    fun tileOf(bx: Int, bz: Int): Int? {
        val tx = (bx + 201) shr 5
        val tz = (bz + 201) shr 5
        return if (tx in 0..5 && tz in 0..5) tx + tz * 6 else null
    }

    fun roomAt(bx: Int, bz: Int): DungeonRoom? {
        val tile = tileOf(bx, bz) ?: return null
        val tx = tile % 6; val tz = tile / 6
        return runCatching { DungeonScan.rooms.firstOrNull { r -> r.tiles.any { it.x == tx && it.z == tz } } }.getOrNull()
    }

    /** Whether the room's rotation is known for sure (a 1x1 needs its clay corner; Fairy has none). */
    fun rotationKnown(r: DungeonRoom): Boolean = r.rotation != null && (r.shape != RoomShape.OneByOne || r.clayPos != null || r.data?.name == "Fairy")

    /** `"r":["Name",rx,ry,rz]` for the block at x y z, or `"r":["Name"]` when the frame is not known yet, or nothing outside rooms. */
    fun tag(j: BrJson, x: Int, y: Int, z: Int) {
        val r = roomAt(x, z) ?: return
        val name = r.data?.name ?: "?"
        if (r.clayPos == null || r.rotation == null) { j.raw("r", "[${BrJson.q(name)}]"); return }
        val rel = runCatching { r.getRelativeCoords(BlockPos(x, y, z)) }.getOrNull()
        if (rel == null) j.raw("r", "[${BrJson.q(name)}]")
        else j.raw("r", "[${BrJson.q(name)},${rel.x},${rel.y},${rel.z}]")
    }

    fun tag(j: BrJson, x: Double, y: Double, z: Double) = tag(j, Math.floor(x).toInt(), Math.floor(y).toInt(), Math.floor(z).toInt())

    /** The room the player stands in, by Odin's own reckoning (its current room). */
    fun current(): DungeonRoom? = runCatching { WorldScan.currentRoom }.getOrNull()

    /** One room as a JSON object: everything Odin knows about it. */
    fun describe(r: DungeonRoom): String {
        val j = BrJson(256)
        j.s("name", r.data?.name)
        j.s("type", runCatching { r.data?.type?.name }.getOrNull())
        j.s("shape", r.shape.name)
        j.s("rot", r.rotation?.name)
        j.b("rotKnown", rotationKnown(r))
        j.bp("clay", r.clayPos)
        j.raw("tiles", r.tiles.joinToString(",", "[", "]") { "[${it.x},${it.z}]" })
        j.s("check", runCatching { r.checkmark.name }.getOrNull())
        j.i("found", r.foundSecrets)
        j.i("max", r.data?.maxSecrets)
        j.i("crypts", r.data?.crypts)
        j.i("trapped", r.data?.trappedChests)
        j.b("walked", r.walkedInto)
        j.i("highest", r.highestBlock)
        return "{$j}"
    }

    /** The world box of the room's tiles (x and z), with the gaps between them. */
    fun box(r: DungeonRoom): IntArray? {
        if (r.tiles.isEmpty()) return null
        val minX = r.tiles.minOf { it.x } * 32 - 201; val maxX = r.tiles.maxOf { it.x } * 32 - 201 + 31
        val minZ = r.tiles.minOf { it.z } * 32 - 201; val maxZ = r.tiles.maxOf { it.z } * 32 - 201 + 31
        return intArrayOf(minX, minZ, maxX, maxZ)
    }
}
