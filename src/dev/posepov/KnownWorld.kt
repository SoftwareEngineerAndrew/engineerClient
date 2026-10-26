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

    /**
     * One placed room. [tiles] are its grid tiles, [core] the core hash it was named by (when known: what teammates are told),
     * [shared] whether a teammate's game named it rather than this one, [guessed] whether only its edge columns did (RoomEdges).
     */
    class Room(val key: String, val inst: RoomInstance, val tiles: List<Tile>, val core: Int? = null, val shared: Boolean = false, val guessed: Boolean = false) {
        val p = inst.placement
        val v = inst.volume
        /** The pack variant it was built from, when not the design's base. */
        val variant: String? = key.substringAfterLast('|').takeIf { it != "null" }
        private val keyCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
        /** This room's own key for the piece at (lx, ly, lz) of its unturned box (16-block steps), made once. */
        fun pieceKey(lx: Int, ly: Int, lz: Int): String = keyCache.getOrPut((lx shl 16) or ((ly and 0xFF) shl 8) or lz) { "$key/$lx/$ly/$lz" }
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

        /** Whether its pieces leave out what faces its sealed pockets ([RoomCut]): until this run opens a way into one. */
        @Volatile var cutPockets = true
        @Volatile private var cut: RoomCut? = null
        /** What of it nobody sees, worked out the first time it's asked for (any thread; the pockets take a few ms). */
        fun cut(): RoomCut = cut ?: synchronized(this) {
            cut ?: run {
                val d = inst.design
                val opaque = { x: Int, y: Int, z: Int -> v.contains(x, y, z) && states[v.paletteIndexAt(x, y, z)].isSolidRender }
                RoomCut(d.w, d.d, d.y0, d.h, runCatching { RoomCut.pockets(d.w, d.d, d.y0, d.h, opaque) }.getOrNull())
            }.also { cut = it }
        }
        /** Whether world cell ([x], [y], [z]) is in one of its sealed pockets (false until they're worked out). */
        fun inPocket(x: Int, y: Int, z: Int): Boolean {
            val c = cut ?: return false
            val (cx, cz) = p.toCanonical(x, z) ?: return false
            return c.hiddenAt(cx, y, cz)
        }
    }

    private val changed = ConcurrentHashMap<Long, BlockState>()
    /** Cells whose block isn't the known one any more (a change, or a door edit), for the mesher to rebuild around; any thread. */
    val touchedCells = ConcurrentLinkedQueue<Long>()
    @Volatile private var edits: Map<Long, BlockState> = emptyMap()
    private var editSections: Set<Long> = emptySet()
    private val changedSections = ConcurrentHashMap.newKeySet<Long>()
    /** The placed room on each of the 6x6 tiles. */
    private val tiles = arrayOfNulls<Room>(36)
    // Worked out again only when a room is placed (it was asked for several times a frame).
    @Volatile private var roomList: List<Room>? = null
    val rooms: List<Room> get() = roomList ?: tiles.filterNotNull().distinct().also { roomList = it }
    private var doorsKey = ""
    @Volatile private var pack: RoomPack? = null
    val roomPack: RoomPack? get() = pack
    @Volatile private var arenaReady = false
    private var loading = false

    /** The cells this run changed (any thread; a live view). */
    val changedCells: Set<Long> get() = changed.keys
    /** The room on tile [t] (z * 6 + x), placed. */
    fun tile(t: Int): Room? = tiles[t]

    /** Every change this run, again: for pieces that now stand where they landed (the arena, once it is read). */
    fun requeueChanges() { touchedCells.addAll(changed.keys) }

    fun change(pos: BlockPos, state: BlockState) {
        val k = pos.asLong()
        changed[k] = state
        changedSections.add(SectionPos.asLong(pos.x shr 4, pos.y shr 4, pos.z shr 4))
        touchedCells.add(k)
    }

    /** A room as a teammate's game named it: its core hash (which names design and variant in the pack), turn and tiles. */
    class SharedRoom(val core: Int, val rot: Rot, val tiles: List<Tile>)

    /** Each linked teammate's rooms, the latest they sent (any thread). */
    private val sharedBy = ConcurrentHashMap<String, List<SharedRoom>>()
    fun shared(from: String, rooms: List<SharedRoom>) { sharedBy[from] = rooms }

    /** The rooms this game named itself, with their cores: for teammates. */
    fun mine(): List<SharedRoom> = rooms.filter { !it.shared && it.core != null }.map { SharedRoom(it.core!!, it.p.rot, it.tiles) }

    /** [r] on its tiles; whatever was on any of them before is gone from all of its own. */
    private fun put(r: Room) {
        for (t in r.tiles) tiles[t.z * 6 + t.x]?.takeIf { it !== r }?.let { old -> for (i in tiles.indices) if (tiles[i] === old) tiles[i] = null }
        for (t in r.tiles) tiles[t.z * 6 + t.x] = r
        roomList = null
    }

    fun reset() {
        sharedBy.clear(); RoomEdges.reset()
        changed.clear(); changedSections.clear(); touchedCells.clear()
        tiles.fill(null); roomList = null; edits = emptyMap(); editSections = emptySet(); doorsKey = ""
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
    fun refresh(level: ClientLevel, edges: Boolean, placed: (Room) -> Unit) {
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
                Room(key, RoomInstance(design, pack.volumeFor(design, variant) ?: continue, p), ts, entry?.takeIf { it.design == design.id }?.let { core })
            } catch (e: Exception) { continue }
            put(r)
            placed(r)
            // Changes from before it was placed now land on its pieces.
            for (k in changed.keys) if (r.covers(BlockPos.getX(k), BlockPos.getZ(k))) touchedCells.add(k)
            changedRooms = true
        }
        // Teammates' rooms, where this game hasn't named one (yours win; between teammates, the first placed stays): each
        // only as the room pack has it (its core names the design and variant, which must take that turn and that many tiles).
        for (list in sharedBy.values) for (s in list) {
            val idx = s.tiles.map { it.z * 6 + it.x }
            if (idx.any { tiles[it]?.guessed == false }) continue
            val entry = pack.designByCore(s.core) ?: continue
            val design = pack.design(entry.design) ?: continue
            if (s.rot !in design.rotations || s.tiles.size != design.tiles.size) continue
            val r = try {
                Room("${design.id}|${s.rot}|${s.tiles}|shared|${entry.variant}", RoomInstance(design, pack.volumeFor(design, entry.variant) ?: continue, design.place(s.rot, s.tiles)), s.tiles, s.core, true)
            } catch (e: Exception) { continue }
            put(r)
            placed(r)
            for (k in changed.keys) if (r.covers(BlockPos.getX(k), BlockPos.getZ(k))) touchedCells.add(k)
            changedRooms = true
        }
        // Rooms nobody has named yet, from their edge columns (until Odin or a teammate does).
        if (edges) RoomEdges.step(level, pack) { tiles[it] != null }?.let { f ->
            val r = try {
                Room("${f.design.id}|${f.rot}|${f.tiles}|guess|null", RoomInstance(f.design, pack.volume(f.design) ?: return@let, f.design.place(f.rot, f.tiles)), f.tiles, guessed = true)
            } catch (e: Exception) { return@let }
            put(r)
            placed(r)
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

    /** Odin's core of the first of the room's tiles whose chunk is loaded and has one of its cores (it names the variant). */
    private fun core(level: ClientLevel, room: DungeonRoom): Int? {
        for (t in room.tiles) {
            val cx = (t.x - 6) * 2; val cz = (t.z - 6) * 2
            val chunk = level.chunkSource.getChunk(cx, cz, ChunkStatus.FULL, false) as? LevelChunk ?: continue
            WorldScan.getRoomCore(chunk, IVec2(cx * 16 + 7, cz * 16 + 7)).first.takeIf { room.data?.cores?.contains(it) == true }?.let { return it }
        }
        return null
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
    fun stateAt(level: ClientLevel, pos: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): BlockState =
        known(x, y, z) ?: level.getBlockState(pos.set(x, y, z))

    /**
     * The block at a cell when anything but the loaded chunks says what it is (a change, a door edit, the arena, a room), else
     * null. Any thread (a builder reads a copy of the chunks for the rest: BlockMesh's snapshots).
     */
    fun known(x: Int, y: Int, z: Int): BlockState? {
        val k = BlockPos.asLong(x, y, z)
        if (changed.isNotEmpty()) changed[k]?.let { return it }
        edits[k]?.let { return it }
        if (arena(x, z)) return Arena.data.get(x, y, z)
        roomAt(x, z)?.let { r -> return r.designAt(x - r.x0, y, z - r.z0) }
        return null
    }
}
