package com.engineerclient.splits

/**
 * The boss broken down into its 25 moments — Maxor's stuns, Storm's crushes, the four terminal
 * sections, the leap into the core, Necron's mids — each timed on both clocks.
 *
 * This is a port of the team's own ChatTriggers module (undonecoffee/allModules,
 * `made by us/EngineerClient/features/EngineerSubSplits.js`): the same 25 steps in the same order
 * with the same colours, advanced by the same lines. What changed is the presentation — they are
 * [Split]s here, so they render exactly like the run's normal splits instead of in that module's
 * own `Move > 8.12s (8.00s)` style.
 *
 * A step runs until the next one starts, so the whole sequence is a stopwatch that gets handed on
 * rather than 25 separate timers. Three things move it along:
 *
 *  - a boss line (most of them),
 *  - the terminal/gate interplay, where a section ends on whichever of "last device done" and
 *    "gate destroyed" arrives second — they can come in either order,
 *  - and two waits on the server's own tick count, for the moments Hypixel never announces: Maxor
 *    starting to move (when he can't be seen doing it), and Storm's first lightning.
 *
 * Nothing here touches Minecraft. What it cannot work out for itself needs the world: Maxor's
 * wither starting to move ([onMaxorMoved]) and the party all being in the core
 * ([onEveryoneInCore]); the module feeds those in.
 */
class SubSplitTracker {

    /** Which split a step belongs to, and how it reads on the HUD. */
    private class Step(val split: String, val label: String)

    private val steps = SEQUENCE
    private val starts = arrayOfNulls<Stamp>(SEQUENCE.size)
    /** How each step's start was found, for Debug: a chat line, a timed wait, the core box. */
    private val sources = arrayOfNulls<String>(SEQUENCE.size)
    /** The line that armed the running timed wait. */
    private var armedBy = ""

    /** 0 before the boss starts, otherwise the 1-based step being timed. */
    private var current = 0
    private var last: Stamp? = null

    /** Server ticks since the current step began — the two timed waits below count on this. */
    private var ticks = 0
    /** Where a timed wait started counting: the line that armed it, not the step's own start. */
    private var watchFrom: Stamp? = null
    private var stormCrushes = 0
    private var laserWaitDone = false

    /** The gate and the last device can arrive in either order; a section ends on the second. */
    private var gateBlown = false
    private var gateWaiting = false

    /** Set when the team starts leaping in, so the core-entry watch knows to run. */
    var watchingCore = false
        private set

    fun reset() {
        java.util.Arrays.fill(starts, null)
        java.util.Arrays.fill(sources, null)
        armedBy = ""
        current = 0; last = null; ticks = 0; watchFrom = null
        stormCrushes = 0; laserWaitDone = false
        gateBlown = false; gateWaiting = false; watchingCore = false
    }

    /** The steps of one split, in order, each running until the next one starts. */
    fun forSplit(split: String): List<Split> {
        val out = mutableListOf<Split>()
        steps.forEachIndexed { i, step ->
            if (step.split != split) return@forEachIndexed
            val start = starts[i] ?: return@forEachIndexed
            val stop = (i + 1 until starts.size).firstNotNullOfOrNull { starts[it] }
            out += Split(step.label, start, stop)
        }
        return out
    }

    /**
     * For Debug: how each of [split]'s steps (in [forSplit]'s order) came to an end - how the next
     * step's start was found - or "running". A step can end on a later split's first line when the
     * moments between were never seen; that shows here too.
     */
    fun endSources(split: String): List<String> {
        val out = mutableListOf<String>()
        steps.forEachIndexed { i, step ->
            if (step.split != split || starts[i] == null) return@forEachIndexed
            val next = (i + 1 until starts.size).firstOrNull { starts[it] != null }
            out += when {
                next == null -> "running"
                next != i + 1 -> (sources[next] ?: "?") + " - the steps between were never seen"
                else -> sources[next] ?: "?"
            }
        }
        return out
    }

    /** How [split]'s first step started, for Debug. */
    fun startSource(split: String): String? =
        steps.indices.firstOrNull { steps[it].split == split && starts[it] != null }?.let { sources[it] }

    /** Whether any split has steps yet — the HUDs fall back to chat events until it does. */
    fun started(): Boolean = current > 0

