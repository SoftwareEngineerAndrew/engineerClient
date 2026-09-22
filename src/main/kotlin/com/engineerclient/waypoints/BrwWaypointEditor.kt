package com.engineerclient.waypoints

import com.engineerclient.EngineerClient
import com.odtheking.odin.OdinMod
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.events.InputEvent
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.WaypointType
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.TextPromptScreen
import com.odtheking.odin.features.impl.dungeon.map.tile.DungeonRoom
import com.odtheking.odin.features.impl.render.Etherwarp
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.getBlockBounds
import com.odtheking.odin.utils.isEtherwarpItem
import com.odtheking.odin.utils.render.drawBoxes
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.render.drawText
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.toFixed
import kotlinx.coroutines.launch
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.lwjgl.glfw.GLFW

/**
 * VENDORED from Odin `dungeonwaypoints/DungeonWaypointEditor.kt` +
 * `DungeonWaypointHud.kt` (upstream 0.3.1 — see VENDORED.md), retargeted at
 * [BrwWaypoints.roomWaypoints]. One addition: when Odin's own DungeonWaypoints
 * edit mode is active at the same time, EC's editor stands down (with a one-time
 * warning) so a right-click never places into two systems at once.
 */

internal fun BrwWaypoints.renderBrwWaypoints(event: RenderEvent.Extract) {
    if (!DungeonUtils.inClear) return
    if (DungeonUtils.currentRoom == null) return
    val waypoints = roomWaypoints
    event.drawBoxes(waypoints, disableDepth)
    waypoints.forEach { waypoint ->
        val title = waypoint.title
        if (waypoint.isClicked || title == null) return@forEach
        event.drawText(
            title, waypoint.blockPos.center.add(0.0, 0.1 * titleScale, 0.0),
            titleScale, waypoint.depth
        )
    }

    brwReachPosition?.takeIf { allowEdits && !odinEditorActive() }?.let { pos ->
        event.drawStyledBox(relativeAabbAt(pos).move(pos), color.withAlpha(0.3f), style = if (filled) 0 else 1, depthCheck)
    }
}

private var warnedDualEditors = false

private fun odinEditorActive(): Boolean = DungeonWaypoints.enabled && DungeonWaypoints.allowEdits

internal fun BrwWaypoints.handleBrwEditorInput(event: InputEvent) {
    if (event.key.value != GLFW.GLFW_MOUSE_BUTTON_RIGHT || mc.screen != null) return
    cacheEtherwarpTarget()
    if (!allowEdits) return
    if (odinEditorActive()) {
        if (!warnedDualEditors) {
            warnedDualEditors = true
            EngineerClient.chat("§8[§6EC§8]§e both EC and Odin waypoint editors are on — EC is standing down. Disable one edit mode.")
        }
        return
    }
    val room = DungeonUtils.currentRoom ?: return
    val pos = brwReachPosition ?: return
    val blockPos = room.getRelativeCoords(pos)
    val visibleWaypoint = roomWaypoints.firstOrNull { it.blockPos == pos }
    val editableWaypoints = getEditableWaypoints(room)
    val editableWaypoint = editableWaypoints.firstOrNull { it.blockPos == blockPos }
    if (visibleWaypoint != null && editableWaypoint == null) {
        EngineerClient.chat("§8[§6EC§8]§e that waypoint belongs to another active pack. Switch edit packs to change it.")
        return
    }
    if (allowTextEdit && mc.player?.isCrouching == true) {
        openWaypointTitlePrompt(room, blockPos, relativeAabbAt(pos), editableWaypoints)
        return
    }
    if (editableWaypoints.removeIf { it.blockPos == blockPos }) {
        EngineerClient.logger.info("[ec] removed waypoint at $blockPos in '$editPackId'")
        syncRoomToActive(room)
        OdinMod.scope.launch { saveWaypoints() }
        return
    }
    editableWaypoints.add(createWaypoint(blockPos, relativeAabbAt(pos)))
    EngineerClient.logger.info("[ec] added waypoint at $blockPos in '$editPackId'")
    syncRoomToActive(room)
    OdinMod.scope.launch { saveWaypoints() }
}

internal val brwReachPosition: BlockPos?
    get() {
        val hitResult = mc.hitResult
        return when {
            hitResult?.type == HitResult.Type.MISS -> Etherwarp.getEtherPos(mc.player?.position(), 5.0, returnEnd = true).pos
            hitResult is BlockHitResult -> hitResult.blockPos
            else -> null
        }
    }

