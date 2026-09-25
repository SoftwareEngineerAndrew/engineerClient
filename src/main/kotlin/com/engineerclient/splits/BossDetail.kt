package com.engineerclient.splits

/**
 * The detail behind the Watcher, Portal and the boss phases — only the moments that were asked
 * for, nothing else, because a detail list is only readable if everything on it was wanted.
 *
 * Four of the requested items are absent, and always for the same reason: nothing reports them.
 * Minecraft sends no mob death message and no damage attribution to a player, so **who killed a
 * mob**, **each hit on Goldor** and **Necron's first hit** cannot be known. Storm's **crusher
 * heights and who moved one** are silent block movements with no player attached. And a terminal
 * announces only that it finished, never a **single click inside Select All**.
 *
 * The closest honest thing to a kill is a mob entity's lifetime, which is here — it knows when,
 * never who. The rest are left out rather than approximated.
 */
class BossDetail(private val detail: SplitDetail) {

    private var watcherMoves = 0

    fun reset() {
        watcherMoves = 0
    }

    /** A mob spawning, in the Watcher fight or during the blood rush. */
    fun onMobSpawn(split: String, at: Stamp, name: String) = detail.add(split, at, "Spawn $name")

    /**
     * A mob gone, with how long it was up. Nothing reports who killed it, so this is the mob's
     * lifetime and nothing more.
     */
    fun onMobGone(split: String, at: Stamp, name: String, lifeMs: Long) =
        detail.add(split, at, "Killed $name §7(" + SplitFormat.time(lifeMs, true) + ")")

    fun onChat(msg: String, at: Stamp) {
        when {
            // The Watcher moves to each new wave, and its taunt is the only sign that it did.
            WATCHER_MOVE.matches(msg) -> {
                watcherMoves++
                detail.add(SplitTracker.WATCHER, at, "Watcher move $watcherMoves")
            }
            // The blessing is what opens the portal.
            msg == WATCHER_DONE -> detail.add(SplitTracker.PORTAL, at, "Portal open")
            // The boss loading is the moment you were actually through it.
            msg == MAXOR_START -> detail.add(SplitTracker.PORTAL, at, "Portal entered")

            msg in LIGHTNING -> detail.add(STORM, at, "Lightning")

            // Goldor dies silently; Necron speaking is the only thing that marks it.
            msg in NECRON_START -> detail.add(GOLDOR, at, "Goldor killed")

            else -> {
                val section = SECTION_DONE.find(msg)
                if (section != null) {
                    val what = when (section.groupValues[2]) {
                        "terminal" -> "Term"
                        "lever" -> "Lever"
                        else -> "Device"
                    }
                    val count = section.groupValues[3] + "/" + section.groupValues[4]
                    detail.add(TERMINALS, at, "$what $count §7(" + section.groupValues[1] + ")")
                    return
                }
                // The crystals were asked for under Maxor, but they are the Wither King's and only
                // exist once Necron has started, so they are filed where they actually happen.
                val picked = CRYSTAL_PICKUP.find(msg)
                if (picked != null) {
                    detail.add(NECRON, at, "Crystal picked §7(" + picked.groupValues[1] + ")")
                    return
                }
                val active = CRYSTAL_ACTIVE.find(msg)
                if (active != null) {
                    detail.add(NECRON, at, "Crystal placed " + active.groupValues[1] + "/" + active.groupValues[2])
                }
            }
        }
    }

    private companion object {
        const val STORM = "&9Storm"
        const val TERMINALS = "&6Terminals"
        const val GOLDOR = "&8Goldor"
        const val NECRON = "&4Necron"

        const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."
        const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"

        val LIGHTNING = setOf(
            "[BOSS] Storm: ENERGY HEED MY CALL!",
            "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!",
        )

        val NECRON_START = setOf(
            "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
            "[BOSS] Necron: You went further than any human before, congratulations.",
        )

        /**
         * The Watcher's between-wave taunts. All ten were counted in the recorded runs; the three
         * least obvious ("Hmmm... this one!", "Very nice.", "This guy looks like a fighter.") are
         * as common as the rest, so leaving them out would drop a third of the moves.
         */
        val WATCHER_MOVE = Regex(
            "^\\[BOSS] The Watcher: (?:Not bad\\.|Aw, I liked that one\\.|You'll do\\.|" +
                "That one was weak anyway\\.|I'm impressed\\.|Go, fight!|Go and live again!|" +
                "Hmmm\\.\\.\\. this one!|Very nice\\.|This guy looks like a fighter\\.)$"
        )

        val SECTION_DONE = Regex("""^(\w+) (?:activated|completed) a (terminal|lever|device)! \((\d+)/(\d+)\)$""")
        val CRYSTAL_PICKUP = Regex("""^(\w+) picked up an Energy Crystal!$""")
        val CRYSTAL_ACTIVE = Regex("""^(\d+)/(\d+) Energy Crystals are now active!$""")
    }
}
