package com.odtheking.odin.features.impl.dungeon.puzzlesolvers

import com.engineerclient.mixin.ArrowInGroundInvoker
import com.engineerclient.p3sim.Bows
import com.engineerclient.p3sim.ShotPlan
import com.odtheking.odin.OdinMod.mc
import com.odtheking.odin.utils.itemId
import com.odtheking.odin.utils.lore
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/** Bow shots in puzzle rooms: where the arrows of a shot about to be fired, or already flying, will land. */
object PuzzleArrows {
    /** An arrow moving [a] to [b] on tick [start], then [v] a tick, as Hypixel's server moves it. */
    class Flight(val a: Vec3, val b: Vec3, val v: Vec3, val start: Int, val shot: Boolean)

    /** Where a flight ends: in box [box] of the ones asked for, else in [block], on [tick]. */
    class Landing(val box: Int, val block: BlockPos?, val tick: Int)

    private const val FLIGHT_TICKS = 60
    private const val G = 0.05

    /** A shortbow click ([left]: a swing): its shot, or null when it doesn't shoot. */
    fun shortbowShot(left: Boolean): List<Flight>? {
        val bow = mc.player?.mainHandItem?.let(::shortbowId) ?: return null
        if (left && bow == Bows.MOSQUITO) return null
        return shot(bow, 3.0)
    }

    /** A drawn bow's release: its shot, or null when it doesn't shoot. */
    fun releaseShot(): List<Flight>? {
        val player = mc.player ?: return null
        val stack = player.useItem
        if (!stack.`is`(Items.BOW) || shortbowId(stack) != null) return null
        val power = BowItem.getPowerForTime(player.ticksUsingItem)
        return if (power < 0.1f) null else shot(stack.itemId, power * 3.0)
    }

    /** Arrows in the air within 64 blocks ([own]: only yours). */
    fun flying(own: Boolean): List<Flight> {
        val player = mc.player ?: return emptyList()
        val level = mc.level ?: return emptyList()
        return level.getEntitiesOfClass(AbstractArrow::class.java, player.boundingBox.inflate(64.0)) { arrow ->
            !(arrow as ArrowInGroundInvoker).`ec$isInGround`() && (!own || arrow.owner === player)
        }.map { val v = it.deltaMovement; Flight(it.position(), it.position().add(v), v.scale(0.99).subtract(0.0, G, 0.0), 1, false) }
    }

    /** The first of [boxes] the arrow enters, or the block it stops in; [stops]: boxes that stop it with no landing. */
    fun land(f: Flight, boxes: List<AABB>, stops: List<AABB> = emptyList()): Landing? {
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        var a = f.a; var b = f.b; var v = f.v
        for (tick in f.start until f.start + FLIGHT_TICKS) {
            val wall = level.clip(ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            val end = if (wall.type == HitResult.Type.MISS) b else wall.location
            var hit = -1; var best = Double.MAX_VALUE
            val all = boxes + stops
            all.forEachIndexed { i, box ->
                val d = if (box.contains(a)) 0.0 else box.clip(a, end).map { a.distanceToSqr(it) }.orElse(Double.MAX_VALUE)
                if (d < best) { best = d; hit = i }
            }
            if (hit >= boxes.size) return null
            if (hit >= 0) return Landing(hit, null, tick)
            if (wall.type != HitResult.Type.MISS) return Landing(-1, (wall as net.minecraft.world.phys.BlockHitResult).blockPos, tick)
            a = b; b = b.add(v); v = v.scale(0.99).subtract(0.0, G, 0.0)
        }
        return null
    }

    private fun shot(bow: String, speed: Double): List<Flight> {
        val player = mc.player ?: return emptyList()
        return ShotPlan.plan(bow, player.position(), player.yRot, player.xRot, player.isShiftKeyDown, 0, Vec3.ZERO, 3, speed)
            .map { Flight(it.from, it.at, it.v, 1 + it.delay, true) }
    }

    private fun shortbowId(stack: ItemStack): String? {
        if (!stack.`is`(Items.BOW)) return null
        val id = stack.itemId
        return id.takeIf { it in Bows.SHORTBOWS || stack.lore.any { line -> "Shortbow: Instantly shoots!" in line.string } }
    }

    // Every puzzle block on a bow or a punch goes through here; true holds the input back.
    @JvmStatic
    fun blocksUse(): Boolean = shortbowShot(false)?.let(::blocksShot) ?: false

    @JvmStatic
    fun blocksAttack(): Boolean = BeamsSolver.blocksPunch() || (shortbowShot(true)?.let(::blocksShot) ?: false)

    @JvmStatic
    fun blocksRelease(): Boolean {
        val shot = releaseShot() ?: return false
        if (!blocksShot(shot)) return false
        mc.player?.stopUsingItem()
        return true
    }

    private fun blocksShot(shot: List<Flight>): Boolean {
        val blocked = BlazeSolver.blocksShot(shot) || BeamsSolver.blocksShot(shot)
        if (blocked) PuzzleSolvers.blocked()
        else BeamsSolver.fired(shot)
        return blocked
    }
}
