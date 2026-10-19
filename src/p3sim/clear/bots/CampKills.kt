package com.engineerclient.p3sim.clear.bots

/**
 * How fast a teammate camping blood kills the Watcher's mobs.
 *
 * Measured (ADR, 311 F7 clears; 1,659 camp mobs, the player models the Watcher throws, that went while the recorder stood in the blood room,
 * so gone is a death and not out of view): from first in the room (in the air, falling from the head's end point) to dead p10/p25/p50/p75/p90
 * 21/22/24/35/118 ticks. UNMEASURED: whether a camper kills more slowly alone (the recorded camps had the party round them).
 */
object CampKills {
    /** The Watcher's own mob kind, never killed by a camper. */
    const val WATCHER = "watcher"

    /** Ticks from a camp mob first standing in the room to its death, the measured spread drawn piecewise. */
    fun life(u: Double): Int = Math.round(PlanMath.curve(listOf(0.0 to 16.0, 0.1 to 21.0, 0.25 to 22.0, 0.5 to 24.0, 0.75 to 35.0, 0.9 to 118.0, 1.0 to 260.0), u)).toInt()
}
