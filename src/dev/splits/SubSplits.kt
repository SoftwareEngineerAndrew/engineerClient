package com.devgineerclient.splits

import kotlin.math.hypot

/**
 * The boss fights broken into sub splits, each timed on both clocks: the Watcher's camp, Maxor,
 * Storm, the four terminal sections, Goldor and Necron.
 *
 * Every step is either the game's own script, which nobody can speed up, or the party's time,
 * never a mix. So a fixed step that runs long points at lag or a missed grid tick, and a
 * controlled step's time over its floor is exactly what the party lost.
 *
 * A step runs until the next one starts, so the whole sequence is a stopwatch that is handed on.
 * What moves it on:
 *
 *  - chat lines (most steps);
 *  - the terminal sections: a section ends when its door opens, which is when both its last
 *    completion and its gate are in. The door's barriers turning to air is seen whatever chat
 *    cleaners hide; chat's last completion plus "The gate has been destroyed!" is the other way;
 *  - things the module sees in the world: Maxor's beacon turning to bedrock (the kill), his placed
 *    crystals vanishing (42 ticks after a hit, for a hit an ability kept quiet), Storm leaving his
 *    spot and reaching Yellow, Necron leaving and coming back to mid, Necron's death (the burst of
 *    TNT he dies in), the Watcher's move, and the camp's last mob appearing;
 *  - one count on the server's ticks: Storm leaves his spot 99 ticks after his lightning line.
 *
 * Nothing here touches Minecraft; the module feeds the world's moments in.
 */
class SubSplitTracker {

    /**
     * Which split a step belongs to, how it reads on the HUD, and its [id] for its colour bands and
     * best time ([SubSplitGrades]). A null label ends a split and isn't shown.
     */
    private class Step(val split: String, val label: String?, val id: String = "")

    private val steps = SEQUENCE
    private val starts = arrayOfNulls<Stamp>(SEQUENCE.size)
    /** How each step's start was found, for Debug: a chat line, a timed wait, something seen. */
    private val sources = arrayOfNulls<String>(SEQUENCE.size)

    /** -1 before anything starts, otherwise the index of the step being timed. */
    private var current = -1

    /** Server ticks since Storm's lightning line, while waiting for him to leave his spot. */
    private var ticks = 0
    private var lightning: Stamp? = null

    /** The Watcher's mobs seen appearing so far: the 19th is the last. */
    private var bloodMobs = 0

    /** The gate and the last completion can arrive in either order; a section ends on the second. */
    private var gateBlown = false
    private var gateWaiting = false

    /** Set once the core is open, so the core-entry watch knows to run. */
    var watchingCore = false
        private set

    fun reset() {
        java.util.Arrays.fill(starts, null)
        java.util.Arrays.fill(sources, null)
        current = -1; ticks = 0; lightning = null; bloodMobs = 0
        gateBlown = false; gateWaiting = false; watchingCore = false
    }

    /** The steps of one split, in order, each running until the next one starts. */
    fun forSplit(split: String): List<Split> {
        val out = mutableListOf<Split>()
        steps.forEachIndexed { i, step ->
            if (step.split != split || step.label == null) return@forEachIndexed
            val start = starts[i] ?: return@forEachIndexed
            val stop = (i + 1 until starts.size).firstNotNullOfOrNull { starts[it] }
            out += Split(step.label, start, stop)
        }
        return out
    }

    /** The ids of [split]'s steps, in [forSplit]'s order. */
    fun idsForSplit(split: String): List<String> =
        steps.indices.filter { steps[it].split == split && steps[it].label != null && starts[it] != null }.map { steps[it].id }

    /**
     * For Debug: how each of [split]'s steps (in [forSplit]'s order) came to an end - how the next
     * step's start was found - or "running". A step can end on a later step's moment when the
     * moments between were never seen; that shows here too.
     */
    fun endSources(split: String): List<String> {
        val out = mutableListOf<String>()
        steps.forEachIndexed { i, step ->
            if (step.split != split || step.label == null || starts[i] == null) return@forEachIndexed
            val next = (i + 1 until starts.size).firstOrNull { starts[it] != null }
            out += when {
                next == null -> "running"
                next != i + 1 && steps[i + 1].split == split -> (sources[next] ?: "?") + " - the steps between were never seen"
                else -> sources[next] ?: "?"
            }
        }
        return out
    }

