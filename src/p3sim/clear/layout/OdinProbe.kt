package com.engineerclient.p3sim.clear.layout

import com.odtheking.odin.features.impl.dungeon.map.DungeonScan
import com.odtheking.odin.features.impl.dungeon.map.WorldScan
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils

/**
 * What Odin thinks of the world, read from its own scanner (client thread). The spike asks this to learn whether an unmodified Odin
 * names the rooms the layout builder pasted, and finds their rotation. Every read is guarded: if a later Odin renames a member this
 * returns what it can, never throws.
 */
object OdinProbe {
    /** The rooms Odin's world scan has recognised so far. */
    fun rooms(): List<SeenRoom> = runCatching {
        DungeonScan.rooms.map { r ->
            SeenRoom(
                name = r.name,
                rotation = r.rotation?.name,
                tiles = r.tiles.map { it.x to it.z }.toSet(),
                type = r.type.name,
                shape = r.shape.name,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Asks Odin's own `DungeonRoom.get1x1Rotation()` again for every 1x1 room it has named but not yet turned. Odin does this itself every tick
     * outside the boss (the blue corner marker may sit in a chunk that was not loaded when the room was first scanned); the sim's bridge keeps
     * Odin "in the boss" until a run sets the stage, so without this the probe would wait forever for a rotation Odin never retries.
     * Returns how many rooms it tried.
     */
    fun retryRotations(): Int = runCatching {
        var n = 0
        for (r in DungeonScan.rooms) {
            if (r.shape != com.odtheking.odin.features.impl.dungeon.map.tile.RoomShape.OneByOne || r.rotation != null || r.highestBlock == null) continue
            r.get1x1Rotation(); n++
        }
        n
    }.getOrDefault(0)

    /**
     * Odin looks at a tile's centre chunk once, when it loads, and only while it believes it is in a dungeon; a chunk that loaded before the sim's bridge
     * said "dungeon" (the first chunks around the player when a world opens) is queued by Odin and never looked at again, because the sim never posts the
     * location change that would flush the queue. This asks Odin's own `WorldScan.scanChunk` (private, reached by reflection as the bridge reaches
     * `LocationUtils.currentArea`) to look at the centre chunk of every tile it has not named yet, if that chunk is loaded. Returns how many it offered.
     */
    fun rescanUnnamed(): Int = runCatching {
        val level = net.minecraft.client.Minecraft.getInstance().level ?: return 0
        val scan = scanChunk ?: return 0
        var n = 0
        for (tz in 0 until 6) for (tx in 0 until 6) {
            if (DungeonScan.tiles[tx + tz * 6].room?.data != null) continue
            val chunk = level.chunkSource.getChunkNow((-185 + 32 * tx) shr 4, (-185 + 32 * tz) shr 4) ?: continue
            scan.invoke(WorldScan, chunk); n++
        }
        n
    }.getOrDefault(0)

    private val scanChunk: java.lang.reflect.Method? = runCatching {
        WorldScan::class.java.getDeclaredMethod("scanChunk", net.minecraft.world.level.chunk.LevelChunk::class.java).apply { isAccessible = true }
    }.getOrNull()

    /** The name of the room Odin says the player stands in (its room-enter state), or null. */
    fun currentRoom(): String? = runCatching { WorldScan.currentRoom?.name }.getOrNull()

    /** Whether Odin believes it is in a dungeon and whether it is in the boss (it scans rooms in a dungeon; it reports room entries only outside the boss). */
    fun inDungeons(): Boolean = runCatching { DungeonUtils.inDungeons }.getOrDefault(false)
    fun inBoss(): Boolean = runCatching { DungeonUtils.inBoss }.getOrDefault(true)
}
