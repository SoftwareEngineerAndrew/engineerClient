package com.engineerclient.p3sim

import com.engineerclient.EngineerClient
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Heightmap
import java.io.DataInputStream
import java.util.EnumSet
import java.util.zip.GZIPInputStream

/**
 * The F7 boss arena, block for block, at Hypixel's own coordinates (built from the ADR room
 * library). The sim world's void generator
 * fills each chunk from it as the chunk is first generated ([fill]), so the world is the arena from
 * its first tick, lit by the game's own lighting pass. The blocks that change during a fight
 * (gates, doors, levers, devices) are put back with [restore].
 */
object Arena {
    class Data(val x0: Int, val y0: Int, val z0: Int, val w: Int, val h: Int, val d: Int, val states: Array<BlockState>, val cells: ShortArray) {
        fun get(x: Int, y: Int, z: Int): BlockState {
            if (x < x0 || y < y0 || z < z0 || x >= x0 + w || y >= y0 + h || z >= z0 + d) return Blocks.AIR.defaultBlockState()
            return states[cells[((y - y0) * d + (z - z0)) * w + (x - x0)].toInt()]
        }
    }

    @Volatile private var loaded: Data? = null

    /** The arena, read once from the mod jar (about 13 MB in memory; dropped with [unload]). */
    val data: Data
        get() = loaded ?: synchronized(this) { loaded ?: ArenaFixes.patch(readBin("arena.bin")).also { loaded = it } }

    fun unload() { loaded = null }

    internal fun readBin(name: String): Data {
        val stream = Arena::class.java.getResourceAsStream("/assets/engineerclient/p3sim/$name") ?: error("$name missing from the jar")
        DataInputStream(GZIPInputStream(stream.buffered(), 1 shl 16).buffered()).use { inp ->
            val magic = ByteArray(4).also { inp.readFully(it) }
            require(String(magic) == "P3A1") { "bad arena.bin" }
            val x0 = inp.readInt(); val y0 = inp.readInt(); val z0 = inp.readInt()
            val w = inp.readInt(); val h = inp.readInt(); val d = inp.readInt()
            val states = Array(inp.readInt()) {
                val s = ByteArray(inp.readUnsignedShort()).also { b -> inp.readFully(b) }.toString(Charsets.UTF_8)
                parse(s)
            }
            val cells = ShortArray(w * h * d)
            var i = 0
            while (i < cells.size) {
                val n = varint(inp); val v = varint(inp).toShort()
                cells.fill(v, i, minOf(cells.size, i + n)); i += n
            }
            return Data(x0, y0, z0, w, h, d, states, cells)
        }
    }

    private fun varint(inp: DataInputStream): Int {
        var v = 0; var shift = 0
        while (true) {
            val b = inp.readUnsignedByte()
            v = v or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) return v
            shift += 7
        }
    }

    fun parse(s: String): BlockState = try {
        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState()
    } catch (t: Throwable) {
        EngineerClient.logger.warn("[p3sim] unknown block state {}", s)
        Blocks.AIR.defaultBlockState()
    }

    /**
     * Fills a freshly generated chunk of the sim world with its part of the arena (straight into the
     * sections: no updates, nothing ticks). Called from the generator on a worker thread.
     */
    fun fill(chunk: ChunkAccess) {
        if (fillBlocks(chunk)) Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG))
    }

    /** Writes the arena's blocks (and its player heads) into [chunk]; false when the chunk is outside the arena's box. Worker thread. */
    internal fun fillBlocks(chunk: ChunkAccess): Boolean {
        val a = data
        val cx = chunk.pos.minBlockX; val cz = chunk.pos.minBlockZ
        if (cx + 16 <= a.x0 || cz + 16 <= a.z0 || cx >= a.x0 + a.w || cz >= a.z0 + a.d) return false
        val air = Blocks.AIR.defaultBlockState()
        for (y in a.y0 until a.y0 + a.h) {
            val si = chunk.getSectionIndex(y)
            if (si < 0 || si >= chunk.sections.size) continue
            val section = chunk.getSection(si)
            for (z in 0 until 16) for (x in 0 until 16) {
                val s = a.get(cx + x, y, cz + z)
                if (s !== air) section.setBlockState(x, y and 15, z, s, false)
            }
        }
        ArenaFixes.skulls(chunk)
        return true
    }

    /** Puts [pos] back to the arena's start state (with the client told, no neighbour updates). */
    fun restore(level: net.minecraft.server.level.ServerLevel, pos: BlockPos) {
        val s = data.get(pos.x, pos.y, pos.z)
        if (level.getBlockState(pos) != s) { level.setBlock(pos, s, FLAGS); ArenaFixes.reskull(level, pos) }
    }

    /**
     * Every block of the arena as built: the whole arena compared, chunk section by section, and any block that
     * differs put back (whatever changed it, in this game or a saved one). Returns how many were.
     */
    fun resync(level: net.minecraft.server.level.ServerLevel): Int {
        val a = data
        var fixed = 0
        for (cx in (a.x0 shr 4)..((a.x0 + a.w - 1) shr 4)) for (cz in (a.z0 shr 4)..((a.z0 + a.d - 1) shr 4)) {
            val chunk = level.getChunk(cx, cz)
            val xs = maxOf(a.x0, cx shl 4) until minOf(a.x0 + a.w, (cx shl 4) + 16)
            val zs = maxOf(a.z0, cz shl 4) until minOf(a.z0 + a.d, (cz shl 4) + 16)
            for (y in a.y0 until a.y0 + a.h) {
                val si = chunk.getSectionIndex(y)
                if (si < 0 || si >= chunk.sections.size) continue
                val section = chunk.getSection(si)
                for (z in zs) for (x in xs) {
                    val want = a.get(x, y, z)
                    if (section.getBlockState(x and 15, y and 15, z and 15) == want) continue
                    val pos = BlockPos(x, y, z)
                    level.setBlock(pos, want, FLAGS)
                    ArenaFixes.reskull(level, pos)
                    fixed++
                }
            }
        }
        return fixed
    }

    fun set(level: net.minecraft.server.level.ServerLevel, pos: BlockPos, state: BlockState) {
        if (level.getBlockState(pos) != state) level.setBlock(pos, state, FLAGS)
    }

    /** Clients told, no shape or neighbour updates (nothing pops off, nothing flows). */
    const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE
}