    /** How [split]'s first step started, for Debug. */
    fun startSource(split: String): String? =
        steps.indices.firstOrNull { steps[it].split == split && starts[it] != null }?.let { sources[it] }

    /** Whether any split has steps yet - the HUDs fall back to chat events until it does. */
    fun started(): Boolean = current >= 0

    // ------------------------------------------------------------------ the world's moments

    /** Odin's server tick: Storm leaves his spot a fixed 99 of them after his lightning line. */
    fun onServerTick() {
        ticks++
        val from = lightning ?: return
        if (current == S_OPENING && ticks >= STORM_LEAVES) {
            lightning = null
            jumpTo(S_CRUSH1, from.plus(STORM_LEAVES), "$STORM_LEAVES server ticks after the lightning line - he wasn't seen leaving, so counted")
        }
    }

    /** The Watcher seen starting his move, the first leg after his dialogue. */
    fun onWatcherMoved(at: Stamp) {
        if (current == W_WAIT) jumpTo(W_CAMP, at, "the Watcher seen starting his move")
    }

    /** A blood mob appearing: the 19th (17 regulars, the Giant and a mini-boss) is the camp's last. */
    fun onBloodMobSpawn(at: Stamp) {
        if (current !in W_DIALOGUE..W_CAMP) return
        if (++bloodMobs >= BLOOD_MOBS) jumpTo(W_CLEAR, at, "the ${BLOOD_MOBS}th blood mob seen appearing")
    }

    /** Maxor's beacon at (73, 221, 73) turning to bedrock: he is dead. */
    fun onMaxorKilled(at: Stamp) {
        if (current in M_CRYSTALS until M_ANIMATION) jumpTo(M_ANIMATION, at, "his beacon turning to bedrock")
    }

    /**
     * Maxor's wither gone, close by: he died 54 ticks earlier. Only for when the beacon wasn't
     * seen; before the last step a wither going is just him leaving view.
     */
    fun onMaxorDead(at: Stamp) {
        if (current == M_KILL) jumpTo(M_ANIMATION, maxOf(at.minus(MAXOR_DESPAWN), starts[M_KILL]!!), "his wither going, $MAXOR_DESPAWN ticks after the kill - the beacon wasn't seen")
    }

    /**
     * Crystals back on Maxor's top platforms: they return 41 ticks after a laser hit, which is how a
     * hit shows when an ability holds back its stun line. (The placed ones vanishing at +42 doesn't
     * work for the second hit: the kill usually comes first and takes them with it.) The fresh pair
     * at his start comes before the laser line, so only counts from Lure on.
     */
    fun onTopCrystal(at: Stamp) {
        val hit = at.minus(CRYSTALS_BACK)
        when (current) {
            M_LURE -> jumpTo(M_COOLDOWN, maxOf(hit, starts[current]!!), "crystals back on top, $CRYSTALS_BACK ticks after a silent hit")
            M_COOLDOWN -> if (hit.tick - starts[M_COOLDOWN]!!.tick >= MIN_HIT_GAP)
                jumpTo(M_KILL, maxOf(hit, starts[current]!!), "crystals back on top, $CRYSTALS_BACK ticks after a silent hit")
        }
    }

    /** Storm's wither, as the server last placed him. */
    fun onStormPosition(at: Stamp, x: Double, y: Double, z: Double) {
        when (current) {
            S_OPENING -> if (lightning != null && hypot(x - STORM_SPOT_X, z - STORM_SPOT_Z) > 0.5) {
                lightning = null
                jumpTo(S_CRUSH1, at, "Storm seen leaving his spot")
            }
            S_FLIGHT -> if (hypot(x - YELLOW_X, z - YELLOW_Z) <= YELLOW_REACHED) jumpTo(S_CRUSH2, at, "Storm seen reaching Yellow")
        }
    }

    /**
     * Necron's wither, how far the server has him from mid. He leaves it for his sidestep and is put
     * back exactly; the hops some runs show during the lock (off at 235-244 ticks in, back 3-18
     * later) and a slow kill's second trip are part of the lock and the kill.
     */
    fun onNecronPosition(at: Stamp, fromMid: Double) {
        when (current) {
            N_INTRO -> if (fromMid > OFF_MID) jumpTo(N_TRIP1, at, "Necron seen leaving mid")
            N_TRIP1 -> if (fromMid < ON_MID) jumpTo(N_LOCK1, at, "Necron seen back at mid")
        }
    }

