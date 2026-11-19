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
import com.adm.features.impl.dungeon.map.tile.DungeonRoom
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.phys.AABB

object BlazeSolver {
    private class Target(val stand: ArmorStand, var hp: Int)

    // In shooting order; a stand's blaze is the entity one id below it.
    private val blazes = ArrayList<Target>(10)

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
        val hp = maxHealth(custom.string) ?: return
        val uuid = stand.uuid
        // Some tags read a 600/600 placeholder for a few ticks before their real health
        for (i in blazes.indices) {
            val known = blazes[i]
            if (known.stand.uuid != uuid) continue
            if (known.hp == hp) return
            known.hp = hp
            order(DungeonUtils.currentRoom?.data?.name)
            return
        }
        blazes.add(Target(stand, hp))
        order(DungeonUtils.currentRoom?.data?.name)
    }

    // "[Lv11] <icon> Blaze 120/1,200❤": the icon is a resource-pack glyph, so only the level, name and health are fixed
    private fun maxHealth(tag: String): Int? {
        if (!tag.endsWith('❤') || !tag.startsWith("[Lv") || !tag.contains(" Blaze ")) return null
        val slash = tag.lastIndexOf('/')
        if (slash < 0 || slash + 1 >= tag.length - 1) return null
        var hp = 0
        for (i in slash + 1 until tag.length - 1) {
            val c = tag[i]
            if (c == ',') continue
            if (c !in '0'..'9') return null
            hp = hp * 10 + (c - '0')
        }
        return hp
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

    // The block most players stand on to shoot from (Higher: also where they etherwarp to), in the room's frame.
    private val HIGHER_SPOT = BlockPos(9, 95, 18)
    private val LOWER_SPOT = BlockPos(21, 56, 15)
    private var spotRoom: DungeonRoom? = null
    private var spotBox: AABB? = null

    fun renderSpot(event: RenderExtractEvent, color: Color) {
        val room = DungeonUtils.currentRoom ?: return
        val name = room.data?.name
        if (name != "Lower Blaze" && name != "Higher Blaze") return
        if (Puzzle.BLAZE.status == PuzzleStatus.Completed) return
        if (spotRoom !== room || spotBox == null) {
            if (room.clayPos == null) return
            spotBox = AABB(room.getRealCoords(if (name == "Higher Blaze") HIGHER_SPOT else LOWER_SPOT))
            spotRoom = room
        }
        event.drawStyledBox(spotBox!!, color, BoxStyle.FILLED_OUTLINE, depth = false)
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
        spotRoom = null
        spotBox = null
    }
}
