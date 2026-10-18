package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.p3sim.pack.Pos
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.run.DungeonPlan

/** What a group of cells is: a crypt tomb (counts as a crypt when destroyed) or a Superboom wall (a secret passage). */
enum class GroupKind { CRYPT, WALL }

/**
 * One connected set of blocks in the world frame: a crypt tomb or a Superboom wall. [id] is stable for a given plan (room, kind,
 * index in the room's list), so a savepoint can name it.
 */
class CellGroup(val id: String, val room: Int, val kind: GroupKind, val cells: List<Pos>) {
    val minX = cells.minOf { it.x }; val maxX = cells.maxOf { it.x }
    val minY = cells.minOf { it.y }; val maxY = cells.maxOf { it.y }
    val minZ = cells.minOf { it.z }; val maxZ = cells.maxOf { it.z }

    /** Whether any cell is within [r] blocks of the point (the Superboom's blast sphere). */
    fun within(cx: Double, cy: Double, cz: Double, r: Double): Boolean {
        if (cx < minX - r || cx > maxX + 1 + r || cy < minY - r || cy > maxY + 1 + r || cz < minZ - r || cz > maxZ + 1 + r) return false
        val r2 = r * r
        for (c in cells) {
            val dx = c.x + 0.5 - cx; val dy = c.y + 0.5 - cy; val dz = c.z + 0.5 - cz
            if (dx * dx + dy * dy + dz * dz <= r2) return true
        }
        return false
    }
}

/**
 * Every crypt tomb and Superboom wall of the dungeon, placed in the world, with what is destroyed so far. A group is destroyed whole
 * (the measured behaviour of a wall: one blast clears its connected component; a tomb is treated the same). Pure.
 */
class CellGroups(val groups: List<CellGroup>) {
    private val byCell: Map<Long, CellGroup> = HashMap<Long, CellGroup>().also { m -> for (g in groups) for (c in g.cells) m[key(c.x, c.y, c.z)] = g }
    private val byId: Map<String, CellGroup> = groups.associateBy { it.id }
    private val gone = LinkedHashSet<String>()

    /** The standing group a block belongs to, or null (not part of one, or already destroyed). */
    fun standingAt(x: Int, y: Int, z: Int): CellGroup? = byCell[key(x, y, z)]?.takeIf { it.id !in gone }

    /** The groups (standing ones) a blast of radius [r] at the point touches. */
    fun hitBy(cx: Double, cy: Double, cz: Double, r: Double): List<CellGroup> = groups.filter { it.id !in gone && it.within(cx, cy, cz, r) }

    fun group(id: String): CellGroup? = byId[id]
    fun isDestroyed(g: CellGroup) = g.id in gone
    val destroyedIds: Set<String> get() = gone

    /** Marks a group destroyed; false if it already was. */
    fun destroy(g: CellGroup): Boolean = gone.add(g.id)

    fun setDestroyed(ids: Collection<String>) { gone.clear(); gone.addAll(ids.filter { it in byId }) }

    fun ofRoom(room: Int, kind: GroupKind? = null): List<CellGroup> = groups.filter { it.room == room && (kind == null || it.kind == kind) }

    companion object {
        fun key(x: Int, y: Int, z: Int): Long = ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

        /** Resolves the rooms' `crypts` and `superboomWalls` groups through each room's placement. A room without a placement has none. */
        fun build(plan: DungeonPlan): CellGroups {
            val out = ArrayList<CellGroup>()
            for (r in plan.rooms) {
                val design = r.design ?: continue
                val placement = r.placement ?: continue
                design.crypts.forEachIndexed { i, g -> world(placement, g).takeIf { it.isNotEmpty() }?.let { out += CellGroup("${r.index}:c$i", r.index, GroupKind.CRYPT, it) } }
                design.superboomWalls.forEachIndexed { i, g -> world(placement, g).takeIf { it.isNotEmpty() }?.let { out += CellGroup("${r.index}:w$i", r.index, GroupKind.WALL, it) } }
            }
            return CellGroups(out)
        }

        private fun world(p: Placement, cells: List<Pos>): List<Pos> = cells.map { c -> p.toWorld(c.x, c.y, c.z).let { Pos(it.x, it.y, it.z) } }
    }
}
