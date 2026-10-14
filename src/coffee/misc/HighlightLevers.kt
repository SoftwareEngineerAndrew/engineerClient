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

/** A box around each P3 lever's hitbox (each lever's own shape: it depends on how the lever faces), in F7/M7 boss and the P3 Sim Beta. */
object HighlightLevers : Module(
    name = "Highlight Levers",
    category = Category.custom("Coffee Client", 860, 10),
    description = "A blue box around each P3 lever's hitbox in F7/M7 boss (and the P3 Sim Beta), so you can see where to click.",
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

    init {
        on<RenderExtractEvent> { CoffeeClient.safely("highlight levers") { draw() } }
    }

    private fun RenderExtractEvent.draw() {
        if (!com.engineerclient.p3sim.P3Sim.inSim && !(DungeonUtils.inBoss && DungeonUtils.floor?.floorNumber == 7)) return
        val level = mc.level ?: return
        val fill = leverColor.withAlpha(leverColor.alphaFloat * 0.2f)
        for (pos in if (deviceLevers) SECTION_LEVERS + DEVICE_LEVERS else SECTION_LEVERS) {
            val state = level.getBlockState(pos)
            if (state.block !is LeverBlock) continue
            val shape = state.getShape(level, pos)
            if (shape.isEmpty) continue
            val box = shape.bounds().move(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
            if (leverFill) drawFilledBox(box, fill, depth = !leverWalls)
            drawWireFrameBox(box, leverColor, depth = !leverWalls)
        }
    }
}
