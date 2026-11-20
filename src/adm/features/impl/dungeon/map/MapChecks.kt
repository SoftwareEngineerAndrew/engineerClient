package com.adm.features.impl.dungeon.map

import com.adm.AdmMod.mc
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.level.material.MapColor

/** Hypixel's own check marks, copied pixel for pixel from the dungeon map. */
object MapChecks {
    private const val SIDE = 128
    private const val UNBUILT: Byte = 0
    private const val NONE: Byte = 1
    private const val MARK: Byte = 2

    private val ID: Identifier = Identifier.fromNamespaceAndPath("engineerclient", "map_checks")
    private var texture: DynamicTexture? = null
    private var dirty = false

    // Each tile's mark is copied into the texture only when a new map arrives.
    private var builtFrom: ByteArray? = null
    private val state = ByteArray(64)

    /**
     * Draws [room]'s check mark as the map shows it, on the tile it's on, a tile being [room] units across and [grid] apart;
     * false when the map has none to copy.
     */
    fun draw(g: GuiGraphicsExtractor, room: DungeonRoom, grid: Int, roomUnits: Int): Boolean {
        val px = pixels ?: return false
        if (px !== builtFrom) { builtFrom = px; state.fill(UNBUILT) }
        val size = DungeonScan.roomSize
        val gap = DungeonScan.roomGap
        for (tile in room.tiles) {
            val ox = DungeonScan.startX + tile.x * gap
            val oy = DungeonScan.startY + tile.z * gap
            if (ox < 0 || oy < 0 || ox + size > SIDE || oy + size > SIDE) continue
            val key = (tile.z and 7) * 8 + (tile.x and 7)
            if (state[key] == UNBUILT) state[key] = build(px, ox, oy, size)
            if (state[key] == NONE) continue
            val tex = texture ?: continue
            if (dirty) { tex.upload(); dirty = false }
            g.pose().pushMatrix()
            g.pose().translate(tile.x * grid.toFloat(), tile.z * grid.toFloat())
            g.pose().scale(roomUnits.toFloat() / size)
            g.blit(RenderPipelines.GUI_TEXTURED, ID, 0, 0, ox.toFloat(), oy.toFloat(), size, size, SIDE, SIDE)
            g.pose().popMatrix()
            return true
        }
        return false
    }

    private fun image(): DynamicTexture =
        texture ?: DynamicTexture({ "engineerclient map checks" }, SIDE, SIDE, true).also {
            mc.textureManager.register(ID, it)
            texture = it
        }

    private fun build(px: ByteArray, ox: Int, oy: Int, size: Int): Byte {
        val fill = px[oy * SIDE + ox]
        if (px[(oy + size / 2) * SIDE + ox + size / 2] == fill) return NONE
        val img = image().pixels ?: return NONE
        for (dy in 0 until size) for (dx in 0 until size) {
            val c = px[(oy + dy) * SIDE + ox + dx]
            img.setPixel(ox + dx, oy + dy, if (c == fill) 0 else MapColor.getColorFromPackedId(c.toInt() and 0xFF))
        }
        dirty = true
        return MARK
    }

    private val pixels get() = MapScan.pixels
}
