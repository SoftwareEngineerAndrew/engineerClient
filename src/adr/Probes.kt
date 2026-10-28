package com.engineerclient.adr

import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.tile.RoomType
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * Research probes for All Data (undonecoffee.com/alldata): things measured across many runs that the replay
 * itself doesn't need. Kept apart from the rest of ADR so they can go without touching it: delete this file
 * and the one line in RunRecorder that calls it.
 *
 * Puzzle rooms whose layout varies run to run (Boulder's boxes, Water Board's pattern and gates): every block of
 * the room once, as soon as Odin has named it and its chunks are here - before anyone has touched it, as a
 * rule. Which layouts come up and how often, and whether any is missing from the hand-captured sets.
 *
 * {"k":"probe","t":t,"what":"room","name":name,"at":[x0,y0,z0],"size":[w,h,d],"pal":[state, ...],
 *  "rle":[palette index, run, ...]} - blocks in y, then z, then x order from the room's north-west bottom
 *  corner (world coordinates, not turned to the room's frame). About 1-2 KB a room once compressed.
 */
class Probes(private val emit: (String) -> Unit) {

    private val done = HashSet<String>()

    fun tick(level: ClientLevel, t: Int) {
        if (t % 20 != 0) return
        for (room in DungeonScan.rooms) {
            if (room.type != RoomType.PUZZLE) continue
            val name = room.name ?: continue
            if (name !in ROOMS || name in done) continue
            val tile = room.tiles.firstOrNull() ?: continue
            val x0 = GeometryCapture.GRID_ORIGIN + 32 * tile.x
            val z0 = GeometryCapture.GRID_ORIGIN + 32 * tile.z
            if (!loaded(level, x0, z0)) continue
            done += name
            emit(snapshot(level, t, name, x0, z0))
        }
    }

    private fun snapshot(level: ClientLevel, t: Int, name: String, x0: Int, z0: Int): String {
        val palette = LinkedHashMap<BlockState, Int>()
        val rle = StringBuilder()
        var last = -1; var run = 0
        val pos = BlockPos.MutableBlockPos()
        for (y in Y0 until Y0 + H) for (z in z0 until z0 + SIDE) for (x in x0 until x0 + SIDE) {
            val i = palette.getOrPut(level.getBlockState(pos.set(x, y, z))) { palette.size }
            if (i == last) { run++; continue }
            if (run > 0) rle.append(if (rle.isEmpty()) "" else ",").append(last).append(',').append(run)
            last = i; run = 1
        }
        if (run > 0) rle.append(if (rle.isEmpty()) "" else ",").append(last).append(',').append(run)
        val pal = palette.keys.joinToString(",") { GeometryCapture.jsonString(BlockStateParser.serialize(it)) }
        return """{"k":"probe","t":$t,"what":"room","name":${GeometryCapture.jsonString(name)},"at":[$x0,$Y0,$z0],"size":[$SIDE,$H,$SIDE],"pal":[$pal],"rle":[$rle]}"""
    }

    private fun loaded(level: ClientLevel, x0: Int, z0: Int): Boolean {
        for (cx in (x0 shr 4)..((x0 + SIDE - 1) shr 4)) for (cz in (z0 shr 4)..((z0 + SIDE - 1) shr 4))
            if (level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) == null) return false
        return true
    }

    private companion object {
        val ROOMS = setOf("Boulder", "Water Board")
        const val SIDE = 31
        /** y 50-99: Water Board's whole height (its board goes below the floor); Boulder sits in y 63-69. */
        const val Y0 = 50
        const val H = 50
    }
}
