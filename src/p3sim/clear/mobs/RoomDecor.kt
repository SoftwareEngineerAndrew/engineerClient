package com.engineerclient.p3sim.clear.mobs

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.pack.Rot
import com.engineerclient.p3sim.pack.rooms.Placement
import com.engineerclient.p3sim.run.Run
import com.engineerclient.p3sim.run.RunBlocks
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.minecart.Minecart
import net.minecraft.world.phys.Vec3

/**
 * Entities that are part of a room's look rather than its mobs: the minecarts standing on the rails of Mines, Rails and Stairs. They stand
 * still for the whole run; nobody can ride or break them.
 *
 * Measured (ADR, about 50 visits of each room, every one SOUTH): the spots below, in the room's own frame (x and z from the placed box's min
 * corner, y absolute), each seen in nearly every visit and never moving. Spots seen once or twice are left out.
 */
object RoomDecor {
    class Spot(val x: Double, val y: Double, val z: Double)

    val MINECARTS: Map<String, List<Spot>> = mapOf(
        "Mines" to listOf(
            Spot(12.47, 41.06, 35.50), Spot(16.50, 63.06, 30.47), Spot(16.50, 61.06, 39.63), Spot(31.88, 40.06, 35.50), Spot(37.75, 41.06, 35.50),
            Spot(49.50, 56.06, 16.50), Spot(49.50, 57.06, 26.63), Spot(49.50, 58.06, 31.84), Spot(51.50, 48.06, 8.50),
        ),
        "Rails" to listOf(Spot(32.50, 70.06, 55.03), Spot(33.59, 47.00, 45.88), Spot(33.69, 68.00, 45.94), Spot(9.47, 73.06, 58.50)),
        "Stairs" to listOf(Spot(10.69, 95.06, 12.50)),
    )

    /** The world position of room-frame point ([x], [z]) under [p] (the Placement law, for points rather than cells). */
    fun toWorld(p: Placement, x: Double, z: Double): DoubleArray {
        val ix = Math.floor(x).toInt(); val iz = Math.floor(z).toInt()
        val fx = x - ix; val fz = z - iz
        val c = p.toWorld(ix, 0, iz)
        val (ox, oz) = when (p.rot) {
            Rot.SOUTH -> fx to fz
            Rot.NORTH -> (1 - fx) to (1 - fz)
            Rot.WEST -> (1 - fz) to fx
            Rot.EAST -> fz to (1 - fx)
        }
        return doubleArrayOf(c.x + ox, c.z + oz)
    }

    /** A minecart that stays where it is put. */
    class StillMinecart(level: net.minecraft.world.level.Level) : Minecart(EntityTypes.MINECART, level) {
        override fun tick() {}
        override fun interact(player: Player, hand: InteractionHand, location: Vec3): InteractionResult = InteractionResult.PASS
        override fun shouldBeSaved(): Boolean = false
    }

    private class Placed(val x: Double, val y: Double, val z: Double) { var e: Minecart? = null }
    private val placed = ArrayList<Placed>()
    private var planned = false

    /** Plans the decorations of [run] once and builds each when its chunk is loaded (again if it went with its chunk). Server thread. */
    fun tick(run: Run) {
        if (!planned) {
            planned = true
            for (room in run.plan.rooms) {
                val spots = MINECARTS[room.name] ?: continue
                val pl = room.placement ?: continue
                for (s in spots) { val w = toWorld(pl, s.x, s.z); placed += Placed(w[0], s.y, w[1]) }
            }
        }
        for (p in placed) {
            if (p.e?.isRemoved == false) continue
            if (!RunBlocks.isLoaded(Math.floor(p.x).toInt(), Math.floor(p.z).toInt())) continue
            EngineerClient.safely("ads room decor") {
                val m = StillMinecart(Sim.level)
                m.snapTo(p.x, p.y, p.z, 0f, 0f)
                m.isPermanentlyInvulnerable = true; m.isSilent = true; m.setNoGravity(true)
                p.e = Sim.spawn(m)
            }
        }
    }

    fun clear() {
        for (p in placed) p.e?.discard()
        placed.clear(); planned = false
    }
}