    /** Odin's server tick. The two waits below are the only reason this class counts them. */
    fun onServerTick() {
        ticks++
        // Maxor starts moving 46 ticks after "DON'T DISAPPOINT ME" (seen, when he is in view - this
        // is the fallback), and Storm's first lightning lands about thirty-four seconds in. Hypixel
        // says nothing either time, so without seeing it the only way to split there is to count.
        val from = watchFrom ?: return
        val after = { n: Int -> Stamp(from.realMs + n * 50L, from.tick + n) }
        if (ticks >= MAXOR_MOVE_GIVE_UP && !laserWaitDone) {
            laserWaitDone = true; watchFrom = null
            advance(after(MAXOR_MOVE_TICKS), "$MAXOR_MOVE_TICKS server ticks after $armedBy - he wasn't seen moving, so counted")
        } else if (ticks >= STORM_LIGHTNING_TICKS && stormCrushes == 0) {
            watchFrom = null
            advance(after(STORM_LIGHTNING_TICKS), "$STORM_LIGHTNING_TICKS server ticks after $armedBy - never announced, so counted")
        }
    }

    /**
     * Maxor's wither gone during his last DPS: he is dead. He says "I'M TOO YOUNG TO DIE AGAIN!" as
     * he dies in some runs only (11 of 27 recorded) - it is the moment when said, his wither going
     * 3-13 ticks later - so without it the wither going is what ends the DPS and starts his
     * animation. Before that step, a wither going is just him leaving view.
     */
    fun onMaxorDead(at: Stamp) {
        if (current == 5) advance(at, "his wither going - the death line wasn't said, so a few ticks after the kill")
    }

    /** Maxor's intro is over and he is about to start moving: the module watches his wither for it. */
    val waitingForMaxorMove: Boolean get() = current == 1 && !laserWaitDone && watchFrom != null

    /** Maxor's wither seen starting to move: Move is over. */
    fun onMaxorMoved(at: Stamp) {
        if (!waitingForMaxorMove) return
        laserWaitDone = true; watchFrom = null
        advance(at, "his wither seen starting to move")
    }

    /** The party is all inside the core ([how] it was told): the leap is over and Goldor's kill begins. */
    fun onEveryoneInCore(at: Stamp, how: String) {
        if (!watchingCore) return
        watchingCore = false
        advance(at, how)
    }

    fun onChat(msg: String, at: Stamp) {
        when {
            msg == MAXOR_START -> { reset(); jumpTo(1, at, said(msg)) }
            current == 0 -> return

            // A jump rather than a step, so a missed line earlier cannot leave the rest misaligned.
            msg == STORM_START -> jumpTo(7, at, said(msg))
            msg == GOLDOR_START -> jumpTo(13, at, said(msg))
            msg in NECRON_START -> { watchingCore = false; jumpTo(19, at, said(msg)) }

            // The lines that arm a timed wait: Maxor's intro ends, Storm calls its lightning.
            msg in ARMS_WAIT -> { watchFrom = at; ticks = 0; armedBy = said(msg) }

            // Storm takes a crush. Four of them and it is dead, so the fifth is not a new step.
            msg in STORM_CRUSHED -> {
                ticks = 0
                if (stormCrushes <= 3) { stormCrushes++; advance(at, said(msg)) }
            }

            msg in ADVANCES -> advance(at, said(msg))

            msg == GATE_DESTROYED -> if (gateWaiting) advance(at, "the gate destroyed, after the last device") else gateBlown = true

            else -> {
                val m = SECTION_DONE.find(msg) ?: return
                // Only the last device of a section closes it.
                if (m.groupValues[2] != m.groupValues[3]) return
                when (current) {
                    15 -> watchingCore = true   // S3 done: the team starts moving to the core
                    16 -> { advance(at, "the last device (${m.groupValues[3]}/${m.groupValues[3]}) - no gate after S4"); return }
                }
                if (gateBlown) advance(at, "the last device (${m.groupValues[3]}/${m.groupValues[3]}), after the gate") else gateWaiting = true
            }
        }
    }

    private fun advance(at: Stamp, source: String) = jumpTo(current + 1, at, source)

    /** A chat line as a source: `"YOU TRICKED ME!"`, the speaker left off. */
    private fun said(msg: String) = "\"" + msg.substringAfter(": ").let { if (it.length > 32) it.take(30) + "..." else it } + "\""