private fun BrwWaypoints.cacheEtherwarpTarget() {
    mc.player?.mainHandItem?.isEtherwarpItem()?.let { item ->
        Etherwarp.getEtherPos(mc.player?.position(), 56.0 + item.getInt("tuned_transmission").orElse(0))
            .takeIf { it.succeeded && it.pos != null }
            ?.also {
                lastEtherTime = System.currentTimeMillis()
                lastEtherPos = it.pos
            }
    }
}

private fun BrwWaypoints.openWaypointTitlePrompt(
    room: DungeonRoom,
    blockPos: BlockPos,
    aabb: AABB,
    editableWaypoints: MutableList<DungeonWaypoint>,
) {
    mc.setScreen(TextPromptScreen("Blood Rush Waypoint Name").setCallback { text ->
        editableWaypoints.removeIf { it.blockPos == blockPos }
        editableWaypoints.add(createWaypoint(blockPos, aabb, text))
        syncRoomToActive(room)
        mc.setScreen(null)
        OdinMod.scope.launch { saveWaypoints() }
    })
}

private fun BrwWaypoints.createWaypoint(blockPos: BlockPos, aabb: AABB, title: String? = null) = DungeonWaypoint(
    blockPos = blockPos,
    color = color.copy(),
    filled = filled,
    depth = depthCheck,
    aabb = aabb,
    title = title,
    type = WaypointType.getByInt(waypointType),
)

internal fun BrwWaypoints.relativeAabbAt(pos: BlockPos): AABB =
    if (!useBlockSize) AABB(BlockPos.ZERO).inflate((sizeX - 1.0) / 2.0, (sizeY - 1.0) / 2.0, (sizeZ - 1.0) / 2.0)
    else pos.getBlockBounds() ?: AABB(BlockPos.ZERO)

// --- editor HUD (vendored from DungeonWaypointHud.kt) ---

internal fun GuiGraphicsExtractor.drawBrwWaypointEditorHud(example: Boolean): Pair<Int, Int> {
    if (example) {
        return drawEditorHud(
            title = "§fBlood Rush Waypoints §8|§f Placing",
            text = "§fType: §5Normal§7, §r#${Colors.MINECRAFT_RED.hex()}§7, §3Outline§7, §cThrough Walls§7, §2Block Size",
            color = Colors.MINECRAFT_RED,
        )
    }

    if (!BrwWaypoints.allowEdits) return 0 to 0

    val room = DungeonUtils.currentRoom
    val pos = brwReachPosition
    if (room == null || pos == null) return 0 to 0

    val hoveredWaypoint = BrwWaypoints.roomWaypoints.firstOrNull { it.blockPos == pos }
    return drawEditorHud(
        title = "§fBlood Rush Waypoints §8|§f ${if (hoveredWaypoint == null) "Placing" else "Viewing"}",
        text = hoveredWaypoint?.describe() ?: BrwWaypoints.describeNextWaypoint(),
        color = hoveredWaypoint?.color ?: BrwWaypoints.color,
    )
}

private fun GuiGraphicsExtractor.drawEditorHud(title: String, text: String, color: Color): Pair<Int, Int> {
    val textWidth = textDim(text, 0, 10, color).first
    centeredText(mc.font, title, textWidth / 2, 0, Colors.WHITE.rgba)
    return textWidth to 19
}

private fun BrwWaypoints.describeNextWaypoint(): String = buildString {
    append("§fType: §5${WaypointType.getByInt(waypointType)?.displayName ?: "None"}")
    append("§7, §r#${color.hex()}§7")
    append(", ${if (filled) "§2Filled" else "§3Outline"}")
    append("§7, ${if (depthCheck) "§2Depth Check" else "§cThrough Walls"}")
    append("§7, ${if (useBlockSize) "§2Block Size" else "§3Size: ${sizeX.toFixed(2)}x${sizeY.toFixed(2)}x${sizeZ.toFixed(2)}"}")
}

private fun DungeonWaypoint.describe(): String = buildString {
    append("§fType: §5${type?.displayName ?: "None"}")
    append("§7, §r#${color.hex()}§7")
    title?.takeIf(String::isNotBlank)?.let { append(", §fTitle: §a$it§7") }
    append(", ${if (filled) "§2Filled" else "§3Outline"}")
    append("§7, ${if (depth) "§2Depth Check" else "§cThrough Walls"}")
    append("§7, §3Size: ${(aabb.maxX - aabb.minX).toFixed(2)}x${(aabb.maxY - aabb.minY).toFixed(2)}x${(aabb.maxZ - aabb.minZ).toFixed(2)}")
}
