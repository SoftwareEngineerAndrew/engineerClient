package com.engineerclient.p3sim.clear.bots

import kotlin.random.Random

/**
 * Small things that make a teammate feel like a player rather than a prop: the soft glass tick of their footsteps, and the deaths a real
 * party suffers. Pure schedules (no world), so the tests can check them; [BotsModule] plays them.
 */

/**
 * When a walking teammate makes its footstep sound. A real teammate's steps are quiet (volume 0.15, pitch 1.0, blocks channel) and come about
 * once every [MEAN_GAP_TICKS] ticks while it walks: 65 a minute per teammate in the recordings, and a teammate walks in three ticks out of four
 * (41 sessions, 55.7 minutes of clear). Standing makes none.
 */
class StepClock(private val random: Random) {
    private val next = HashMap<Int, Int>()

    /** True when [seat], walking or not, should make a step sound at tick [t]. A bot that stands still makes none and starts afresh when it moves again. */
    fun due(seat: Int, t: Int, moving: Boolean): Boolean {
        if (!moving) { next.remove(seat); return false }
        val at = next[seat]
        if (at == null) { next[seat] = t + gap(); return false }
        if (t < at) return false
        next[seat] = t + gap()
        return true
    }

    private fun gap(): Int = (MEAN_GAP_TICKS * (0.7 + 0.6 * random.nextDouble())).toInt().coerceAtLeast(2)

    companion object {
        /** One step sound every 14 ticks while walking (MEASURED: about 65 step sounds a minute per teammate, who walks in 75% of ticks). */
        const val MEAN_GAP_TICKS = 14
    }
}

/** The sounds a teammate's footstep can be. Every one is played at volume 0.15, pitch 1.0 on the blocks channel. */
enum class StepSound(val sound: String) {
    GLASS("block.glass.place"), GRAVEL("block.gravel.step"), WOOL("block.wool.step"), WOOD("block.wood.step"), GRASS("block.grass.step"),
}

/**
 * Which step sound a teammate makes on a floor. Within two blocks of a real teammate 83% of the steps are `block.glass.place`, 13% `block.gravel.step`,
 * 2.6% `block.wool.step`, 1.8% `block.wood.step`, and a few grass and mob steps. The recordings do not say which floor makes which sound; the mapping
 * from the floor's sound group is ASSUMED (the family the block belongs to), with the glass tick for everything else.
 */
object StepSounds {
    /** [group] is the floor block's sound group in lower case (`gravel`, `wool`, `wood`, `grass`...), "" when unknown. */
    fun forFloor(group: String): StepSound = when (group) {
        "gravel", "dirt", "coarse_dirt", "rooted_dirt", "mud" -> StepSound.GRAVEL
        "wool", "carpet", "moss" -> StepSound.WOOL
        "wood", "planks", "log", "bamboo_wood", "nether_wood" -> StepSound.WOOD
        "grass", "azalea", "fern" -> StepSound.GRASS
        else -> StepSound.GLASS
    }
}

/**
 * The teammate deaths of a clear. They are rare: in 433 recorded clears 409 had none, 19 had one and 5 had two (a death happens about once in
 * fifteen clears), spread over the clear from 150 ticks after the first Mort line (half of them before 800). One count is drawn per run.
 */
object TeamDeaths {
    /** Deaths in the clear to number of recorded clears. */
    val DISTRIBUTION: List<Pair<Int, Int>> = listOf(0 to 409, 1 to 19, 2 to 5)

    /** Earliest a teammate dies: before the first rooms are open nobody has met a mob. */
    const val EARLIEST_TICK = 150

    /** Death tick after the first Mort line as (share of deaths, tick): the recorded 5th, 25th, 50th, 75th and 95th percent were 151, 456, 806, 1151 and 1748. */
    val TICK_CURVE: List<Pair<Double, Double>> = listOf(0.0 to 150.0, 0.05 to 151.0, 0.25 to 456.0, 0.5 to 806.0, 0.75 to 1151.0, 0.95 to 1748.0, 1.0 to 1900.0)

