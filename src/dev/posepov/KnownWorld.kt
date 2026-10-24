package com.devgineerclient.posepov

import com.engineerclient.adr.RoomKeys
import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.clear.layout.BlockStates
import com.engineerclient.p3sim.pack.BundledPacks
import com.engineerclient.p3sim.pack.PackFiles
import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.PackSource
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.WorldScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.utils.IVec2
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The blocks Pose POV draws, the way ADR rebuilds a run: what we already know, then this run's block changes.
 *  1. A block the server changed since the world loaded ([changed], straight off the packets) - doors opening, P3's gates.
 *  2. F7's boss: the shipped arena (p3sim's [Arena], built from the ADR library), at x, z >= -8.
 *  3. A dungeon room Odin has named and turned: its design from the bundled ADS room pack, placed by [Placement].
 *  4. Anything else (the gaps and doors between rooms, rooms not identified yet, every other island): the loaded chunks.
 */
object KnownWorld {
    private val AIR: BlockState = Blocks.AIR.defaultBlockState()
    private const val ARENA_MIN = -8

    private class Placed(val key: String, val p: Placement, val v: RoomVolume) {
        val states: Array<BlockState> = Array(v.palette.size) { i ->
            val s = v.palette[i]
            if (i == 0 || s == "minecraft:air") AIR else BlockStates.rotated(s, p.quarterTurns)
        }
        val x1 = p.originX + p.boxW - 1
        val z1 = p.originZ + p.boxD - 1
    }

    private val changed = ConcurrentHashMap<Long, BlockState>()
    /** The placed room on each of the 6x6 tiles. */
    private val tiles = arrayOfNulls<Placed>(36)
    @Volatile private var pack: RoomPack? = null
    @Volatile private var arenaReady = false
    private var loading = false

    fun change(pos: BlockPos, state: BlockState) { changed[pos.asLong()] = state }

    fun reset() {
        changed.clear()
        tiles.fill(null)
    }

    /** Starts reading the room pack and the arena off-thread the first time they're wanted; [onReady] runs on arrival. */
    fun load(onReady: () -> Unit) {
        if (loading) return
        loading = true
        CompletableFuture.runAsync {
            pack = Packs.current?.rooms ?: run {
                val dir = PackFiles.defaultDir()
                val source = PackSource.resolve(dir, BundledPacks.JAR, PackFiles.cacheRootFor(dir), PackReport(), listOf(PackFiles.ROOMS_INDEX, PackFiles.ROOMS_BLOCKS))
                RoomPack.load(source)
            }
            Arena.data
            arenaReady = true
        }.whenComplete { _, _ -> onReady() }
    }

    /**
     * Places every room Odin has named with a rotation it is sure of; a room that is new or changed calls [dirty] with its
     * box (x0, z0, x1, z1). Render thread, every frame the menu is up: cheap when nothing changed.
     */
    fun refresh(level: ClientLevel, dirty: (Int, Int, Int, Int) -> Unit) {
        val pack = pack ?: return
        for (room in DungeonScan.rooms) {
            val name = room.data?.name ?: continue
            val rot = Rot.fromWord(room.rotation?.name) ?: continue
            if (!RoomKeys.rotationKnown(room) || room.tiles.isEmpty()) continue
            val ts = room.tiles.map { Tile(it.x, it.z) }
            if (ts.any { it.x !in 0..5 || it.z !in 0..5 }) continue
            val core = core(level, room)
            val key = "$name|$rot|$ts|$core"
            if (tiles[ts[0].z * 6 + ts[0].x]?.key == key) continue
            val entry = core?.let { pack.designByCore(it) }
            val design = entry?.let { pack.design(it.design) } ?: pack.designsByName(name).firstOrNull() ?: continue
            val placed = try {
                val p = design.place(rot, ts)
                val v = pack.volumeFor(design, entry?.takeIf { it.design == design.id }?.variant) ?: continue
                Placed(key, p, v)
            } catch (e: Exception) { continue }
            for (t in ts) tiles[t.z * 6 + t.x] = placed
            dirty(placed.p.originX, placed.p.originZ, placed.x1, placed.z1)
        }
    }

    /** Odin's core of the room's first tile (it names the variant), once that chunk is loaded. */
    private fun core(level: ClientLevel, room: DungeonRoom): Int? {
        val t = room.tiles.first()
        val cx = (t.x - 6) * 2; val cz = (t.z - 6) * 2
        val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: return null
        return WorldScan.getRoomCore(chunk, IVec2(cx * 16 + 7, cz * 16 + 7)).first.takeIf { room.data?.cores?.contains(it) == true }
    }

    private fun arena(x: Int, z: Int) = arenaReady && x >= ARENA_MIN && z >= ARENA_MIN && DungeonUtils.floor == Floor.F7

    private fun roomAt(x: Int, z: Int): Placed? {
        val tx = Math.floorDiv(x + 200, 32); val tz = Math.floorDiv(z + 200, 32)
        if (tx !in 0..5 || tz !in 0..5) return null
        return tiles[tz * 6 + tx]
    }

    /** Whether no known data covers the section, so an unloaded or all-air chunk section means it is empty. */
    fun unknown(sx: Int, sz: Int): Boolean {
        val x0 = sx shl 4; val z0 = sz shl 4
        if (arena(x0 + 15, z0 + 15)) return false
        for (x in intArrayOf(x0, x0 + 15)) for (z in intArrayOf(z0, z0 + 15)) if (roomAt(x, z) != null) return false
        return true
    }

    fun stateAt(level: ClientLevel, pos: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): BlockState {
        if (changed.isNotEmpty()) changed[BlockPos.asLong(x, y, z)]?.let { return it }
        if (arena(x, z)) return Arena.data.get(x, y, z)
        roomAt(x, z)?.let { r ->
            val c = r.p.toCanonical(x, z)
            if (c != null) return if (r.v.contains(c.first, y, c.second)) r.states[r.v.paletteIndexAt(c.first, y, c.second)] else AIR
        }
        return level.getBlockState(pos.set(x, y, z))
    }
}
