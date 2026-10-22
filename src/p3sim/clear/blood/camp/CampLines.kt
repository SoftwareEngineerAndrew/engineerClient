package com.engineerclient.p3sim.clear.blood.camp

import kotlin.random.Random

/** The Watcher's own camp lines by class (a line within a class is drawn uniformly, as measured). The pass line is [com.engineerclient.p3sim.clear.blood.WatcherLines.PASS]. */
object CampLines {
    /** The opening line, 3 ticks after the door: this one in 471 of 501 recorded camps (DR and ADR, one per run). */
    const val INTRO = "Ah, we meet again. As I foresaw..."

    /**
     * The other opening line (30 of 501 camps). It came in runs bunched in time (every run of one afternoon, one run in a week of others),
     * so something per session picks it; what is UNKNOWN, and a camp draws it at the recorded share.
     */
    const val INTRO_OTHER = "Things feel a little more roomy now, eh?"

    /** After [INTRO_OTHER], always these two, [INTRO_OTHER_GAP] and twice that many ticks after it (30 of 30 camps). */
    val INTRO_OTHER_MORE = listOf("I've knocked down those pillars to go for a more...open concept.", "Plus I needed to give my new friends some space to roam...")
    const val INTRO_OTHER_GAP = 82

    /** The share of camps that open with [INTRO]. */
    const val INTRO_SHARE = 471.0 / 501.0

    /** A replay hands the recording's lines of each class here, in order: [pick] takes the next one before drawing (null: drawn). */
    @Volatile var script: Map<CampMech.Line, ArrayDeque<String>>? = null

    /** Which class a Watcher line belongs to (null: the opening, the pass line or one not in the tables). */
    fun classOf(text: String): CampMech.Line? = when (text) {
        in SPAWN -> CampMech.Line.SPAWN; in REACT -> CampMech.Line.REACT; in HANDLE -> CampMech.Line.HANDLE
        in ENOUGH -> CampMech.Line.ENOUGH; in ATTACK -> CampMech.Line.ATTACK; in LEDGE -> CampMech.Line.LEDGE; else -> null
    }

    /** A replay sets the recording's opening here so its camp opens the same way (null: drawn). */
    @Volatile var forcedOpening: String? = null

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
    fun opening(rng: Random): String = forcedOpening ?: if (rng.nextDouble() < INTRO_SHARE) INTRO else INTRO_OTHER

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
        script?.get(line)?.removeFirstOrNull()?.let { return it }
        return pool[rng.nextInt(pool.size)]
    }
}
