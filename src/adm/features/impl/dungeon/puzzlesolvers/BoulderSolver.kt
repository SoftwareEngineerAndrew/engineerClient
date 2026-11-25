package com.adm.features.impl.dungeon.puzzlesolvers

import com.adm.AdmMod.mc
import com.adm.events.RenderExtractEvent
import com.adm.events.RoomEnterEvent
import com.adm.events.UseItemOnPostEvent
import com.adm.utils.Color
import com.adm.utils.JsonResourceLoader
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawStyledBox
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.AABB

object BoulderSolver {
    private data class BoxPosition(val render: AABB, val click: BlockPos)
    private var currentPositions = mutableListOf<BoxPosition>()
    private var solutions: Map<String, List<List<Int>>> = JsonResourceLoader.loadJson(Identifier.fromNamespaceAndPath("engineerclient", "puzzles/boulder-solutions.json")) ?: emptyMap()

    fun onRoomEnter(event: RoomEnterEvent) = with(event.room) {
        if (this?.data?.name != "Boulder") return@with reset()
        var str = ""
        for (z in 24 downTo 9 step 3) {
            for (x in 24 downTo 6 step 3) {
                str += if (mc.level?.getBlockState(getRealCoords(BlockPos(x, 66, z)))?.isAir == true) "0" else "1"
            }
        }
        currentPositions = solutions[str]?.map { sol ->
            val render = getRealCoords(BlockPos(sol[0], 65, sol[1]))
            val click = getRealCoords(BlockPos(sol[2], 65, sol[3]))
            BoxPosition(AABB(render), click)
        }?.toMutableList() ?: return@with
    }

    fun onRenderWorld(event: RenderExtractEvent, showAllBoulderClicks: Boolean, boulderStyle: BoxStyle, boulderColor: Color) {
        if (DungeonUtils.currentRoomName != "Boulder" || currentPositions.isEmpty()) return
        if (showAllBoulderClicks) currentPositions.forEach {
            event.drawStyledBox(it.render, boulderColor, boulderStyle, false)
        } else currentPositions.firstOrNull()?.let {
            event.drawStyledBox(it.render, boulderColor, boulderStyle, false)
        }
    }

    fun playerInteract(event: UseItemOnPostEvent) {
        currentPositions.remove(currentPositions.firstOrNull { it.click == event.hitResult.blockPos })
    }

    fun reset() {
        currentPositions = mutableListOf()
    }
}