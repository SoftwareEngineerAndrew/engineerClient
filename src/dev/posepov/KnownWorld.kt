package com.devgineerclient.posepov

import com.engineerclient.adr.RoomKeys
import com.engineerclient.p3sim.Arena
import com.engineerclient.p3sim.clear.layout.BlockStates
import com.engineerclient.p3sim.clear.layout.RegionEdits
import com.engineerclient.p3sim.clear.layout.RoomInstance
import com.engineerclient.p3sim.clear.layout.Slot
import com.engineerclient.p3sim.pack.BundledPacks
import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.PackFiles
import com.engineerclient.p3sim.pack.PackReport
import com.engineerclient.p3sim.pack.PackSource
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.WorldScan
import com.odtheking.odin.features.impl.dungeon.map.tile.DoorType
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.utils.IVec2
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The blocks Pose POV draws, the way ADR rebuilds a run: what we already know, then this run's block changes.
 *  1. A block the server changed since the world loaded ([changed], straight off the packets): doors opening, P3's gates.
 *  2. The doors between the rooms ADS would build for Odin's door map ([RegionEdits]: seals, frames, door bodies).
 *  3. F7/M7's boss: the shipped arena (p3sim's [Arena], built from the ADR library), at x, z >= 0.
 *  4. A dungeon room Odin has named and turned: its design from the bundled ADS room pack ([Room]).
 *  5. Anything else (rooms not identified yet, every other island): the loaded chunks.
 */
object KnownWorld {
    val AIR: BlockState = Blocks.AIR.defaultBlockState()
    /** The arena's sections drawn from the shipped data alone: the ones at x, z >= 0 (its -8 edge shares sections with the dungeon's). */
    val ARENA_SX = 0..8
    val ARENA_SZ = 0..9

    /** One placed room. [designKey] names its geometry, the same for every run that has this design, variant and turn. */
    class Room(val key: String, val inst: RoomInstance) {
        val p = inst.placement
        val v = inst.volume
        val designKey = "${inst.design.id}|${(key.substringAfterLast('|'))}|${p.rot}"
        val states: Array<BlockState> = Array(v.palette.size) { i ->
            val s = v.palette[i]
            if (i == 0 || s == "minecraft:air") AIR else BlockStates.rotated(s, p.quarterTurns)
        }
        val x0 = p.originX; val z0 = p.originZ
        val x1 = x0 + p.boxW - 1; val z1 = z0 + p.boxD - 1
        val y0 = v.y0; val y1 = v.y0 + v.h - 1

        /** The design's own block at box cell ([bx], [y], [bz]), nothing of this run. */
        fun designAt(bx: Int, y: Int, bz: Int): BlockState {
            val c = p.toCanonical(x0 + bx, z0 + bz) ?: return AIR
            return if (v.contains(c.first, y, c.second)) states[v.paletteIndexAt(c.first, y, c.second)] else AIR
        }
        fun covers(x: Int, z: Int) = x in x0..x1 && z in z0..z1
    }

    private val changed = ConcurrentHashMap<Long, BlockState>()
    /** Cells whose block isn't the known one any more (a change, or a door edit), for the mesher to rebuild around; any thread. */
    val touchedCells = ConcurrentLinkedQueue<Long>()
    private var edits: Map<Long, BlockState> = emptyMap()
    private var editSections: Set<Long> = emptySet()
    private val changedSections = ConcurrentHashMap.newKeySet<Long>()
    /** The placed room on each of the 6x6 tiles. */
    private val tiles = arrayOfNulls<Room>(36)
    val rooms: List<Room> get() = tiles.filterNotNull().distinct()
    private var doorsKey = ""
    @Volatile private var pack: RoomPack? = null
    @Volatile private var arenaReady = false
    private var loading = false

    /** Every change this run, again: for pieces that now stand where they landed (the arena, once it is read). */
    fun requeueChanges() { touchedCells.addAll(changed.keys) }

    fun change(pos: BlockPos, state: BlockState) {
        val k = pos.asLong()
        changed[k] = state
        changedSections.add(SectionPos.asLong(pos.x shr 4, pos.y shr 4, pos.z shr 4))
        touchedCells.add(k)
    }