    fun drawCount(random: Random): Int {
        var pick = random.nextInt(DISTRIBUTION.sumOf { it.second })
        for ((deaths, n) in DISTRIBUTION) { if (pick < n) return deaths; pick -= n }
        return 0
    }

    /** The run ticks of this run's teammate deaths, sorted, between [EARLIEST_TICK] and [lastTick]. */
    fun schedule(random: Random, lastTick: Int): List<Int> {
        val n = drawCount(random)
        val hi = maxOf(lastTick, EARLIEST_TICK + 1)
        return List(n) { PlanMath.curve(TICK_CURVE, random.nextDouble()).toInt().coerceIn(EARLIEST_TICK, hi - 1) }.sorted()
    }
}

/**
 * One teammate death, from the first hit to the ghost. A real clear death reads "was killed by &lt;mob&gt; and became a ghost" in four cases out of
 * five and happens to someone who has been taking hits, so this one waits for a teammate that stands in a room with a living mob (up to
 * [BotHealth.VICTIM_PATIENCE] ticks past its time), hits it two to four times in the last forty ticks so the sidebar number falls first, and
 * then kills it by that mob. One death in six is instead a dropped connection (see [BotHealth.DISCONNECT_SHARE]). Nothing in the world is touched
 * here; [World] is the module's hands.
 */
class TeamDeath(private val dueT: Int, private val random: Random, disconnectShare: Double = BotHealth.DISCONNECT_SHARE) {
    /** What the module lets a death do. */
    interface World {
        /** A living teammate in a room with a living required mob, or null. */
        fun inFight(random: Random): Int?
        /** Any living teammate, or null. */
        fun anyone(random: Random): Int?
        fun alive(seat: Int): Boolean
        fun hpPercent(seat: Int): Double
        /** A hit by a living mob of the seat's room (none: unnamed). */
        fun hit(seat: Int, percent: Double)
        /** The death, blamed on a living mob of the seat's room (none: "died"). */
        fun kill(seat: Int)
        /** The death of a teammate whose connection dropped: no mob to blame. Default: an ordinary death. */
        fun disconnect(seat: Int) { kill(seat) }
    }

    /** One death in [BotHealth.DISCONNECT_SHARE] is a dropped connection: it happens at its time, with no fight and no run-up hits. */
    private val disconnects = disconnectShare > 0.0 && random.nextDouble() < disconnectShare

    private var seat: Int? = null
    private var deathAt = Int.MAX_VALUE
    private val hitAt = ArrayList<Int>()

    var finished = false
        private set

    /** The tick the death will happen, once the victim is chosen. */
    val diesAt: Int? get() = if (seat == null) null else deathAt

    fun step(t: Int, world: World) {
        if (finished) return
        if (disconnects) {
            if (t >= dueT) { world.anyone(random)?.let { world.disconnect(it) }; finished = true }
            return
        }
        val victim = seat
        if (victim == null) {
            if (t < dueT - BotHealth.RUN_UP_WINDOW) return
            val pick = world.inFight(random)
            if (pick != null) {
                seat = pick
                val hits = random.nextInt(2, 5)
                deathAt = maxOf(dueT, t + BotHealth.RUN_UP_GAP * hits + 1)
                for (j in 1..hits) hitAt += deathAt - BotHealth.RUN_UP_GAP * j
            } else if (t >= dueT + BotHealth.VICTIM_PATIENCE) {
                world.anyone(random)?.let { world.kill(it) }
                finished = true
            }
            return
        }
        if (!world.alive(victim)) { finished = true; return }
        if (hitAt.any { it <= t }) {
            hitAt.removeAll { it <= t }
            world.hit(victim, maxOf(4.0, world.hpPercent(victim) * (BotHealth.RUN_UP_SHARE.start + random.nextDouble() * (BotHealth.RUN_UP_SHARE.endInclusive - BotHealth.RUN_UP_SHARE.start))))
        }
        if (t >= deathAt) { world.kill(victim); finished = true }
    }
}
