package com.engineerclient.p3sim.clear.blood.camp

import kotlin.random.Random

/** The Watcher's own camp lines by class (a line within a class is drawn uniformly, as measured). The pass line is [com.engineerclient.p3sim.clear.blood.WatcherLines.PASS]. */
object CampLines {
    /** The opening line, 3 ticks after the door: this one in 128 of 176 camps. */
    const val INTRO = "Ah, we meet again. As I foresaw..."

    /** The other opening line (48 of 176 camps). */
    const val INTRO_OTHER = "Things feel a little more roomy now, eh?"

    /** The share of camps that open with [INTRO]. */
    const val INTRO_SHARE = 128.0 / 176.0

    val SPAWN = listOf("This guy looks like a fighter.", "You'll do.", "Go and live again!", "Hmmm... this one!", "Go, fight!")
    val REACT = listOf("Very nice.", "I'm impressed.", "Aw, I liked that one.", "That one was weak anyway.", "Not bad.")
    val HANDLE = listOf("Let's see how you can handle this.")
    val ENOUGH = listOf("That will be enough for now.")
    val ATTACK = listOf("Stop attacking me.")
    val LEDGE = listOf(
        "My Watchful Eyes see you up there! Come down and fight!", "Don't try to sneak anything past my Watchful Eyes. They see you up there!",
        "We are always watching. Come down from there!", "My Watchful Eyes see all!",
    )

    /** The opening line of one camp: [INTRO] in [INTRO_SHARE] of camps, else [INTRO_OTHER]. */
    fun opening(rng: Random): String = if (rng.nextDouble() < INTRO_SHARE) INTRO else INTRO_OTHER

    /** A line for [line]'s class. The opening line is [opening]'s business, so it is not drawn here (null). */
    fun pick(line: CampMech.Line, rng: Random): String? {
        val pool = when (line) {
            CampMech.Line.SPAWN -> SPAWN
            CampMech.Line.REACT -> REACT
            CampMech.Line.HANDLE -> HANDLE
            CampMech.Line.ENOUGH -> ENOUGH
            CampMech.Line.ATTACK -> ATTACK
            CampMech.Line.LEDGE -> LEDGE
            CampMech.Line.INTRO -> return null
        }
        return pool[rng.nextInt(pool.size)]
    }
}
