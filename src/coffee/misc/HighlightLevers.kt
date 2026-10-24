package com.coffeeclient.misc

import com.coffeeclient.CoffeeClient
import com.coffeeclient.CoffeeClient.mc
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.Color.Companion.withAlpha
import com.odtheking.odin.utils.render.drawFilledBox
import com.odtheking.odin.utils.render.drawWireFrameBox
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.VoxelShape

/** A box around each P3 lever's hitbox (each lever's own shape: it depends on how the lever faces), in F7/M7 boss and the P3 Sim Beta. */
object HighlightLevers : Module(
    name = "Highlight Levers",
    category = Category.custom("Coffee Client", 860, 10),
    description = "A blue box around each P3 lever's hitbox in F7/M7 boss (and ADS), so you can see where to click.",
) {
    private val leverColor by ColorSetting("Lever Color", Color(40, 120, 255, 1f), true, desc = "")
    private val leverFill by BooleanSetting("Lever Fill", false, desc = "Fills the box too, faintly.")
    private val leverWalls by BooleanSetting("Levers Through Walls", true, desc = "Drawn over blocks in the way.")
    private val deviceLevers by BooleanSetting("Lights Device Levers", true, desc = "The 20 levers of S2's lights device too.")

    /** The 8 terminal-section levers (S1-S4), as the P3 Sim places them. */
    private val SECTION_LEVERS = listOf(
        BlockPos(106, 124, 113), BlockPos(94, 124, 113), BlockPos(27, 124, 127), BlockPos(23, 132, 138),
        BlockPos(2, 122, 55), BlockPos(14, 122, 55), BlockPos(84, 121, 34), BlockPos(86, 128, 46),
    )
    /** The lights device's 5x4 levers (x 58-62, y 133-136, z 142). */
    private val DEVICE_LEVERS = (58..62).flatMap { x -> (133..136).map { y -> BlockPos(x, y, 142) } }
    /** Section levers first, so without [deviceLevers] the loop stops after them. */
    private val ALL_LEVERS = SECTION_LEVERS + DEVICE_LEVERS

    /**
     * Each lever's box and the shape it was made from. Shapes are shared and never change, so a box is only made again
     * when its lever's shape does (placed facing another way), not every frame.
     */
    private val shapes = arrayOfNulls<VoxelShape>(ALL_LEVERS.size)
    private val boxes = arrayOfNulls<AABB>(ALL_LEVERS.size)

    /** The last fill and the [leverColor] value it was made from: by value, as the setting's colour is edited in place. */
    private var fillColor: Color? = null
    private var fillRgba = 0
    private var fillAlpha = 0f

    init {
        on<RenderExtractEvent> { CoffeeClient.safely("highlight levers") { draw() } }
    }

    private fun RenderExtractEvent.draw() {
        if (!com.engineerclient.p3sim.P3Sim.inSim && !(DungeonUtils.inBoss && DungeonUtils.floor?.floorNumber == 7)) return
        val level = mc.level ?: return
        // Every setting read is a Compose state read: once a frame, not once per lever.
        val color = leverColor
        val fill = if (leverFill) fillOf(color) else null
        val depth = !leverWalls
        val count = if (deviceLevers) ALL_LEVERS.size else SECTION_LEVERS.size
        for (i in 0 until count) {
            val pos = ALL_LEVERS[i]
            val state = level.getBlockState(pos)
            if (state.block !is LeverBlock) continue
            val shape = state.getShape(level, pos)
            if (shape.isEmpty) continue
            if (shape !== shapes[i]) {
                boxes[i] = shape.bounds().move(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
                shapes[i] = shape
            }
            val box = boxes[i]!!
            if (fill != null) drawFilledBox(box, fill, depth = depth)
            drawWireFrameBox(box, color, depth = depth)
        }
    }

    /** [color] at a fifth of its alpha. */
    private fun fillOf(color: Color): Color {
        val rgba = color.rgba
        val alpha = color.alphaFloat
        fillColor?.let { if (rgba == fillRgba && alpha == fillAlpha) return it }
        return color.withAlpha(alpha * 0.2f).also { fillColor = it; fillRgba = rgba; fillAlpha = alpha }
    }
}