    /**
     * Necron dead on M7: the burst of TNT he dies in (3 or more within 2 server ticks), with the
     * Wither King next. On F7 his fight runs to the run's end (EXTRA STATS) instead: there is no end
     * animation since Hypixel's boss update.
     */
    fun onNecronDeath(at: Stamp) {
        if (current in N_INTRO until N_END) jumpTo(N_END, at, "his death's TNT burst")
    }

    /** A terminal section's door opening (its barriers turning to air): [section] 1-3 is over. */
    fun onSectionDoor(at: Stamp, section: Int) {
        val step = T_S1 + section - 1
        if (current == step) jumpTo(step + 1, at, "S$section's door opening")
    }

    /**
     * A start part way through the terminals (the P3 Sim's S2-S4 starts): no Goldor line comes, so
     * sections 1..[section] are started back at [starts] (each one's start).
     */
    fun startTerms(section: Int, starts: List<Stamp>) {
        reset()
        for (s in 1..section.coerceAtMost(4)) jumpTo(T_S1 + s - 1, starts[s - 1], "the sim's start")
    }

    /** The party is all inside the core ([how] it was told): the leap is over and Goldor's kill begins. */
    fun onEveryoneInCore(at: Stamp, how: String) {
        if (!watchingCore) return
        watchingCore = false
        if (current == G_LEAPS) jumpTo(G_KILL, at, how)
    }

    // ------------------------------------------------------------------ chat

    fun onChat(msg: String, at: Stamp) {
        when {
            // The Watcher: any line of his starts the camp, as it starts the Blood split.
            msg.startsWith(WATCHER) && current < W_DIALOGUE -> { reset(); jumpTo(W_DIALOGUE, at, said(msg)) }
            msg == WATCHER_HANDLE -> if (current == W_DIALOGUE) jumpTo(W_WAIT, at, said(msg))
            msg == WATCHER_DONE -> if (current in W_DIALOGUE..W_CLEAR) jumpTo(W_END, at, said(msg))

            // A jump rather than a step, so a missed moment earlier cannot leave the rest misaligned.
            msg == MAXOR_START -> jumpTo(M_CRYSTALS, at, said(msg))
            msg == STORM_START -> { lightning = null; jumpTo(S_OPENING, at, said(msg)) }
            msg == GOLDOR_START -> { gateBlown = false; gateWaiting = false; jumpTo(T_S1, at, said(msg)) }
            msg == CORE_OPENING -> if (current in T_S1..T_S4) { jumpTo(G_LEAPS, at, "\"$CORE_OPENING\""); watchingCore = true }
            msg in NECRON_START -> { watchingCore = false; jumpTo(N_INTRO, at, said(msg)) }
            SplitTracker.EXTRA_STATS.matches(msg) -> if (current in N_INTRO until N_END) jumpTo(N_END, at, "the run's end (EXTRA STATS)")

            current < 0 -> return

            // Maxor: the laser charging, the two hits.
            msg == LASER_CHARGING -> if (current == M_CRYSTALS) jumpTo(M_LURE, at, "\"$LASER_CHARGING\"")
            msg in MAXOR_STUN -> when (current) {
                M_CRYSTALS, M_LURE -> jumpTo(M_COOLDOWN, at, said(msg))
                // A held-back stun line after a silent hit is the same hit, not the next one.
                M_COOLDOWN -> if (at.tick - starts[M_COOLDOWN]!!.tick >= MIN_HIT_GAP) jumpTo(M_KILL, at, said(msg))
            }

            // Storm: the lightning arms the count to his leaving; crushes, the pin's end, the death.
            msg in STORM_LIGHTNING -> if (current == S_OPENING && lightning == null) { lightning = at; ticks = 0 }
            msg in STORM_CRUSHED -> when (current) {
                S_OPENING, S_CRUSH1 -> jumpTo(S_PIN, at, said(msg))
                S_CRUSH2 -> jumpTo(S_KILL, at, said(msg))
                // Crushed before he was seen within 2.4 blocks of Yellow (it happens occasionally): Crush is 0.
                S_FLIGHT -> { jumpTo(S_CRUSH2, at, said(msg) + ", before he was seen reaching Yellow"); jumpTo(S_KILL, at, said(msg)) }
            }
            msg == STORM_ENRAGED -> if (current == S_PIN) jumpTo(S_FLIGHT, at, "\"$STORM_ENRAGED\"")
            // Some deaths come with no second crush line: the death still ends whatever is running.
            msg == STORM_DEAD -> if (current in S_OPENING until S_ANIMATION) jumpTo(S_ANIMATION, at, said(msg))

            // Necron: his first ARGH! ends the lock on mid. A second (a slow kill) is part of the kill.
            msg == NECRON_ARGH -> if (current in N_INTRO..N_LOCK1) jumpTo(N_KILL, at, said(msg))

            // Terminals: a section is over once its last completion and its gate are both in.
            msg == GATE_DESTROYED -> if (current in T_S1..T_S3) {
                if (gateWaiting) jumpTo(current + 1, at, "the gate destroyed, after the last completion") else gateBlown = true
            }
            else -> {
                if (current !in T_S1..T_S4) return
                val m = SECTION_DONE.find(msg) ?: return
                if (m.groupValues[2] != m.groupValues[3]) return
                val done = "the last completion (${m.groupValues[3]}/${m.groupValues[3]})"
                when {
                    current == T_S4 -> { jumpTo(G_LEAPS, at, "$done - no gate after S4"); watchingCore = true }
                    gateBlown -> jumpTo(current + 1, at, "$done, after the gate")
                    else -> gateWaiting = true
                }
            }
        }
    }

