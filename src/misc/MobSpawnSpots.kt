package com.engineerclient.misc

import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.dungeon.map.tile.RoomShape
import com.engineerclient.dungeonpack.pack.BundledPacks
import com.engineerclient.dungeonpack.pack.PackFiles
import com.engineerclient.dungeonpack.pack.PackReport
import com.engineerclient.dungeonpack.pack.PackSource
import com.engineerclient.dungeonpack.pack.Packs
import com.engineerclient.dungeonpack.pack.Rot
import com.engineerclient.dungeonpack.pack.Tile
import com.engineerclient.dungeonpack.pack.readGzipJson
import com.engineerclient.dungeonpack.pack.mobs.MobArchetype
import com.engineerclient.dungeonpack.pack.mobs.MobPack
import com.engineerclient.dungeonpack.pack.rooms.RoomPack
import com.engineerclient.pov.KnownWorld
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import java.util.concurrent.CompletableFuture

/** The blocks starred mobs spawn on in the scanned rooms, from the mob tables, placed as each room is found and turned. */
internal object MobSpawnSpots {
    @Volatile private var ownRooms: RoomPack? = null
    @Volatile private var ownMobs: MobPack? = null
    private var loading = false
    private val spots = Long2ObjectOpenHashMap<MobArchetype>()
    private val placed = HashSet<String>()

    fun clear() { spots.clear(); placed.clear() }

    fun refresh() {
        val rooms = KnownWorld.roomPack ?: Packs.current?.rooms ?: ownRooms
        val mobs = Packs.current?.mobs ?: ownMobs
        if (rooms == null || mobs == null) return load()
        for (room in DungeonScan.rooms) runCatching { add(room, rooms, mobs) }
    }

    /** The archetype spawning on or next to block (x, y, z), or null. */
    fun near(x: Int, y: Int, z: Int): MobArchetype? {
        if (spots.isEmpty()) return null
        for (dx in -1..1) for (dz in -1..1) spots.get(BlockPos.asLong(x + dx, y, z + dz))?.let { return it }
        return null
    }

    private fun add(room: DungeonRoom, rooms: RoomPack, mobs: MobPack) {
        val name = room.data?.name ?: return
        val rot = Rot.fromWord(room.rotation?.name) ?: return
        if (room.shape == RoomShape.OneByOne && room.clayPos == null) return
        val tiles = room.tiles.map { Tile(it.x, it.z) }
        if (tiles.isEmpty() || tiles.any { it.x !in 0..5 || it.z !in 0..5 }) return
        val key = "$name|$rot|$tiles"
        if (!placed.add(key)) return
        val table = mobs.room(name) ?: return
        val design = table.designs.firstNotNullOfOrNull { rooms.design(it) } ?: rooms.designsByName(name).firstOrNull() ?: return
        val p = design.place(rot, tiles)
        // A block an unstarred mob was ever seen on is no sure sign.
        val unsure = HashSet<Long>()
        for (c in table.cells) if (!c.star) unsure += BlockPos.asLong(c.x, c.y, c.z)
        for (c in table.cells) {
            if (!c.star || BlockPos.asLong(c.x, c.y, c.z) in unsure) continue
            val arch = mobs.archetype(c.arch)?.takeIf { it.star } ?: continue
            val w = p.toWorld(c.x, c.y, c.z)
            spots.putIfAbsent(BlockPos.asLong(w.x, w.y, w.z), arch)
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        CompletableFuture.runAsync {
            ownMobs = runCatching { BundledPacks.JAR.open(PackFiles.MOBS)?.use { MobPack.fromJson(readGzipJson(it)) } }.getOrNull()
            if (KnownWorld.roomPack == null && Packs.current?.rooms == null) {
                // the same files as Secret Waypoints, so both share one unpacked copy
                val dir = PackFiles.defaultDir()
                ownRooms = RoomPack.load(PackSource.resolve(dir, BundledPacks.JAR, PackFiles.cacheRootFor(dir), PackReport(), listOf(PackFiles.ROOMS_INDEX, PackFiles.ROOMS_BLOCKS)))
            }
        }
    }
}
