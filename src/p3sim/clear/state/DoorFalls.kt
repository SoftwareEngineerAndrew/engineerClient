package com.engineerclient.p3sim.clear.state

/** What to write into a door's 36 blocks. */
enum class BodyBlocks { CLOSED, BARRIER, AIR }

/** One thing a falling door does at a tick. */
data class FallStep(val door: Int, val blocks: BodyBlocks, val onlyFirst: Boolean = false)

/**
 * The fall of doors, coal (or red terracotta, infested stone) -> barrier -> air (FORMAT-runstate 4 rule 1). On use the 36 blocks
 * become barrier in one server tick (96.7 % of 769 measured doors; with [firstBlockToRestTicks] above 0 the first block goes one
 * step earlier and the other 35 follow), and all 36 become air together [barrierTicks] later: 14 ticks for wither and blood doors,
 * 15 for the entrance door. Keeps the times in the run clock; pure.
 */
class DoorFalls {
    private class Fall(val door: Int, val startT: Int, val restT: Int, val airT: Int)

    private val falling = LinkedHashMap<Int, Fall>()

    /** Starts door [door] falling at run tick [t]. Returns the writes to make now (the barrier; none when [barrierDelay] is above 0). */
    fun begin(door: Int, t: Int, barrierTicks: Int, firstBlockToRestTicks: Int = 0, barrierDelay: Int = 0): List<FallStep> {
        if (door in falling) return emptyList()
        // A delayed fall writes nothing now: the whole barrier comes [barrierDelay] ticks later (key doors: one tick after the chat line).
        val rest = t + maxOf(firstBlockToRestTicks, barrierDelay, 0)
        falling[door] = Fall(door, t, rest, rest + barrierTicks)
        if (barrierDelay > 0) return emptyList()
        return if (firstBlockToRestTicks > 0) listOf(FallStep(door, BodyBlocks.BARRIER, onlyFirst = true)) else listOf(FallStep(door, BodyBlocks.BARRIER))
    }

    /** Re-creates a fall that was in progress (a savepoint restore): the door opened at [openedT]. */
    fun resume(door: Int, openedT: Int, barrierTicks: Int) { falling[door] = Fall(door, openedT, openedT, openedT + barrierTicks) }

    /** The air writes due at run tick [t]: every fall that has run its time. Finished falls are forgotten. */
    fun due(t: Int): List<FallStep> {
        val out = ArrayList<FallStep>()
        val done = ArrayList<Int>()
        for (f in falling.values) {
            if (t >= f.airT) { out += FallStep(f.door, BodyBlocks.AIR); done += f.door }
        }
        for (d in done) falling.remove(d)
        return out
    }

    /** The rest of a staggered fall is due at [t]: doors whose first block went earlier. */
    fun restDue(t: Int, previous: Int): List<FallStep> =
        falling.values.filter { it.restT > it.startT && it.restT > previous && it.restT <= t }.map { FallStep(it.door, BodyBlocks.BARRIER) }

    fun isFalling(door: Int): Boolean = door in falling

    fun airAt(door: Int): Int? = falling[door]?.airT

    fun clear() = falling.clear()
}
