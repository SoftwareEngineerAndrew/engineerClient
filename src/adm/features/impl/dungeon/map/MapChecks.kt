package com.adm.features.impl.dungeon.map

import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.level.material.MapColor

/** Hypixel's own check marks, copied pixel for pixel from the dungeon map. */
object MapChecks {
    private const val SIDE = 128

    // Each tile's mark as row runs (x, y, length, colour), rebuilt only when a new map arrives.
    private var spansOf: ByteArray? = null
    private val spans = arrayOfNulls<IntArray>(64)
    private val NONE = IntArray(0)

    /**
     * Draws [room]'s check mark as the map shows it, on the tile it's on, a tile being [room] units across and [grid] apart;
     * false when the map has none to copy.
     */
    fun draw(g: GuiGraphicsExtractor, room: DungeonRoom, grid: Int, roomUnits: Int): Boolean {
        val px = pixels ?: return false
        if (px !== spansOf) { spansOf = px; spans.fill(null) }
        val size = DungeonScan.roomSize
        val gap = DungeonScan.roomGap
        for (tile in room.tiles) {
            val ox = DungeonScan.startX + tile.x * gap
            val oy = DungeonScan.startY + tile.z * gap
            if (ox < 0 || oy < 0 || ox + size > SIDE || oy + size > SIDE) continue
            val key = (tile.z and 7) * 8 + (tile.x and 7)
            val runs = spans[key] ?: build(px, ox, oy, size).also { spans[key] = it }
            if (runs === NONE) continue
            g.pose().pushMatrix()
            g.pose().translate(tile.x * grid.toFloat(), tile.z * grid.toFloat())
            g.pose().scale(roomUnits.toFloat() / size)
            var i = 0
            while (i < runs.size) {
                g.fill(runs[i], runs[i + 1], runs[i] + runs[i + 2], runs[i + 1] + 1, runs[i + 3])
                i += 4
            }
            g.pose().popMatrix()
            return true
        }
        return false
    }

    private fun build(px: ByteArray, ox: Int, oy: Int, size: Int): IntArray {
        val fill = px[oy * SIDE + ox]
        if (px[(oy + size / 2) * SIDE + ox + size / 2] == fill) return NONE
        val out = ArrayList<Int>()
        for (dy in 0 until size) {
            var dx = 0
            while (dx < size) {
                val c = px[(oy + dy) * SIDE + ox + dx]
                var end = dx + 1
                while (end < size && px[(oy + dy) * SIDE + ox + end] == c) end++
                if (c != fill) { out += dx; out += dy; out += end - dx; out += MapColor.getColorFromPackedId(c.toInt() and 0xFF) }
                dx = end
            }
        }
        return out.toIntArray()
    }

    private val pixels get() = MapScan.pixels
}
