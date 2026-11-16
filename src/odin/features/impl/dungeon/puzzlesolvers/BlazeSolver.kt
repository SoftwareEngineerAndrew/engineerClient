package com.odtheking.odin.features.impl.dungeon.puzzlesolvers

import com.engineerclient.mixin.ArrowInGroundInvoker
import com.engineerclient.p3sim.Bows
import com.engineerclient.p3sim.ShotPlan
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.RenderExtractEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.features.impl.dungeon.puzzlesolvers.PuzzleSolvers.onPuzzleComplete
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.equalsOneOf
import com.odtheking.odin.utils.render.BoxStyle
import com.odtheking.odin.utils.render.drawLine
import com.odtheking.odin.utils.render.drawStyledBox
import com.odtheking.odin.utils.sendCommand
import com.odtheking.odin.utils.skyblock.dungeon.DungeonListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.Puzzle
import com.odtheking.odin.utils.skyblock.dungeon.PuzzleStatus
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.lore
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

object BlazeSolver {
    private class BlazeTag(val entity: ArmorStand, val maxHealth: Int)

    private val blazes = ArrayList<BlazeTag>(10)
    private val blazeHealthRegex = Regex("^\\[Lv+\\d+]  Blaze [\\d,]+/([\\d,]+)❤$")

    inline val inBlazeRoom get() = DungeonUtils.currentRoomName.equalsOneOf("Lower Blaze", "Higher Blaze")

    fun isBlazeTag(entity: Entity): Boolean =
        entity is ArmorStand && inBlazeRoom && blazes.any { it.entity.id == entity.id }

    fun onEntityData(event: EntityEvent.SetData) {
        if (!DungeonUtils.inClear) return
        val entity = (event.entity as? ArmorStand)?.takeIf { !it.isDeadOrDying } ?: return
        if (blazes.any { it.entity.uuid == entity.uuid }) return
        val name = entity.name.string.takeIf { it.endsWith('❤') } ?: return
        val maxHealth = blazeHealthRegex.find(name)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: return
        blazes.add(BlazeTag(entity, maxHealth))
        sort(DungeonUtils.currentRoomName)
    }

    private fun sort(roomName: String?) {
        if (roomName == "Lower Blaze") blazes.sortByDescending { it.maxHealth }
        else blazes.sortBy { it.maxHealth }
    }

    fun onRoomEnter(event: RoomEnterEvent) {
        sort(event.room?.data?.name)
    }

    fun onEntityRemove(event: EntityEvent.Remove) {
        blazes.removeAll { it.entity.id == event.entity.id }
    }

    fun onEntityDeath(event: EntityEvent.Event, sendComplete: Boolean) {
        if (event.id != 3.toByte() || event.entity !is Blaze) return
        if (!blazes.removeAll { it.entity.id == event.entity.id + 1 } || blazes.isNotEmpty() || !inBlazeRoom) return

        DungeonListener.puzzles.find { it == Puzzle.BLAZE }?.status = PuzzleStatus.Completed
        onPuzzleComplete(DungeonUtils.currentRoomName)
        if (sendComplete) sendCommand("pc Blaze puzzle solved!")
    }

    fun onRenderWorld(event: RenderExtractEvent, blazeLineNext: Boolean, blazeLineAmount: Int, blazeStyle: BoxStyle, blazeFirstColor: Color, blazeSecondColor: Color, blazeThirdColor: Color, blazeAllColor: Color, blazeLineWidth: Float) {
        if (blazes.isEmpty() || !inBlazeRoom) return

        blazes.forEachIndexed { index, tag ->
            val color = when (index) {
                0 -> blazeFirstColor
                1 -> blazeSecondColor
                2 -> blazeThirdColor
                else -> blazeAllColor
            }
            val aabb = tag.entity.boundingBox.inflate(0.5, 1.0, 0.5).move(0.0, -1.0, 0.0)

            event.drawStyledBox(aabb, color, blazeStyle, depth = true)

            if (blazeLineNext && index > 0 && index <= blazeLineAmount)
                event.drawLine(listOf(blazes[index - 1].entity.boundingBox.center, aabb.center), color = color, thickness = blazeLineWidth, depth = true)
        }
    }

