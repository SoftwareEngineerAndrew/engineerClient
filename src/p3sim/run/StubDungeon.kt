package com.engineerclient.p3sim.run

import com.engineerclient.p3sim.pack.DungeonFrame
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.Tile
import com.engineerclient.p3sim.pack.layouts.DoorKind
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * A made-up dungeon for the engine's spikes and for trying the Run container without the data packs: 36 empty, roofed stone-brick rooms
 * (one per tile, 31 x 31, floor y 68, ceiling y 86) joined by open doorways, a few sea lanterns on each ceiling so the light engine has
 * emitters to deal with. It stands in for the layout module's region in `/ads dev cost` and `/ads dev stubrun`. It is not a recorded room
 * and carries no data.
 */
object StubDungeon {
    const val NAME = "stub-dungeon"
    const val FLOOR_Y = 68
    const val CEILING_Y = 86

    /** The plan: a 6 x 6 snake of 1 x 1 rooms, the entrance at tile (0, 0) and the blood room at the far end of the snake. */
    fun plan(seed: Long): DungeonPlan {
        val order = ArrayList<Tile>()
        for (z in 0 until DungeonFrame.TILES) { val xs = (0 until DungeonFrame.TILES).toList(); for (x in if (z % 2 == 0) xs else xs.reversed()) order += Tile(x, z) }
        val rooms = order.mapIndexed { i, t ->
            val type = when (i) { 0 -> RoomType.ENTRANCE; order.size - 1 -> RoomType.BLOOD; order.size - 2 -> RoomType.FAIRY; else -> RoomType.NORMAL }
            PlacedRoom(i, "Stub Room ${i + 1}", "Stub", "stub", null, type, "1x1", listOf(t), Rot.SOUTH, null, 0, null, null, null, null, null)
        }
        val doors = ArrayList<PlacedDoor>()
        for (z in 0 until DungeonFrame.TILES) for (x in 0 until DungeonFrame.TILES) {
            if (x < DungeonFrame.TILES - 1) doors += door(doors.size, 'E', Tile(x, z), Tile(x + 1, z), order, rooms)
            if (z < DungeonFrame.TILES - 1) doors += door(doors.size, 'S', Tile(x, z), Tile(x, z + 1), order, rooms)
        }
        return DungeonPlan("stub", "F7", seed, rooms, doors, rooms.indices.toList(), emptyList(), emptyList())
    }

    enum class Kind { AIR, BRICK, LANTERN, BEDROCK }

    const val BEDROCK_Y = 66

    /** What the stub has at a world block: pure, so the shape is tested without a world. Outside the dungeon box it is air. */
    fun kindAt(x: Int, y: Int, z: Int): Kind {
        if (x < DungeonFrame.ORIGIN || x > DungeonFrame.DUNGEON_MAX || z < DungeonFrame.ORIGIN || z > DungeonFrame.DUNGEON_MAX) return Kind.AIR
        if (y < BEDROCK_Y || y > CEILING_Y) return Kind.AIR
        if (y < FLOOR_Y) return Kind.BEDROCK
        val tx = Math.floorDiv(x - DungeonFrame.ORIGIN, DungeonFrame.PITCH); val tz = Math.floorDiv(z - DungeonFrame.ORIGIN, DungeonFrame.PITCH)
        val lx = Math.floorMod(x - DungeonFrame.ORIGIN, DungeonFrame.PITCH)   // 0..30 inside a room, 31 the gap column
        val lz = Math.floorMod(z - DungeonFrame.ORIGIN, DungeonFrame.PITCH)
        if (y == FLOOR_Y || y == CEILING_Y) return Kind.BRICK
        // A sea lantern under the ceiling every 8 blocks.
        if (y == CEILING_Y - 1 && lx % 8 == 4 && lz % 8 == 4 && lx < 30 && lz < 30) return Kind.LANTERN
        // Walls three thick on each tile edge and the gap column; a doorway (3 wide, 4 high) in the middle where the snake joins two rooms.
        val wallX = lx >= 30 || lx == 0
        val wallZ = lz >= 30 || lz == 0
        if (!wallX && !wallZ) return Kind.AIR
        val openX = wallX && !wallZ && Math.abs(lz - 15) <= 1 && joined(tx, tz, if (lx == 0) tx - 1 else tx + 1, tz)
        val openZ = wallZ && !wallX && Math.abs(lx - 15) <= 1 && joined(tx, tz, tx, if (lz == 0) tz - 1 else tz + 1)
        return if (y <= DungeonFrame.DOOR_Y_MAX && (openX || openZ)) Kind.AIR else Kind.BRICK
    }

    /** Whether the snake joins tile (ax, az) to (bx, bz): they follow each other in the plan's order. */
    private fun joined(ax: Int, az: Int, bx: Int, bz: Int): Boolean {
        if (bx !in 0 until DungeonFrame.TILES || bz !in 0 until DungeonFrame.TILES) return false
        return Math.abs(snakeIndex(ax, az) - snakeIndex(bx, bz)) == 1
    }

    /** The place of tile (x, z) along the snake: row by row, every other row backwards. */
    internal fun snakeIndex(x: Int, z: Int): Int = z * DungeonFrame.TILES + (if (z % 2 == 0) x else DungeonFrame.TILES - 1 - x)

    private fun door(i: Int, axis: Char, a: Tile, b: Tile, order: List<Tile>, rooms: List<PlacedRoom>): PlacedDoor {
        val ra = order.indexOf(a); val rb = order.indexOf(b)
        // Only neighbours along the snake are doorways; the rest are walls.
        val kind = if (Math.abs(ra - rb) == 1) (if (minOf(ra, rb) == 0) DoorKind.ENTRANCE else DoorKind.NORMAL) else DoorKind.NONE
        return PlacedDoor(i, axis, a, b, ra, rb, kind, null)
    }

    /** The region that writes the stub rooms while the world's chunks generate. Pure block writes, safe on the generator's workers. */
    fun region(): WorldRegion = object : WorldRegion {
        override val name = NAME
        override val minX = DungeonFrame.ORIGIN
        override val minZ = DungeonFrame.ORIGIN
        override val maxX = DungeonFrame.DUNGEON_MAX
        override val maxZ = DungeonFrame.DUNGEON_MAX

        // Looked up when the first chunk is filled (the block registry is not open before the game starts).
        private val brick: BlockState by lazy { Blocks.STONE_BRICKS.defaultBlockState() }
        private val lantern: BlockState by lazy { Blocks.SEA_LANTERN.defaultBlockState() }
        private val bedrock: BlockState by lazy { Blocks.BEDROCK.defaultBlockState() }

        override fun fill(chunk: ChunkAccess) {
            val cx = chunk.pos.minBlockX; val cz = chunk.pos.minBlockZ
            for (dz in 0 until 16) for (dx in 0 until 16) {
                val x = cx + dx; val z = cz + dz
                if (x < minX || x > maxX || z < minZ || z > maxZ) continue
                for (y in BEDROCK_Y..CEILING_Y) {
                    val s = when (kindAt(x, y, z)) { Kind.AIR -> continue; Kind.BRICK -> brick; Kind.LANTERN -> lantern; Kind.BEDROCK -> bedrock }
                    put(chunk, x, y, z, s)
                }
            }
        }

        private fun put(chunk: ChunkAccess, x: Int, y: Int, z: Int, s: BlockState) {
            val si = chunk.getSectionIndex(y)
            if (si < 0 || si >= chunk.sections.size) return
            chunk.getSection(si).setBlockState(x and 15, y and 15, z and 15, s, false)
        }

        override fun resync(level: ServerLevel): Int = 0
    }
}
