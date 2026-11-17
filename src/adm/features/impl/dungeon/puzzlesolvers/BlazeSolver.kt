package com.adm.features.impl.dungeon.puzzlesolvers

import com.adm.AdmMod.mc
import com.adm.events.EntityEvent
import com.adm.events.RenderExtractEvent
import com.adm.events.RoomEnterEvent
import com.adm.utils.Color
import com.adm.utils.render.BoxStyle
import com.adm.utils.render.drawLine
import com.adm.utils.render.drawStyledBox
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.dungeon.DungeonListener
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.utils.skyblock.dungeon.Puzzle
import com.adm.utils.skyblock.dungeon.PuzzleStatus
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.phys.AABB

object BlazeSolver {
    private class Target(val stand: ArmorStand, val hp: Int)

    // In shooting order; a stand's blaze is the entity one id below it.
    private val blazes = ArrayList<Target>(10)
    private val tagName = Regex("^\\[Lv+\\d+]  Blaze [\\d,]+/([\\d,]+)❤$")

    val inBlazeRoom: Boolean
        get() {
            val name = DungeonUtils.currentRoom?.data?.name ?: return false
            return name == "Lower Blaze" || name == "Higher Blaze"
        }

    fun isBlazeTag(entity: Entity): Boolean {
        if (entity !is ArmorStand || blazes.isEmpty() || !inBlazeRoom) return false
        val id = entity.id
        for (i in blazes.indices) if (blazes[i].stand.id == id) return true
        return false
    }

    fun onEntityData(event: EntityEvent.SetData) {
        val stand = event.entity as? ArmorStand ?: return
        if (!DungeonUtils.inClear || stand.isDeadOrDying) return
        val custom = stand.customName ?: return
        val uuid = stand.uuid
        for (i in blazes.indices) if (blazes[i].stand.uuid == uuid) return
        val text = custom.string
        if (!text.endsWith('❤')) return
        val hp = tagName.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: return
        blazes.add(Target(stand, hp))
        order(DungeonUtils.currentRoom?.data?.name)
    }

    private fun order(room: String?) {
        if (room == "Lower Blaze") blazes.sortByDescending { it.hp } else blazes.sortBy { it.hp }
    }

    fun onRoomEnter(event: RoomEnterEvent) {
        if (blazes.size > 1) order(event.room?.data?.name)
    }

    fun onEntityRemove(event: EntityEvent.Remove) {
        if (blazes.isNotEmpty()) drop(event.entity.id)
    }

    private fun drop(standId: Int): Boolean {
        for (i in blazes.indices) if (blazes[i].stand.id == standId) { blazes.removeAt(i); return true }
        return false
    }

    fun onEntityDeath(event: EntityEvent.Event, sendComplete: Boolean) {
        if (event.id != 3.toByte() || event.entity !is Blaze || blazes.isEmpty()) return
        if (!drop(event.entity.id + 1) || blazes.isNotEmpty() || !inBlazeRoom) return
        DungeonListener.puzzles.find { it == Puzzle.BLAZE }?.status = PuzzleStatus.Completed
        PuzzleSolvers.onPuzzleComplete(DungeonUtils.currentRoomName)
        if (sendComplete) sendCommand("pc Blaze puzzle solved!")
    }

    private fun box(stand: ArmorStand): AABB = stand.boundingBox.inflate(0.5, 1.0, 0.5).move(0.0, -1.0, 0.0)

    fun onRenderWorld(event: RenderExtractEvent, blazeLineNext: Boolean, blazeLineAmount: Int, blazeStyle: BoxStyle, blazeFirstColor: Color, blazeSecondColor: Color, blazeThirdColor: Color, blazeAllColor: Color, blazeLineWidth: Float) {
        if (blazes.isEmpty() || !inBlazeRoom) return
        for (i in blazes.indices) {
            val color = when (i) {
                0 -> blazeFirstColor
                1 -> blazeSecondColor
                2 -> blazeThirdColor
                else -> blazeAllColor
            }
            val aabb = box(blazes[i].stand)
            event.drawStyledBox(aabb, color, blazeStyle, depth = true)
            if (blazeLineNext && i in 1..blazeLineAmount)
                event.drawLine(listOf(blazes[i - 1].stand.boundingBox.center, aabb.center), color = color, thickness = blazeLineWidth, depth = true)
        }
    }

    // Each blaze has to be hit after every one before it: arrows already flying count, and they land first only on an earlier tick.
    fun blocksShot(shot: List<PuzzleArrows.Flight>): Boolean {
        if (!PuzzleSolvers.enabled || !PuzzleSolvers.blazeBlockWrong || !DungeonUtils.inClear || blazes.isEmpty() || !inBlazeRoom) return false
        val level = mc.level ?: return false
        val boxes = blazes.map { (level.getEntity(it.stand.id - 1) as? Blaze)?.boundingBox?.inflate(0.3) ?: box(it.stand) }

        val firstHit = IntArray(boxes.size) { Int.MAX_VALUE }
        for (arrow in PuzzleArrows.flying(own = false)) {
            val land = PuzzleArrows.land(arrow, boxes) ?: continue
            if (land.box >= 0 && land.tick < firstHit[land.box]) firstHit[land.box] = land.tick
        }
        return shot.any { arrow ->
            val land = PuzzleArrows.land(arrow, boxes)
            land != null && land.box >= 0 && (0 until land.box).any { firstHit[it] >= land.tick }
        }
    }

    fun reset() {
        blazes.clear()
    }
}