    private fun jumpTo(step: Int, at: Stamp, source: String) {
        if (step > steps.size) return
        ticks = 0
        watchFrom = null
        gateBlown = false
        gateWaiting = false
        current = step
        last = at
        starts[step - 1] = at
        sources[step - 1] = source
    }

    private companion object {
        const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
        const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
        const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
        const val GATE_DESTROYED = "The gate has been destroyed!"
        val SECTION_DONE = Regex("""^(\w+) (?:activated|completed) a (?:terminal|device|lever)! \((\d+)/(\d+)\)$""")

        /**
         * Maxor starts moving 46 server ticks (2.3 s) after "DON'T DISAPPOINT ME" - 45 to 51 in
         * every recorded run he was in view for. His wither moving is what ends Move; this count is
         * only for when he can't be seen, given up on after [MAXOR_MOVE_GIVE_UP].
         */
        const val MAXOR_MOVE_TICKS = 46
        const val MAXOR_MOVE_GIVE_UP = 60

        /** About 34.4s: Storm's first lightning, the end of its opening animation. */
        const val STORM_LIGHTNING_TICKS = 688

        val NECRON_START = setOf(
            "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
            "[BOSS] Necron: You went further than any human before, congratulations.",
        )

        val ARMS_WAIT = setOf(
            "[BOSS] Maxor: DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE.",
            "[BOSS] Storm: ENERGY HEED MY CALL!",
            "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!",
        )

        val STORM_CRUSHED = setOf("[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!")

        /** Every other line that simply hands the stopwatch to the next step. */
        val ADVANCES = setOf(
            "[BOSS] Maxor: YOU TRICKED ME!",
            "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!",
            "⚠ Maxor is enraged! ⚠",
            "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!",

            "[BOSS] Storm: I should have known that I stood no chance.",
            "[BOSS] Storm: THAT WAS ONLY IN MY WAY!",
            "[BOSS] Storm: Slowing me down will be your greatest accomplishment!",
            "[BOSS] Storm: This factory is too small for me!",
            "[BOSS] Storm: BEGONE PILLAR!",
            "[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself.",
            "[BOSS] Necron: Sometimes when you have a problem, you just need to destroy it all and start again.",
            "[BOSS] Necron: WITNESS MY RAW NUCLEAR POWER!",
            "[BOSS] Necron: ARGH!",
            "[BOSS] Necron: Let's make some space!",
            "[BOSS] Necron: All this, for nothing...",
        )

        /**
         * The 25 steps, in order, with the colours the ChatTriggers module used. Two phases repeat
         * a name on purpose — Maxor is stunned twice and Storm is crushed twice before it dies —
         * and the repeat is the point: it is the second one that tells you whether the first was
         * slow.
         */
        val SEQUENCE: List<Step> = listOf(
            Step(SplitTracker.MAXOR, "&6Move"), Step(SplitTracker.MAXOR, "&5Stun"), Step(SplitTracker.MAXOR, "&cDps"),
            Step(SplitTracker.MAXOR, "&5Stun"), Step(SplitTracker.MAXOR, "&cDps"), Step(SplitTracker.MAXOR, "&dAnimation"),

            Step(SplitTracker.STORM, "&aAnimation"), Step(SplitTracker.STORM, "&6Crush"), Step(SplitTracker.STORM, "&cDps"),
            Step(SplitTracker.STORM, "&6Crush"), Step(SplitTracker.STORM, "&cDps"), Step(SplitTracker.STORM, "&aAnimation"),

            Step(SplitTracker.TERMS, "&6S1"), Step(SplitTracker.TERMS, "&6S2"),
            Step(SplitTracker.TERMS, "&6S3"), Step(SplitTracker.TERMS, "&6S4"),

            Step(SplitTracker.GOLDOR, "&5Leaps"), Step(SplitTracker.GOLDOR, "&cKill"),

            Step(SplitTracker.NECRON, "&dAnimation"), Step(SplitTracker.NECRON, "&aMid"), Step(SplitTracker.NECRON, "&cDps"),
            Step(SplitTracker.NECRON, "&cDps"), Step(SplitTracker.NECRON, "&aMid"), Step(SplitTracker.NECRON, "&cDps"),
            Step(SplitTracker.NECRON, "&dAnimation"),
        )
    }
}
