package com.engineerclient.practice

import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity

/** Finding the teammates in Odin's Leap Menu in the world, for the leap highlights (Recore, EE Highlight). */
internal object LeapTeammates {
    private val CONTROL_CODES = Regex("§.")

    /** [m]'s player as this game has it: their entity, else a player by their name, else (P3 sim) their bot; null out of view. */
    fun entity(m: DungeonPlayer, level: ClientLevel): Entity? =
        m.entity?.takeIf { !it.isRemoved } ?: level.players().firstOrNull { it.name.string == m.name } ?: simBot(level, m.name)

    /** In the P3 sim, a bot teammate: its Mannequin, by the name over it. */
    private fun simBot(level: ClientLevel, name: String): Entity? {
        if (!com.engineerclient.p3sim.P3Sim.inSim) return null
        return level.entitiesForRendering().firstOrNull { it is net.minecraft.world.entity.decoration.Mannequin && !it.isRemoved && it.customName?.string?.replace(CONTROL_CODES, "") == name }
    }

    /**
     * A Spirit Leap drags you to its target over several ticks, and a leap from S2 into S4 goes straight
     * through the core box: anyone moving faster than this per tick is in flight, not where they seem to be. Running
     * is 0.3 a tick, a leap drag many blocks; an etherwarp is one big jump, then still.
     */
    private const val FLIGHT_PER_TICK = 2.0
    fun inFlight(p: Entity): Boolean {
        val dx = p.x - p.xOld; val dy = p.y - p.yOld; val dz = p.z - p.zOld
        return dx * dx + dy * dy + dz * dz > FLIGHT_PER_TICK * FLIGHT_PER_TICK
    }
}
