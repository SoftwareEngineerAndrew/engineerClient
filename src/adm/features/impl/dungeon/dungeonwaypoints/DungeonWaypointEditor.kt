package com.adm.features.impl.dungeon.dungeonwaypoints

import com.mojang.blaze3d.platform.InputConstants
import com.adm.AdmMod
import com.adm.AdmMod.mc
import com.adm.events.InputEvent
import com.adm.events.RenderExtractEvent
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.WaypointType
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.render.Etherwarp
import com.adm.features.impl.render.Etherwarp.getEtherwarpDistance
import com.adm.utils.*
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawBoxes
import com.adm.utils.render.drawStyledBox
import com.adm.utils.render.drawText
import com.adm.utils.skyblock.dungeon.DungeonUtils
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

fun DungeonWaypoints.renderWaypoints(event: RenderExtractEvent) {
    if (!DungeonUtils.inClear) return
    val room = DungeonUtils.currentRoom ?: return
    event.drawBoxes(room.waypoints, disableDepth)
    room.waypoints.forEach { waypoint ->
        if (waypoint.isClicked || waypoint.title == null) return@forEach
        event.drawText(
            waypoint.title, waypoint.blockPos.center.add(0.0, 0.1 * titleScale, 0.0),
            titleScale, waypoint.depth
        )
    }

    reachPosition?.takeIf { allowEdits }?.let { pos ->
        event.drawStyledBox(relativeAabbAt(pos).move(pos), color.withAlpha(0.3f), style = if (filled) BoxStyle.FILLED else BoxStyle.OUTLINE, depthCheck)
    }
}

private fun DungeonWaypoints.cacheEtherwarpTarget() {
    mc.player?.mainHandItem?.isEtherwarpItem()?.let { item ->
        Etherwarp.getEtherPos(mc.player?.position(), getEtherwarpDistance(item))
            .takeIf { it.succeeded && it.pos != null }
            ?.also {
                lastEtherTime = System.currentTimeMillis()
                lastEtherPos = it.pos
            }
    }
}

fun DungeonWaypoints.handleEditorInput(event: InputEvent) {
    if (event.key.type != InputConstants.Type.MOUSE || event.key.value != InputConstants.MOUSE_BUTTON_RIGHT || mc.gui.screen() != null) return
    cacheEtherwarpTarget()
    if (!allowEdits) return
    val room = DungeonUtils.currentRoom ?: return
    val pos = reachPosition ?: return
    val blockPos = room.getRelativeCoords(pos)
    val visibleWaypoint = room.waypoints.firstOrNull { it.blockPos == pos }
    val editableWaypoints = getEditableWaypoints(room)
    val editableWaypoint = editableWaypoints.firstOrNull { it.blockPos == blockPos }
    if (visibleWaypoint != null && editableWaypoint == null) {
        modMessage("§eThat waypoint belongs to another active pack. Switch edit packs to change it.")
        return
    }
    if (allowTextEdit && mc.player?.isCrouching == true) {
        openWaypointTitlePrompt(room, blockPos, room.getRelativeAABB(relativeAabbAt(pos)), editableWaypoints)
        return
    }
    if (editableWaypoints.removeIf { it.blockPos == blockPos }) {
        devMessage("Removed waypoint at $blockPos")
        syncRoomToActive(room)
        AdmMod.scope.launch { saveWaypoints() }
        return
    }
    editableWaypoints.add(createWaypoint(blockPos, room.getRelativeAABB(relativeAabbAt(pos))))
    devMessage("Added waypoint at $blockPos")
    syncRoomToActive(room)
    AdmMod.scope.launch { saveWaypoints() }
}

val reachPosition: BlockPos?
    get() {
        val hitResult = mc.hitResult
        return when {
            hitResult?.type == HitResult.Type.MISS -> Etherwarp.getEtherPos(mc.player?.position(), 5.0, returnEnd = true).pos
            hitResult is BlockHitResult -> hitResult.blockPos
            else -> null
        }
    }

private fun DungeonWaypoints.openWaypointTitlePrompt(
    room: DungeonRoom,
    blockPos: BlockPos,
    aabb: AABB,
    editableWaypoints: MutableList<DungeonWaypoint>,
) {
    mc.setScreenAndShow(TextPromptScreen("Waypoint Name").setCallback { text ->
        editableWaypoints.removeIf { it.blockPos == blockPos }
        editableWaypoints.add(createWaypoint(blockPos, aabb, text))
        devMessage("Added waypoint with $text at $blockPos")
        syncRoomToActive(room)
        mc.gui.setScreen(null)
        AdmMod.scope.launch { saveWaypoints() }
    })
}

private fun DungeonWaypoints.createWaypoint(blockPos: BlockPos, aabb: AABB, title: String? = null) = DungeonWaypoint(
    blockPos = blockPos,
    color = color.copy(),
    filled = filled,
    depth = depthCheck,
    aabb = aabb,
    title = title,
    type = waypointType.takeIf { it != WaypointType.NONE },
)

fun DungeonWaypoints.relativeAabbAt(pos: BlockPos): AABB =
    if (!useBlockSize) AABB(BlockPos.ZERO).inflate((sizeX - 1.0) / 2.0, (sizeY - 1.0) / 2.0, (sizeZ - 1.0) / 2.0)
    else pos.getBlockBounds() ?: AABB(BlockPos.ZERO)