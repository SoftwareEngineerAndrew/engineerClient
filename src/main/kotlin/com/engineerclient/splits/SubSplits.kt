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
 *    dropping his shield, and Storm's first lightning.
 *
 * Nothing here touches Minecraft. The one thing it cannot work out for itself is when the party has
 * finished leaping into the core, which needs player positions; the module feeds that in through
 * [onEveryoneInCore].
 */
class SubSplitTracker {

    /** Which split a step belongs to, and how it reads on the HUD. */
    private class Step(val split: String, val label: String)

    private val steps = SEQUENCE
    private val starts = arrayOfNulls<Stamp>(SEQUENCE.size)

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

    /** Whether any split has steps yet — the HUDs fall back to chat events until it does. */
    fun started(): Boolean = current > 0

    /** Odin's server tick. The two waits below are the only reason this class counts them. */
    fun onServerTick() {
        ticks++
        // Maxor's shield drops on its own about eight seconds after his intro, and Storm's first
        // lightning lands about thirty-four seconds in. Hypixel says nothing either time, so the
        // only way to split there is to count.
        val from = watchFrom ?: return
        val after = { n: Int -> Stamp(from.realMs + n * 50L, from.tick + n) }
        if (ticks >= MAXOR_SHIELD_TICKS && !laserWaitDone) {
            laserWaitDone = true; watchFrom = null
            advance(after(MAXOR_SHIELD_TICKS))
        } else if (ticks >= STORM_LIGHTNING_TICKS && stormCrushes == 0) {
            watchFrom = null
            advance(after(STORM_LIGHTNING_TICKS))
        }
    }

    /** The party is all inside the core: the leap is over and Goldor's kill begins. */
    fun onEveryoneInCore(at: Stamp) {
        if (!watchingCore) return
        watchingCore = false
        advance(at)
    }

    fun onChat(msg: String, at: Stamp) {
        when {
            msg == MAXOR_START -> { reset(); jumpTo(1, at) }
            current == 0 -> return

            // A jump rather than a step, so a missed line earlier cannot leave the rest misaligned.
            msg == STORM_START -> jumpTo(7, at)
            msg == GOLDOR_START -> jumpTo(13, at)
            msg in NECRON_START -> { watchingCore = false; jumpTo(19, at) }

            // The lines that arm a timed wait: Maxor's intro ends, Storm calls its lightning.
            msg in ARMS_WAIT -> { watchFrom = at; ticks = 0 }

            // Storm takes a crush. Four of them and it is dead, so the fifth is not a new step.
            msg in STORM_CRUSHED -> {
                ticks = 0
                if (stormCrushes <= 3) { stormCrushes++; advance(at) }
            }

            msg in ADVANCES -> advance(at)

            msg == GATE_DESTROYED -> if (gateWaiting) advance(at) else gateBlown = true

            else -> {
                val m = SECTION_DONE.find(msg) ?: return
                // Only the last device of a section closes it.
                if (m.groupValues[2] != m.groupValues[3]) return
                when (current) {
                    15 -> watchingCore = true   // S3 done: the team starts moving to the core
                    16 -> { advance(at); return }
                }
                if (gateBlown) advance(at) else gateWaiting = true
            }
        }
    }

    private fun advance(at: Stamp) = jumpTo(current + 1, at)

    private fun jumpTo(step: Int, at: Stamp) {
        if (step > steps.size) return
        ticks = 0
        watchFrom = null
        gateBlown = false
        gateWaiting = false
        current = step
        last = at
        starts[step - 1] = at
    }

    private companion object {
        const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
        const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
        const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
        const val GATE_DESTROYED = "The gate has been destroyed!"
        val SECTION_DONE = Regex("""^(\w+) (?:activated|completed) a (?:terminal|device|lever)! \((\d+)/(\d+)\)$""")

        /** About 8.3s: Maxor's shield is down and the laser stun is up. */
        const val MAXOR_SHIELD_TICKS = 166

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
            "[BOSS] Maxor: I'LL MAKE YOU REMEMBER MY DEATH!!",
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
