package com.adm.features.impl.dungeon.dungeonwaypoints

import com.adm.AdmMod.mc
import com.adm.clickgui.settings.impl.label
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.WaypointType
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.render.text
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.toFixed
import net.minecraft.client.gui.GuiGraphicsExtractor

internal object EditorHud {
    private const val PLACING = "§fEditing Waypoints §8|§f Placing"
    private const val VIEWING = "§fEditing Waypoints §8|§f Viewing"

    private val exampleText by lazy {
        "§fType: §5Normal§7, §r#${Colors.MINECRAFT_RED.hex()}§7, §3Outline§7, §cThrough Walls§7, §2Block Size"
    }

    private var viewed: DungeonWaypoint? = null
    private var viewedText = ""

    private var nextType: WaypointType? = null
    private var nextRgba = 0
    private var nextFilled = false
    private var nextDepth = false
    private var nextBlockSize = false
    private var nextX = 0.0
    private var nextY = 0.0
    private var nextZ = 0.0
    private var nextText = ""

    private var widthOf: String? = null
    private var width = 0

    fun draw(graphics: GuiGraphicsExtractor, example: Boolean): Pair<Int, Int> {
        if (example) return graphics.panel(PLACING, exampleText, Colors.MINECRAFT_RED)
        if (!DungeonWaypoints.allowEdits) return 0 to 0
        val room = DungeonUtils.currentRoom ?: return 0 to 0
        val pos = WaypointEditor.target() ?: return 0 to 0
        val hovered = WaypointView.visible(room).firstOrNull { it.blockPos == pos }
        return if (hovered != null) graphics.panel(VIEWING, describe(hovered), hovered.color)
        else graphics.panel(PLACING, describeNext(), DungeonWaypoints.color)
    }

    private fun GuiGraphicsExtractor.panel(title: String, body: String, color: Color): Pair<Int, Int> {
        if (body !== widthOf) {
            widthOf = body
            width = mc.font.width(body)
        }
        text(body, 0, 10, color)
        centeredText(mc.font, title, width / 2, 0, Colors.WHITE.rgba)
        return width to 19
    }

    private fun describe(w: DungeonWaypoint): String {
        if (w === viewed) return viewedText
        viewed = w
        val box = w.aabb
        viewedText = buildString {
            append("§fType: §5").append(w.type?.label ?: "None")
            append("§7, §r#").append(w.color.hex()).append("§7")
            w.title?.takeIf(String::isNotBlank)?.let { append(", §fTitle: §a").append(it).append("§7") }
            append(if (w.filled) ", §2Filled" else ", §3Outline")
            append(if (w.depth) "§7, §2Depth Check" else "§7, §cThrough Walls")
            append("§7, §3Size: ").append((box.maxX - box.minX).toFixed(2)).append('x')
                .append((box.maxY - box.minY).toFixed(2)).append('x').append((box.maxZ - box.minZ).toFixed(2))
        }
        return viewedText
    }

    private fun describeNext(): String = with(DungeonWaypoints) {
        val rgba = color.rgba
        if (waypointType === nextType && rgba == nextRgba && filled == nextFilled && depthCheck == nextDepth &&
            useBlockSize == nextBlockSize && sizeX == nextX && sizeY == nextY && sizeZ == nextZ && nextText.isNotEmpty()
        ) return nextText
        nextType = waypointType
        nextRgba = rgba
        nextFilled = filled
        nextDepth = depthCheck
        nextBlockSize = useBlockSize
        nextX = sizeX
        nextY = sizeY
        nextZ = sizeZ
        nextText = buildString {
            append("§fType: §5").append(waypointType.label)
            append("§7, §r#").append(color.hex()).append("§7")
            append(if (filled) ", §2Filled" else ", §3Outline")
            append(if (depthCheck) "§7, §2Depth Check" else "§7, §cThrough Walls")
            if (useBlockSize) append("§7, §2Block Size")
            else append("§7, §3Size: ").append(sizeX.toFixed(2)).append('x').append(sizeY.toFixed(2)).append('x').append(sizeZ.toFixed(2))
        }
        nextText
    }
}