    fun reset() {
        changed.clear(); changedSections.clear(); touchedCells.clear()
        tiles.fill(null); edits = emptyMap(); editSections = emptySet(); doorsKey = ""
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

    /** Whether the boss arena is drawn from the shipped data: F7 and M7 (they differ only in P5). */
    fun bossFloor(): Boolean = arenaReady && DungeonUtils.floor.let { it == Floor.F7 || it == Floor.M7 }

    /**
     * Places every room Odin has named with a rotation it is sure of, and lays the doors between them out again when the rooms
     * or Odin's doors change. [placed] is called for each room new this time. Render thread; cheap when nothing changed.
     */
    fun refresh(level: ClientLevel, placed: (Room) -> Unit) {
        val pack = pack ?: return
        var changedRooms = false
        for (room in DungeonScan.rooms) {
            val name = room.data?.name ?: continue
            val rot = Rot.fromWord(room.rotation?.name) ?: continue
            if (!RoomKeys.rotationKnown(room) || room.tiles.isEmpty()) continue
            val ts = room.tiles.map { Tile(it.x, it.z) }
            if (ts.any { it.x !in 0..5 || it.z !in 0..5 }) continue
            val core = core(level, room)
            val entry = core?.let { pack.designByCore(it) }
            val design = entry?.let { pack.design(it.design) } ?: pack.designsByName(name).firstOrNull() ?: continue
            val variant = entry?.takeIf { it.design == design.id }?.variant
            val key = "$name|$rot|$ts|$variant"
            if (tiles[ts[0].z * 6 + ts[0].x]?.key == key) continue
            val r = try {
                val p = design.place(rot, ts)
                Room(key, RoomInstance(design, pack.volumeFor(design, variant) ?: continue, p))
            } catch (e: Exception) { continue }
            for (t in ts) tiles[t.z * 6 + t.x] = r
            placed(r)
            // Changes from before it was placed now land on its pieces.
            for (k in changed.keys) if (r.covers(BlockPos.getX(k), BlockPos.getZ(k))) touchedCells.add(k)
            changedRooms = true
        }
        val doors = doors()
        val dk = doors.entries.sortedBy { "${it.key}" }.joinToString { "${it.key}=${it.value}" }
        if (!changedRooms && dk == doorsKey) return
        doorsKey = dk
        val old = edits
        edits = try {
            RegionEdits.build(rooms.map { it.inst }, doors).associate { BlockPos.asLong(it.x, it.y, it.z) to BlockStates.rotated(it.state, it.quarterTurns) }
        } catch (e: Exception) { emptyMap() }
        editSections = edits.keys.mapTo(HashSet()) { SectionPos.asLong(BlockPos.getX(it) shr 4, BlockPos.getY(it) shr 4, BlockPos.getZ(it) shr 4) }
        for (k in old.keys) if (edits[k] != old[k]) touchedCells.add(k)
        for (k in edits.keys) if (old[k] != edits[k]) touchedCells.add(k)
    }

    /** Odin's door map as ADS door slots. */
    private fun doors(): Map<Slot, DoorKind> {
        val out = HashMap<Slot, DoorKind>()
        for (d in DungeonScan.doors.values) {
            val x = d.worldX; val z = d.worldZ
            val slot = when {
                Math.floorMod(x - DungeonFrame.ORIGIN - 31, DungeonFrame.PITCH) == 0 -> Slot('E', Math.floorDiv(x - DungeonFrame.ORIGIN, DungeonFrame.PITCH), Math.floorDiv(z - DungeonFrame.ORIGIN, DungeonFrame.PITCH))
                Math.floorMod(z - DungeonFrame.ORIGIN - 31, DungeonFrame.PITCH) == 0 -> Slot('S', Math.floorDiv(x - DungeonFrame.ORIGIN, DungeonFrame.PITCH), Math.floorDiv(z - DungeonFrame.ORIGIN, DungeonFrame.PITCH))
                else -> continue
            }
            out[slot] = when (d.type) {
                DoorType.Wither -> DoorKind.WITHER
                DoorType.Blood -> DoorKind.BLOOD
                DoorType.Fairy -> DoorKind.FAIRY
                else -> DoorKind.NORMAL
            }
        }
        return out
    }

    /** Odin's core of the room's first tile (it names the variant), once that chunk is loaded. */
    private fun core(level: ClientLevel, room: DungeonRoom): Int? {
        val t = room.tiles.first()
        val cx = (t.x - 6) * 2; val cz = (t.z - 6) * 2
        val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: return null
        return WorldScan.getRoomCore(chunk, IVec2(cx * 16 + 7, cz * 16 + 7)).first.takeIf { room.data?.cores?.contains(it) == true }
    }

    private fun arena(x: Int, z: Int) = x >= 0 && z >= 0 && bossFloor()

    fun roomAt(x: Int, z: Int): Room? {
        val tx = Math.floorDiv(x - DungeonFrame.ORIGIN, DungeonFrame.PITCH); val tz = Math.floorDiv(z - DungeonFrame.ORIGIN, DungeonFrame.PITCH)
        if (tx !in 0..5 || tz !in 0..5) return null
        return tiles[tz * 6 + tx]?.takeIf { it.p.toCanonical(x, z) != null }
    }

    /** Whether no room or arena covers any of the section's columns, so an unloaded or all-air chunk section is empty. */
    /**
     * Whether the world section can hold anything to draw: the arena, a loaded section with blocks, or a door edit or block change
     * in it. (Room cells are the rooms' own pieces, never the world's.) Everything else, the sky over the dungeon included, is skipped.
     */
    fun mayHaveBlocks(level: ClientLevel, sx: Int, sy: Int, sz: Int): Boolean {
        if (arena((sx shl 4) + 15, (sz shl 4) + 15)) return true
        val key = SectionPos.asLong(sx, sy, sz)
        if (key in editSections || key in changedSections) return true
        return level.hasChunk(sx, sz) && !level.getChunk(sx, sz).sections[level.getSectionIndexFromSectionY(sy)].hasOnlyAir()
    }

    /** Whether the cell's column is drawn by a room's own mesh or the arena's, not the world sections. */
    fun coveredByRoom(x: Int, z: Int) = roomAt(x, z) != null

    /** The block at a cell, everything known and this run's changes included. */
    fun stateAt(level: ClientLevel, pos: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): BlockState {
        val k = BlockPos.asLong(x, y, z)
        if (changed.isNotEmpty()) changed[k]?.let { return it }
        edits[k]?.let { return it }
        if (arena(x, z)) return Arena.data.get(x, y, z)
        roomAt(x, z)?.let { r -> return r.designAt(x - r.x0, y, z - r.z0) }
        return level.getBlockState(pos.set(x, y, z))
    }
}
