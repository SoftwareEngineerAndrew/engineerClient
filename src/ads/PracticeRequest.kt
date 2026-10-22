package com.engineerclient.ads

import net.rustclear.mod.Floor

/** One thing to practice in the dungeon engine's own solo world (P3 is the P3 Sim's, not here). */
sealed interface PracticeRequest {
    val floor: Floor

    /** A whole run; [seed] null makes a new map, a seed replays the same one. */
    data class FullClear(override val floor: Floor, val seed: Long?) : PracticeRequest

    /** One room alone, entering through [door] (north, east, south, west or random). */
    data class Room(override val floor: Floor, val room: String, val door: String) : PracticeRequest

    /** From the entrance to the blood door, timed, with the blood key in hand. */
    data class BloodRush(override val floor: Floor, val seed: Long?) : PracticeRequest

    /** In the blood room as the Watcher's camp starts, timed to the portal. */
    data class BloodCamp(override val floor: Floor) : PracticeRequest

    /** Straight into the boss at [phase]; P5 is always M7. */
    data class Boss(override val floor: Floor, val phase: Phase) : PracticeRequest

    enum class Phase(val label: String) { P1("Maxor"), P2("Storm"), P4("Necron"), P5("Wither King") }
}
