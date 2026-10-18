package com.engineerclient.p3sim.clear.blood

/**
 * One thrown wall head's flight (pure, tested): a straight line from the stand it left to the end point the camp names, at an even
 * speed, spinning, and gone on arrival. The camp decides the end point and the time; this only walks the line.
 *
 * UNMEASURED: the spin ([SPIN_PER_TICK] is read off one recorded flying head stand, 12 degrees every 3 ticks).
 */
class HeadFlight(
    val fromX: Double, val fromY: Double, val fromZ: Double,
    val toX: Double, val toY: Double, val toZ: Double,
    /** Ticks the whole flight takes (at least 1). */
    ticks: Int,
    /** The stand's yaw when it left. */
    val startYaw: Float = 0f,
) {
    val ticks: Int = ticks.coerceAtLeast(1)

    /** Blocks per tick. */
    val speed: Double get() = Math.sqrt((toX - fromX) * (toX - fromX) + (toY - fromY) * (toY - fromY) + (toZ - fromZ) * (toZ - fromZ)) / ticks

    /** The stand's feet after [tick] ticks of flight (0 = still on the wall, [ticks] = arrived; clamped). */
    fun x(tick: Int): Double = lerp(fromX, toX, tick)
    fun y(tick: Int): Double = lerp(fromY, toY, tick)
    fun z(tick: Int): Double = lerp(fromZ, toZ, tick)

    /** The yaw after [tick] ticks: the start yaw plus [SPIN_PER_TICK] a tick, wrapped to 0..360. */
    fun yaw(tick: Int): Float = Math.floorMod((startYaw + SPIN_PER_TICK * tick.coerceIn(0, ticks)).toInt(), 360).toFloat()

    /** Whether the head has arrived after [tick] ticks. */
    fun arrived(tick: Int): Boolean = tick >= ticks

    /** Whether the position is sent as a full position sync this tick (every [SYNC_EVERY] ticks, and the last one). */
    fun syncDue(tick: Int): Boolean = tick % SYNC_EVERY == 0 || tick == ticks

    private fun lerp(a: Double, b: Double, tick: Int): Double = a + (b - a) * tick.coerceIn(0, ticks) / ticks

    companion object {
        /** Ticks between position syncs of a flying head (recorded: every 3 ticks). */
        const val SYNC_EVERY = 3

        /** Degrees of spin a tick. */
        const val SPIN_PER_TICK = 4f
    }
}
