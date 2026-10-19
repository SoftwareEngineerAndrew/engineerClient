package com.engineerclient.p3sim.clear.secrets

import com.engineerclient.EngineerClient
import com.engineerclient.p3sim.CarriedBlock
import com.engineerclient.p3sim.Sim
import com.engineerclient.p3sim.run.RunBlocks
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.level.block.state.BlockState

/**
 * What a blown wall shows (a Superboom wall, a crypt tomb, a lever's wall): like a door's debris, every removed block rides an invisible bat that
 * sinks slowly through the floor and is gone.
 *
 * Measured (ADR, 73 F7 clears, 157 bat columns away from any door, about 2 a run): the bat at the cell's y + 0.344 (as a door's), sinking from the
 * first tick at about [SINK_PER_TICK] (-1.2 at 10 ticks, -2.4 at 20, -6.2 at 40), each gone p10/p50/p90 3/19/42 ticks later. The spread of lives is
 * taken to be the floor: a piece goes when it sinks into a solid block (the bottom row at once, the top ones last), at [LIFE] at the latest.
 */
object WallDebris {
    const val DY = 0.34375
    const val SINK_PER_TICK = 0.155
    const val RIDE_DELAY = 1
    const val LIFE = 42

    private class Piece(val bat: Bat, val block: CarriedBlock, val x: Double, val y0: Double, val z: Double, val cellY: Int) { var age = 0 }

    private val pieces = ArrayList<Piece>()

    /** Debris for block [state] leaving cell ([x], [y], [z]) (called before the cell is set to air). Server thread. */
    fun spawn(x: Int, y: Int, z: Int, state: BlockState) {
        if (state.isAir || !RunBlocks.isLoaded(x, z)) return
        EngineerClient.safely("ads wall debris") {
            val px = x + 0.5; val pz = z + 0.5; val py = y + DY
            val b = Bat(EntityTypes.BAT, Sim.level)
            b.isSilent = true; b.isPermanentlyInvulnerable = true; b.isInvisible = true
            b.addEffect(MobEffectInstance(MobEffects.INVISIBILITY, -1, 0, false, false))
            b.setNoAi(true); b.setNoGravity(true)
            b.snapTo(px, py, pz, 0f, 0f)
            val f = CarriedBlock(Sim.level, state)
            f.snapTo(px, py, pz, 0f, 0f)
            Sim.spawn(b); Sim.spawn(f)
            pieces += Piece(b, f, px, py, pz, y)
        }
    }

    /** Sinks the pieces and takes away those that reached the floor or their time. Server thread. */
    fun tick() {
        if (pieces.isEmpty()) return
        val it = pieces.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age++
            if (p.bat.isRemoved) { p.block.discard(); it.remove(); continue }
            if (p.age == RIDE_DELAY && !p.block.isRemoved) p.block.startRiding(p.bat, true, false)
            val y = p.y0 - SINK_PER_TICK * p.age
            val cell = Math.floor(y).toInt()
            val floor = cell < p.cellY && RunBlocks.get(Math.floor(p.x).toInt(), cell, Math.floor(p.z).toInt())?.isAir == false
            if (p.age >= LIFE || floor) { p.block.discard(); p.bat.discard(); it.remove(); continue }
            p.bat.snapTo(p.x, y, p.z, 0f, 0f)
        }
    }

    fun clear() {
        for (p in pieces) { p.block.discard(); p.bat.discard() }
        pieces.clear()
    }
}