    /** A shortbow click ([left]: a swing): true holds it back. */
    @JvmStatic
    fun blocksShortbow(left: Boolean): Boolean {
        if (!active()) return false
        val bow = mc.player?.mainHandItem?.let(::shortbowId) ?: return false
        if (left && bow == Bows.MOSQUITO) return false
        return blocks(bow, 3.0)
    }

    /** A drawn bow's release: true drops the draw instead of shooting. */
    @JvmStatic
    fun blocksRelease(): Boolean {
        if (!active()) return false
        val player = mc.player ?: return false
        val stack = player.useItem
        if (!stack.`is`(Items.BOW) || shortbowId(stack) != null) return false
        val power = BowItem.getPowerForTime(player.ticksUsingItem)
        if (power < 0.1f || !blocks(stack.itemId, power * 3.0)) return false
        player.stopUsingItem()
        return true
    }

    private fun active() = PuzzleSolvers.enabled && PuzzleSolvers.blazeBlockWrong && DungeonUtils.inClear && inBlazeRoom && blazes.isNotEmpty()

    private fun shortbowId(stack: ItemStack): String? {
        if (!stack.`is`(Items.BOW)) return null
        val id = stack.itemId
        return id.takeIf { it in Bows.SHORTBOWS || stack.lore.any { line -> "Shortbow: Instantly shoots!" in line.string } }
    }

    // Each blaze has to be hit after every one before it: arrows already flying count, and they land first only on an earlier tick.
    private fun blocks(bow: String, speed: Double): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val boxes = blazes.map { tag -> (level.getEntity(tag.entity.id - 1) as? Blaze)?.boundingBox?.inflate(0.3) ?: tag.entity.boundingBox.inflate(0.5, 1.0, 0.5).move(0.0, -1.0, 0.0) }

        val landed = HashMap<Int, Int>()
        for (arrow in level.getEntitiesOfClass(AbstractArrow::class.java, player.boundingBox.inflate(64.0))) {
            if ((arrow as ArrowInGroundInvoker).`ec$isInGround`()) continue
            val v = arrow.deltaMovement
            val (index, tick) = fly(arrow.position(), arrow.position().add(v), v.scale(0.99).subtract(0.0, G, 0.0), 1, boxes) ?: continue
            if (tick < (landed[index] ?: Int.MAX_VALUE)) landed[index] = tick
        }

        val shot = ShotPlan.plan(bow, player.position(), player.yRot, player.xRot, player.isShiftKeyDown, 0, Vec3.ZERO, 3, speed)
        val blocked = shot.any { arrow ->
            val (index, tick) = fly(arrow.from, arrow.at, arrow.v, 1 + arrow.delay, boxes) ?: return@any false
            (0 until index).any { (landed[it] ?: Int.MAX_VALUE) >= tick }
        }
        if (blocked) PuzzleSolvers.blocked()
        return blocked
    }

    /** The first blaze an arrow moving [a] to [b] on tick [start], then [v] a tick, hits, and the tick it does; null if a block stops it first. */
    private fun fly(a0: Vec3, b0: Vec3, v0: Vec3, start: Int, boxes: List<AABB>): Pair<Int, Int>? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        var a = a0; var b = b0; var v = v0
        for (tick in start until start + FLIGHT_TICKS) {
            val wall = level.clip(ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            val end = if (wall.type == HitResult.Type.MISS) b else wall.location
            var hit = -1; var best = Double.MAX_VALUE
            boxes.forEachIndexed { i, box ->
                val d = if (box.contains(a)) 0.0 else box.clip(a, end).map { a.distanceToSqr(it) }.orElse(Double.MAX_VALUE)
                if (d < best) { best = d; hit = i }
            }
            if (hit >= 0) return hit to tick
            if (wall.type != HitResult.Type.MISS) return null
            a = b; b = b.add(v); v = v.scale(0.99).subtract(0.0, G, 0.0)
        }
        return null
    }

    private const val FLIGHT_TICKS = 60
    private const val G = 0.05

    fun reset() {
        blazes.clear()
    }
}
