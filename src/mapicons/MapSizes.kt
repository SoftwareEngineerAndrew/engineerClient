package com.engineerclient.mapicons

import com.engineerclient.lcm.LcmStyle
import com.engineerclient.EngineerClient
import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.features.impl.dungeon.map.DungeonScan
import com.adm.features.impl.dungeon.map.buildExampleDoors
import com.adm.features.impl.dungeon.map.buildExampleRooms
import com.adm.features.impl.dungeon.map.renderMap
import com.adm.features.impl.dungeon.map.renderPlayers
import com.adm.utils.render.hollowFill
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.features.impl.dungeon.map.DungeonMap
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Room Size and Door Width on ADM's Dungeon Map. ADM draws on a 20 px grid: each room 16 px with a 4 px gap, each
 * door 8 px across that gap; it draws with these instead. The grid stays
 * 20, so a bigger room is a smaller gap and the map keeps its size; doors always span the whole gap. LCM Style draws
 * dtMap's own sizes, so it keeps ADM's.
 */
object MapSizes {
    const val TILE = 20
    const val DEFAULT_ROOM = 16
    const val DEFAULT_DOOR = 8

    private val roomSize = NumberSetting("Room Size", DEFAULT_ROOM, 8..20, 1,
        desc = "How big each room is drawn on the map, out of the 20 each tile takes (ADM's is 16); the rest is the gap the doors sit in. Not in LCM Style. Added by engineerClient.")
    private val doorWidth = NumberSetting("Door Width", DEFAULT_DOOR, 2..20, 1,
        desc = "How wide each door is drawn across the gap between rooms (ADM's is 8), at most the room size. Not in LCM Style. Added by engineerClient.")

    private val customPadding = BooleanSetting("Custom Map Padding", false,
        desc = "Fits the map's background to the dungeon with Map Padding on every side, instead of ADM's square the size of a vanilla map (where the gap depends on the floor). LCM Style uses it too, instead of its 5. Added by engineerClient.")
    private val padding = NumberSetting("Map Padding", 5, 0..30, 1,
        desc = "The gap between the outermost rooms and the edge of the map's background.").withDependency { customPadding.value }

    fun install() {
        for (s in listOf(roomSize, doorWidth, customPadding, padding)) DungeonMap.registerSetting(s)
    }

    /** Map Padding while Custom Map Padding is on, else null (ADM's own layout, or LCM Style's 5). */
    @JvmStatic
    fun padding(): Int? = if (customPadding.value) padding.value else null

    private val exampleRooms by lazy { buildExampleRooms() }
    private val exampleDoors by lazy { buildExampleDoors() }
    private var failed = false

    /**
     * ADM's map HUD with Custom Map Padding: the same map at ADM's scale, but
     * the background fitted to the floor's rooms plus the padding. Its size, or null to leave the frame to ADM.
     */
    @JvmStatic
    fun renderHud(g: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int>? {
        val pad = padding() ?: return null
        if (!example && (!DungeonUtils.inDungeons || (LcmStyle.disableInBoss() && DungeonUtils.inBoss))) return 0 to 0
        val (cols, rows) = LcmStyle.mapSize(example)
        // ADM draws a live map at the map item's scale (a room there is roomSize pixels to its 16); the example at 1
        val scale = if (example) 1f else DungeonScan.roomSize / 16f
        val room = room()
        val width = (((cols - 1) * TILE + room) * scale + pad * 2).toInt()
        val height = (((rows - 1) * TILE + room) * scale + pad * 2).toInt()
        g.pose().pushMatrix()
        try {
            g.fill(0, 0, width, height, DungeonMap.backgroundColor.rgba)
            g.hollowFill(0, 0, width, height, 1, DungeonMap.backgroundOutline)
            g.pose().translate(pad.toFloat(), pad.toFloat())
            if (example) g.renderMap(exampleRooms, exampleDoors, emptyList())
            else {
                g.pose().scale(scale)
                g.renderMap(DungeonScan.rooms, DungeonScan.doors.values, DungeonScan.pathHints)
                if (!DungeonUtils.inBoss) g.renderPlayers()
            }
        } catch (t: Throwable) {
            // A map packet changed the rooms mid-draw: the next frame draws them whole, so ADM must not draw it a second time.
            if (t is ConcurrentModificationException) return width to height
            if (!failed) {
                failed = true
                EngineerClient.logger.error("[ec] Custom Map Padding failed, ADM draws the map", t)
            }
            return null
        } finally {
            g.pose().popMatrix()
        }
        return width to height
    }

    /** The room size ADM's look draws now (LCM Style: ADM's). */
    @JvmStatic
    fun room(): Int = if (LcmStyle.active()) DEFAULT_ROOM else roomSize.value

    /** The door width ADM's look draws now, never wider than a room. */
    @JvmStatic
    fun door(): Int = if (LcmStyle.active()) DEFAULT_DOOR else doorWidth.value.coerceAtMost(room())

    /** How far a room's centre moves from where ADM centres it (on a 16 room), for its icons and text. */
    @JvmStatic
    fun shift(): Float = (room() - DEFAULT_ROOM) / 2f
}
