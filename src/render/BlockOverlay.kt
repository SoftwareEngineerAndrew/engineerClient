package com.engineerclient.render

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.RenderExtractEvent
import com.adm.events.core.on
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.isEtherwarpItem
import com.adm.utils.render.drawFilledBox
import com.adm.utils.render.drawSegments
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

object BlockOverlay : Module(
    name = "Block Overlay",
    category = Category.RENDER,
    description = "Replaces the block outline with a custom box.",
    key = null,
) {
    private enum class Mode { OUTLINE, FILL, FILLED_OUTLINE }

    private val mode by SelectorSetting("Mode", Mode.FILLED_OUTLINE, desc = "How the looked at block is drawn.")
    private val fillColor by ColorSetting("Fill Color", Color(0, 255, 255, 0.2f), true, desc = "Inside of the box.").withDependency { mode != Mode.OUTLINE }
    private val outlineColor by ColorSetting("Outline Color", Color(0, 255, 255, 1f), true, desc = "Edges of the block's shape.").withDependency { mode != Mode.FILL }
    private val lineWidth by NumberSetting("Line Width", 2.5, 1.0..10.0, 0.1, desc = "Thickness of the edges.").withDependency { mode != Mode.FILL }
    private val phase by BooleanSetting("Phase", false, desc = "Drawn through walls.")
    private val hideWithEtherwarp by BooleanSetting("Hide With Etherwarp", false, desc = "Hidden while sneaking with an etherwarp item.")

    fun replacesOutline() = enabled

    init {
        on<RenderExtractEvent> {
            if (mc.gui.hud.isHidden) return@on
            val player = mc.player ?: return@on
            val level = mc.level ?: return@on
            val hit = mc.hitResult as? BlockHitResult ?: return@on
            if (hit.type != HitResult.Type.BLOCK) return@on
            if (hideWithEtherwarp && player.isShiftKeyDown && player.mainHandItem.isEtherwarpItem() != null) return@on
            val pos = hit.blockPos
            val state = level.getBlockState(pos)
            if (state !== cachedState || pos != cachedPos) cache(state, state.getShape(level, pos, CollisionContext.of(player)), pos)
            if (edgeCount == 0) return@on
            if (mode != Mode.OUTLINE) for (box in boxes) drawFilledBox(box, fillColor, !phase)
            if (mode != Mode.FILL) drawSegments(edges, edgeCount, outlineColor.rgba, !phase, lineWidth.toFloat())
        }
    }

    // The shape's own boxes and edges, so stairs, fences and hoppers are outlined as they are.
    private var cachedState: BlockState? = null
    private val cachedPos = BlockPos.MutableBlockPos()
    private var boxes: List<AABB> = emptyList()
    private var edges = DoubleArray(0)
    private var edgeCount = 0

    private fun cache(state: BlockState, shape: VoxelShape, pos: BlockPos) {
        cachedState = state
        cachedPos.set(pos)
        if (shape.isEmpty) { boxes = emptyList(); edgeCount = 0; return }
        boxes = shape.toAabbs().map { it.move(pos) }
        val out = ArrayList<Double>()
        val x = pos.x.toDouble(); val y = pos.y.toDouble(); val z = pos.z.toDouble()
        shape.forAllEdges { x1, y1, z1, x2, y2, z2 -> out.add(x1 + x); out.add(y1 + y); out.add(z1 + z); out.add(x2 + x); out.add(y2 + y); out.add(z2 + z) }
        edges = out.toDoubleArray()
        edgeCount = edges.size
    }
}
