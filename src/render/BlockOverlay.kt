package com.engineerclient.render

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.isEtherwarpItem
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext

object BlockOverlay : Module(
    name = "Block Overlay",
    category = Category.RENDER,
    description = "Replaces the block outline with a custom box.",
    key = null,
) {
    private enum class Mode { OUTLINE, FILL, FILLED_OUTLINE }

    private val mode by SelectorSetting("Mode", Mode.FILLED_OUTLINE, desc = "How the looked at block is drawn.")
    private val fillColor by ColorSetting("Fill Color", Color(0, 255, 255, 0.2f), true, desc = "Inside of the box.").withDependency { mode != Mode.OUTLINE }
    private val outlineColor by ColorSetting("Outline Color", Color(0, 255, 255, 1f), true, desc = "Edges of the box.").withDependency { mode != Mode.FILL }
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
            val shape = level.getBlockState(pos).getShape(level, pos, CollisionContext.of(player))
            if (shape.isEmpty) return@on
            val box = shape.bounds().move(pos)
            if (mode != Mode.OUTLINE) drawFilledBox(box, fillColor, !phase)
            if (mode != Mode.FILL) drawWireFrameBox(box, outlineColor, lineWidth.toFloat(), !phase)
        }
    }
}
