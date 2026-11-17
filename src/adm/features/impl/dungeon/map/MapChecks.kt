package com.adm.features.impl.dungeon.map

import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.level.material.MapColor

/** Hypixel's own check marks, copied pixel for pixel from the dungeon map. */
object MapChecks {
    private const val SIDE = 128

    /**
     * Draws [room]'s check mark as the map shows it, on the tile it's on, a tile being [room] units across and [grid] apart;
     * false when the map has none to copy.
     */
    fun draw(g: GuiGraphicsExtractor, room: DungeonRoom, grid: Int, roomUnits: Int): Boolean {
        val px = pixels ?: return false
        val size = DungeonScan.roomSize
        val gap = DungeonScan.roomGap
        for (tile in room.tiles) {
            val ox = DungeonScan.startX + tile.x * gap
            val oy = DungeonScan.startY + tile.z * gap
            if (ox < 0 || oy < 0 || ox + size > SIDE || oy + size > SIDE) continue
            val fill = px[oy * SIDE + ox]
            if (px[(oy + size / 2) * SIDE + ox + size / 2] == fill) continue
            g.pose().pushMatrix()
            g.pose().translate(tile.x * grid.toFloat(), tile.z * grid.toFloat())
            g.pose().scale(roomUnits.toFloat() / size)
            for (dy in 0 until size) for (dx in 0 until size) {
                val c = px[(oy + dy) * SIDE + ox + dx]
                if (c != fill) g.fill(dx, dy, dx + 1, dy + 1, MapColor.getColorFromPackedId(c.toInt() and 0xFF))
            }
            g.pose().popMatrix()
            return true
        }
        return false
    }

    private val pixels get() = MapScan.pixels
}