    /** A chat line as a source: `"YOU TRICKED ME!"`, the speaker left off. */
    private fun said(msg: String) = "\"" + msg.substringAfter(": ").let { if (it.length > 32) it.take(30) + "..." else it } + "\""

    private fun jumpTo(step: Int, at: Stamp, source: String) {
        ticks = 0
        gateBlown = false
        gateWaiting = false
        current = step
        starts[step] = at
        sources[step] = source
    }

    private fun Stamp.plus(n: Int) = Stamp(realMs + n * 50L, tick + n)
    private fun Stamp.minus(n: Int) = Stamp(realMs - n * 50L, tick - n)
    private fun maxOf(a: Stamp, b: Stamp) = if (a.tick >= b.tick) a else b

    private companion object {
        const val WATCHER = "[BOSS] The Watcher: "
        const val WATCHER_HANDLE = "[BOSS] The Watcher: Let's see how you can handle this."
        const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."
        const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
        const val LASER_CHARGING = "The Energy Laser is charging up!"
        const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
        const val STORM_ENRAGED = "⚠ Storm is enraged! ⚠"
        const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
        const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
        const val GATE_DESTROYED = "The gate has been destroyed!"
        const val CORE_OPENING = "The Core entrance is opening!"
        const val NECRON_ARGH = "[BOSS] Necron: ARGH!"
        val SECTION_DONE = Regex("""^(\w+) (?:activated|completed) a (?:terminal|device|lever)! \((\d+)/(\d+)\)$""")

        val MAXOR_STUN = setOf("[BOSS] Maxor: YOU TRICKED ME!", "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!")
        val STORM_LIGHTNING = setOf("[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!")
        val STORM_CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")
        val NECRON_START = setOf(
            "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
            "[BOSS] Necron: You went further than any human before, congratulations.",
        )

        /** The camp is always 19 mobs: 17 regulars, the Giant and one mini-boss. */
        const val BLOOD_MOBS = 19

        /**
         * Maxor's top crystals come back 41-42 ticks after a hit (less from the stun line when the
         * line itself was held back). His wither goes about 54 ticks after the kill (bedrock to the
         * wither going is 51-55 since Hypixel's boss update, Oct 2026).
         */
        const val CRYSTALS_BACK = 41
        const val MAXOR_DESPAWN = 54
        /**
         * There is no 10 s cooldown since the boss update: the second hit only waits on the crystals
         * (back +41, placed, the laser +27), so hits are at least ~81 ticks apart. A held-back stun
         * line trails its hit by up to ~41: closer than 60 is the same hit.
         */
        const val MIN_HIT_GAP = 60

        /**
         * Storm parks at (102.375, 183, 52.375) and leaves 99 ticks after his lightning line
         * (typically 98-102 since the boss update).
         */
        const val STORM_LEAVES = 99
        const val STORM_SPOT_X = 102.375
        const val STORM_SPOT_Z = 52.375
        /** He switches from his flight to chasing ~2.4 blocks from Yellow's point (46, 65). */
        const val YELLOW_X = 46.0
        const val YELLOW_Z = 65.0
        const val YELLOW_REACHED = 2.4

        /** Necron's mid: leaving it is a scripted sidestep, coming back a teleport to exactly mid. */
        const val OFF_MID = 0.5
        const val ON_MID = 0.05

        // Step indices into [SEQUENCE].
        const val W_DIALOGUE = 0; const val W_WAIT = 1; const val W_CAMP = 2; const val W_CLEAR = 3; const val W_END = 4
        const val M_CRYSTALS = 5; const val M_LURE = 6; const val M_COOLDOWN = 7; const val M_KILL = 8; const val M_ANIMATION = 9
        const val S_OPENING = 10; const val S_CRUSH1 = 11; const val S_PIN = 12; const val S_FLIGHT = 13
        const val S_CRUSH2 = 14; const val S_KILL = 15; const val S_ANIMATION = 16
        const val T_S1 = 17; const val T_S4 = 20; const val T_S3 = 19
        const val G_LEAPS = 21; const val G_KILL = 22
        const val N_INTRO = 23; const val N_TRIP1 = 24; const val N_LOCK1 = 25; const val N_KILL = 26; const val N_END = 27

        /**
         * The steps, in order. Fixed steps (the game's script): Dialogue, Wait, Animation, Opening,
         * Flight, Intro, Lock. The rest are the party's. Storm repeats a name on purpose: it is the
         * second crush that tells you whether the first was slow.
         *
         * Necron (since Hypixel's boss update, Oct 2026): his intro until his sidestep off mid
         * (81-84 ticks), the trip until he is back on it, the lock until ARGH! (said 269-273 ticks
         * in, however late he was back), then the kill, to the run's end on F7 (the fight has no end
         * animation) or the TNT burst he dies in on M7.
         */
        val SEQUENCE: List<Step> = listOf(
            Step(SplitTracker.BLOOD, "&7Dialogue", "watcher.dialogue"), Step(SplitTracker.BLOOD, "&5Wait", "watcher.wait"), Step(SplitTracker.BLOOD, "&cCamp", "watcher.camp"),
            Step(SplitTracker.BLOOD, "&aClear", "watcher.clear"), Step(SplitTracker.BLOOD, null),

            Step(SplitTracker.MAXOR, "&dCrystals", "maxor.crystals"), Step(SplitTracker.MAXOR, "&6Lure", "maxor.lure"), Step(SplitTracker.MAXOR, "&5Cooldown", "maxor.cooldown"),
            Step(SplitTracker.MAXOR, "&cKill", "maxor.kill"), Step(SplitTracker.MAXOR, "&dAnimation", "maxor.animation"),

            Step(SplitTracker.STORM, "&aOpening", "storm.opening"), Step(SplitTracker.STORM, "&6Crush", "storm.crush1"),
            Step(SplitTracker.STORM, "&cPin", "storm.pin"), Step(SplitTracker.STORM, "&bFlight", "storm.flight"),
            Step(SplitTracker.STORM, "&6Crush", "storm.crush2"), Step(SplitTracker.STORM, "&cKill", "storm.kill"),
            Step(SplitTracker.STORM, "&aAnimation", "storm.animation"),

            Step(SplitTracker.TERMS, "&6S1", "terms.s1"), Step(SplitTracker.TERMS, "&6S2", "terms.s2"),
            Step(SplitTracker.TERMS, "&6S3", "terms.s3"), Step(SplitTracker.TERMS, "&6S4", "terms.s4"),

            Step(SplitTracker.GOLDOR, "&5Leaps", "goldor.leaps"), Step(SplitTracker.GOLDOR, "&cKill", "goldor.kill"),

            Step(SplitTracker.NECRON, "&dIntro", "necron.intro"), Step(SplitTracker.NECRON, "&cTrip", "necron.trip1"),
            Step(SplitTracker.NECRON, "&aLock", "necron.lock1"), Step(SplitTracker.NECRON, "&cKill", "necron.kill"),
            Step(SplitTracker.NECRON, null),
        )
    }
}
