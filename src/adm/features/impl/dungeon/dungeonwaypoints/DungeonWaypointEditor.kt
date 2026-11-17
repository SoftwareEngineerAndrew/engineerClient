package com.adm.features.impl.dungeon.dungeonwaypoints

import com.mojang.blaze3d.platform.InputConstants
import com.adm.AdmMod.mc
import com.adm.events.InputEvent
import com.adm.events.RenderExtractEvent
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.WaypointType
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import com.adm.features.impl.render.Etherwarp
import com.adm.utils.Color
import com.adm.utils.Color.Companion.withAlpha
import com.adm.utils.center
import com.adm.utils.devMessage
import com.adm.utils.getBlockBounds
import com.adm.utils.modMessage
import com.adm.utils.render.BoxBatch
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawBoxes
import com.adm.utils.render.drawStyledBox
import com.adm.utils.render.drawText
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

internal object WaypointView {
    private var room: DungeonRoom? = null
    private var builtRevision = -1
    private var shown: List<DungeonWaypoint> = emptyList()

    private val boxes = BoxBatch()
    private var boxesFor: List<DungeonWaypoint>? = null
    private var boxesNoDepth = false

    private var labelsFor: List<DungeonWaypoint>? = null
    private var labelScale = 0f
    private var labelCount = 0
    private var labelText = arrayOfNulls<String>(8)
    private var labelPos = arrayOfNulls<Vec3>(8)
    private var labelDepth = BooleanArray(8)

    fun visible(room: DungeonRoom): List<DungeonWaypoint> {
        val revision = WaypointStore.revision
        if (room === this.room && revision == builtRevision) return shown
        this.room = room
        builtRevision = revision
        val source = WaypointStore.roomWaypoints(room)
        // A set, so the same waypoint in two packs is drawn once.
        val set = LinkedHashSet<DungeonWaypoint>()
        source?.mapTo(set) { it.copy(blockPos = room.getRealCoords(it.blockPos), aabb = room.getRealAABB(it.aabb)) }
        room.waypoints = set
        shown = if (set.isEmpty()) emptyList() else set.toList()
        return shown
    }

    fun render(event: RenderExtractEvent) {
        val room = DungeonUtils.currentRoom ?: return
        if (!DungeonUtils.inClear) return
        val list = visible(room)
        if (list.isNotEmpty()) {
            val noDepth = DungeonWaypoints.disableDepth
            if (boxesFor !== list || boxesNoDepth != noDepth) fillBoxes(list, noDepth)
            event.drawBoxes(boxes)

            val scale = DungeonWaypoints.titleScale
            if (labelsFor !== list || labelScale != scale) fillLabels(list, scale)
            for (i in 0 until labelCount) event.drawText(labelText[i]!!, labelPos[i]!!, scale, labelDepth[i])
        }
        if (DungeonWaypoints.allowEdits) WaypointEditor.drawCursor(event)
    }

    private fun fillBoxes(list: List<DungeonWaypoint>, noDepth: Boolean) {
        boxesFor = list
        boxesNoDepth = noDepth
        boxes.clear()
        for (w in list) {
            if (w.isClicked || w.color.isTransparent) continue
            val box = w.aabb.move(w.blockPos)
            val depth = w.depth && !noDepth
            if (w.filled) boxes.filled(box, w.color, depth = depth) else boxes.outline(box, w.color, depth = depth)
        }
    }

    private fun fillLabels(list: List<DungeonWaypoint>, scale: Float) {
        labelsFor = list
        labelScale = scale
        if (labelText.size < list.size) {
            labelText = arrayOfNulls(list.size)
            labelPos = arrayOfNulls(list.size)
            labelDepth = BooleanArray(list.size)
        }
        var n = 0
        for (w in list) {
            val title = w.title ?: continue
            if (w.isClicked) continue
            labelText[n] = title
            labelPos[n] = w.blockPos.center.add(0.0, 0.1 * scale, 0.0)
            labelDepth[n] = w.depth
            n++
        }
        labelCount = n
    }
}

internal object WaypointEditor {
    private var cursorBase: Color? = null
    private var cursorRgba = 0
    private var cursorColor: Color? = null

    fun drawCursor(event: RenderExtractEvent) {
        val pos = target() ?: return
        val base = DungeonWaypoints.color
        if (base !== cursorBase || base.rgba != cursorRgba) {
            cursorBase = base
            cursorRgba = base.rgba
            cursorColor = base.withAlpha(0.3f)
        }
        val style = if (DungeonWaypoints.filled) BoxStyle.FILLED else BoxStyle.OUTLINE
        event.drawStyledBox(boxAt(pos).move(pos), cursorColor!!, style, DungeonWaypoints.depthCheck)
    }

    fun onInput(event: InputEvent) {
        val key = event.key
        if (key.value != InputConstants.MOUSE_BUTTON_RIGHT || key.type != InputConstants.Type.MOUSE || mc.gui.screen() != null) return
        SecretWaypoints.aimEtherwarp()
        if (!DungeonWaypoints.allowEdits) return
        val room = DungeonUtils.currentRoom ?: return
        val pos = target() ?: return
        val rel = room.getRelativeCoords(pos)
        val shownHere = WaypointView.visible(room).any { it.blockPos == pos }
        val own = WaypointStore.editable(room) ?: return
        if (shownHere && own.none { it.blockPos == rel }) {
            modMessage("§eThat waypoint belongs to another active pack. Switch edit packs to change it.")
            return
        }
        if (DungeonWaypoints.allowTextEdit && mc.player?.isCrouching == true) {
            val box = room.getRelativeAABB(boxAt(pos))
            mc.setScreenAndShow(TextPromptScreen("Waypoint Name") { text ->
                own.removeIf { it.blockPos == rel }
                own.add(newWaypoint(rel, box, text))
                devMessage("Added waypoint with $text at $rel")
                WaypointStore.roomEdited(room)
                mc.gui.setScreen(null)
            })
            return
        }
        if (own.removeIf { it.blockPos == rel }) {
            devMessage("Removed waypoint at $rel")
        } else {
            own.add(newWaypoint(rel, room.getRelativeAABB(boxAt(pos))))
            devMessage("Added waypoint at $rel")
        }
        WaypointStore.roomEdited(room)
    }

    fun target(): BlockPos? {
        val hit = mc.hitResult
        return when {
            hit?.type == HitResult.Type.MISS -> Etherwarp.getEtherPos(mc.player?.position(), 5.0, returnEnd = true).pos
            hit is BlockHitResult -> hit.blockPos
            else -> null
        }
    }

    private fun newWaypoint(rel: BlockPos, box: AABB, title: String? = null) = DungeonWaypoint(
        rel, DungeonWaypoints.color.copy(), DungeonWaypoints.filled, DungeonWaypoints.depthCheck, box, title,
        DungeonWaypoints.waypointType.takeIf { it != WaypointType.NONE },
    )

    private fun boxAt(pos: BlockPos): AABB = with(DungeonWaypoints) {
        if (useBlockSize) pos.getBlockBounds() ?: AABB(BlockPos.ZERO)
        else AABB(BlockPos.ZERO).inflate((sizeX - 1.0) / 2.0, (sizeY - 1.0) / 2.0, (sizeZ - 1.0) / 2.0)
    }
}
