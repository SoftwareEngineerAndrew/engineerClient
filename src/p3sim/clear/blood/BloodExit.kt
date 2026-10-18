package com.engineerclient.p3sim.clear.blood

import kotlin.random.Random

/**
 * The clock of the blood room's exit (pure, tested headless). Measured in 36 recorded runs, counted from the tick the Watcher's last
 * counted kill left him at 0.1 health (the defeat tick, "D"):
 *
 * - the portal in the room's back alcove appears in ONE tick, at D+80 in a third of the runs and at D+90 in the other two thirds;
 * - the lightning bolt is added in that tick or the one before it;
 * - the Watcher, his Eyes and their name tags are removed at D+81;
 * - nobody has to be near: the portal opens on its own, and the party is taken to Maxor 1 tick after a player's body first touches
 *   the portal blocks (0 to 3 ticks in 14 of 15 recorded entries).
 *
 * The "You may pass." line is a different clock (D to D+20 ticks, said by the camp's chat queue); it does not start any of this.
 */
class BloodExit(
    /** Absolute server tick of the defeat (the Watcher's last counted kill). */
    val defeatTick: Int,
    /** Ticks from the defeat to the portal: [PORTAL_SHORT] or [PORTAL_LONG]. */
    val portalDelay: Int,
) {
    /** The tick the portal blocks appear (and thunder and the explosion play). */
    val portalAt: Int get() = defeatTick + portalDelay

    /** The tick the lightning bolt entity is added: the one before the portal. */
    val strikeAt: Int get() = portalAt - 1

    /** The tick the Watcher, his Eyes and their tags are removed. */
    val watcherGoneAt: Int get() = defeatTick + WATCHER_GONE

    companion object {
        /** Measured: 12 of 36 runs. */
        const val PORTAL_SHORT = 80

        /** Measured: 24 of 36 runs. */
        const val PORTAL_LONG = 90

        /** The share of runs with the long wait (24 of 36). */
        const val P_LONG = 24.0 / 36.0

        /** Measured: 52 of 53 recorded runs (the 53rd left the tracking range first). */
        const val WATCHER_GONE = 81

        /** Ticks from a player's first contact with the portal to the jump (measured 1 in 11 of 15, 0 to 3 in 14 of 15). */
        const val ENTRY_LAG = 1

        /** Rolls the portal delay for a defeat at [defeatTick]. */
        fun roll(defeatTick: Int, random: Random): BloodExit =
            BloodExit(defeatTick, if (random.nextDouble() < P_LONG) PORTAL_LONG else PORTAL_SHORT)
    }
}

/**
 * The Watcher's health and what hangs on it (pure). Health is 3000 minus 200 for every counted kill (3000, 2800, ... 200) and 0.1 at
 * the fifteenth; the red boss bar shows it as a fraction rounded to hundredths.
 */
object WatcherStats {
    /** Kills that finish him. */
    const val KILLS_TO_DEFEAT = 15

    /** His health and maximum health (Floor 7). */
    const val MAX_HEALTH = 3000f
    const val HEALTH_PER_KILL = 200f
    const val DEFEATED_HEALTH = 0.1f

    /** Each Watchful Eye's health and maximum health. */
    const val EYE_HEALTH = 1000f

    /** His movement speed attribute (the recorded value; he is not driven by it). */
    const val MOVEMENT_SPEED = 0.26

    /** How high above the floor he hangs when the room opens (recorded y 73 over a floor of 69). */
    const val RISE = 4.0

    /** The height of a name tag stand over its body's feet (recorded). */
    const val TAG_DY = 1.96875

    /** The most kills that count, whatever a camp reports. */
    fun clamp(kills: Int): Int = kills.coerceIn(0, KILLS_TO_DEFEAT)

    fun health(kills: Int): Float {
        val k = clamp(kills)
        return if (k >= KILLS_TO_DEFEAT) DEFEATED_HEALTH else MAX_HEALTH - HEALTH_PER_KILL * k
    }

    /** The boss bar fill: health over maximum, rounded to hundredths (1.0 before the first kill, 0.0 at the fifteenth). */
    fun barProgress(kills: Int): Float = Math.round(health(kills) / MAX_HEALTH * 100f) / 100f

    /**
     * The yaw (Minecraft degrees) he faces: toward the blood door, which is the canonical north wall turned by the room's clockwise
     * quarter turns (a SOUTH room 180, WEST 270, NORTH 0, EAST 90).
     */
    fun yawTowardDoor(quarterTurns: Int): Float = ((180 + 90 * Math.floorMod(quarterTurns, 4)) % 360).toFloat()

    /**
     * Where a Watchful Eye hangs relative to the Watcher's feet (world axes, not turned with the room). Recorded across 107 eyes: each
     * horizontal offset is 0.5 to 1.5 blocks on the negative side or 1.5 to 2.5 on the positive side, and 2.5 above his feet.
     */
    fun eyeOffset(random: Random): DoubleArray {
        fun side(): Double = if (random.nextBoolean()) -(0.5 + random.nextDouble()) else 1.5 + random.nextDouble()
        return doubleArrayOf(side(), EYE_RISE, side())
    }

    const val EYE_RISE = 2.5
}
