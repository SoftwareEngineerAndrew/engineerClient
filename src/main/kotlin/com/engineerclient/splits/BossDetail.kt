package com.engineerclient.splits

/**
 * The detail behind the Watcher, Portal and the four boss phases: every moment of them the game
 * actually tells us about, for the "Everything" setting.
 *
 * What is here is what Hypixel says out loud and what the world can be watched for. Several things
 * that were asked for are NOT here, and the reason is always the same one — nothing reports them:
 *
 *  - **Who killed a mob, and when the last one died.** Minecraft sends no death message for mobs
 *    and no damage attribution to other players, and Odin has none either. So "time to kill the
 *    mob {player}" cannot be done from chat; the closest honest thing is watching the mob entity
 *    disappear, which [DungeonSplits] feeds in through [onMobGone] — it knows *when*, never *who*.
 *  - **Who hit Goldor, and each hit.** Same reason. Goldor takes damage silently; there is no
 *    per-hit line and no attribution. Only his death changes anything anyone can see.
 *  - **Storm's crusher heights and who moved one.** The crushers are moved by standing on a plate;
 *    nothing is announced and the block movement is not attributable to a player.
 *  - **Each button press on Select All.** Terminal clicks are inventory clicks inside a GUI. The
 *    server announces the terminal *finishing*, never a single click, so the presses inside one
 *    cannot be timed from chat.
 *
 * Those are left out rather than approximated. A number that looks like a measurement but is a
 * guess is worse than a gap, because a gap is obvious and a guess is not.
 */
class BossDetail(private val detail: SplitDetail) {

    private var watcherWaves = 0
    private var crystalsPlaced = 0

    fun reset() {
        watcherWaves = 0
        crystalsPlaced = 0
    }

    /**
     * A mob the module was watching has gone. Used for the Watcher's waves, where what matters is
     * how long a wave stayed up rather than who cleared it.
     */
    fun onMobGone(split: String, at: Stamp, name: String) = detail.add(split, at, "Gone: $name")

    /** A mob spawned in the blood room. */
    fun onMobSpawn(split: String, at: Stamp, name: String) = detail.add(split, at, "Spawn: $name")

    /** The exit portal appearing, which the module spots as a block change. */
    fun onPortalOpen(at: Stamp) = detail.add(SplitTracker.PORTAL, at, "Portal open")

    /** You stepping into the portal — the boss loading is what actually ends the clear. */
    fun onPortalEntered(at: Stamp) = detail.add(SplitTracker.PORTAL, at, "Portal entered")

    fun onChat(msg: String, at: Stamp) {
        // The Watcher: its taunts are the waves. Each one is a wave landing, and the blessing ends
        // the fight, so numbering them gives the shape of the fight without needing mob kills.
        when {
            WATCHER_WAVE.matches(msg) -> {
                watcherWaves++
                detail.add(SplitTracker.WATCHER, at, "Wave $watcherWaves")
            }
            msg == WATCHER_DIALOG_END -> detail.add(SplitTracker.WATCHER, at, "Final wave")
            msg == WATCHER_DONE -> {
                detail.add(SplitTracker.WATCHER, at, "Watcher cleared")
                // The portal is what the blessing opens, so this is also the Portal split's start.
                detail.add(SplitTracker.PORTAL, at, "Blessed")
            }
        }

        // Maxor and Storm. Their dialogue is the only thing that marks a stun, a crush or a phase
        // ending; there is nothing else to read.
        when (msg) {
            // The boss loading is what actually ends the clear, so Maxor's first line is the
            // moment the portal was gone through.
            "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> {
                detail.add(SplitTracker.PORTAL, at, "Portal entered")
                detail.add(MAXOR, at, "Maxor spawns")
            }
            MAXOR_INTRO_END -> detail.add(MAXOR, at, "Intro done, shield up")
            "[BOSS] Maxor: YOU TRICKED ME!", "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!" ->
                detail.add(MAXOR, at, "Stunned by laser")
            "⚠ Maxor is enraged! ⚠" -> detail.add(MAXOR, at, "Enraged")
            "[BOSS] Maxor: I'M TOO YOUNG TO DIE AGAIN!", "[BOSS] Maxor: I'LL MAKE YOU REMEMBER MY DEATH!!" ->
                detail.add(MAXOR, at, "Maxor killed")

            "[BOSS] Storm: ENERGY HEED MY CALL!", "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!" ->
                detail.add(STORM, at, "Lightning called")
            "[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!" -> detail.add(STORM, at, "Storm crushed")
            "[BOSS] Storm: THAT WAS ONLY IN MY WAY!",
            "[BOSS] Storm: Slowing me down will be your greatest accomplishment!",
            "[BOSS] Storm: This factory is too small for me!",
            "[BOSS] Storm: BEGONE PILLAR!" -> detail.add(STORM, at, "Pillar destroyed")
            "[BOSS] Storm: I should have known that I stood no chance." -> detail.add(STORM, at, "Storm killed")

            "The Core entrance is opening!" -> detail.add(TERMINALS, at, "Core opening")
            "The gate has been destroyed!" -> detail.add(TERMINALS, at, "Gate destroyed")

            // Goldor dies silently; the only thing that marks it is Necron starting to speak.
            "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
            "[BOSS] Necron: You went further than any human before, congratulations." ->
                detail.add(GOLDOR, at, "Goldor dead")
        }

        // Terminals: every device the party finishes, with who and the running count. Hypixel
        // numbers these across the section, so the count here is the one being called out loud.
        SECTION_DONE.find(msg)?.let { m ->
            val what = when (m.groupValues[2]) {
                "terminal" -> "Term"
                "lever" -> "Lever"
                else -> "Device"
            }
            detail.add(TERMINALS, at, "$what ${m.groupValues[3]}/${m.groupValues[4]} (${m.groupValues[1]})")
        }

        // Necron's fight, and the Wither King phase that runs on inside it. The crystals are the
        // one part of this dungeon where Hypixel does announce who did what.
        CRYSTAL_PICKUP.find(msg)?.let { detail.add(NECRON, at, "Crystal picked (${it.groupValues[1]})") }
        CRYSTAL_ACTIVE.find(msg)?.let { m ->
            crystalsPlaced++
            detail.add(NECRON, at, "Crystal ${m.groupValues[1]}/${m.groupValues[2]} placed")
        }
        when (msg) {
            "[BOSS] Necron: ARGH!", "[BOSS] Necron: Let's make some space!" -> detail.add(NECRON, at, "Necron mid")
            "[BOSS] Necron: WITNESS MY RAW NUCLEAR POWER!" -> detail.add(NECRON, at, "Nuclear power")
            "[BOSS] Necron: All this, for nothing..." -> detail.add(NECRON, at, "Necron dead")
        }
    }

    private companion object {
        const val MAXOR = "&5Maxor"
        const val STORM = "&9Storm"
        const val TERMINALS = "&6Terminals"
        const val GOLDOR = "&8Goldor"
        const val NECRON = "&4Necron"

        const val MAXOR_INTRO_END = "[BOSS] Maxor: DON'T DISAPPOINT ME, I HAVEN'T HAD A GOOD FIGHT IN A WHILE."
        const val WATCHER_DIALOG_END = "[BOSS] The Watcher: Let's see how you can handle this."
        const val WATCHER_DONE = "[BOSS] The Watcher: You have proven yourself. You may pass."

        /**
         * The Watcher's between-wave taunts. All ten were counted in the recorded runs; the three
         * least obvious ("Hmmm... this one!", "Very nice.", "This guy looks like a fighter.") are
         * as common as the rest, so leaving them out would have dropped a third of the waves.
         */
        val WATCHER_WAVE = Regex(
            "^\\[BOSS] The Watcher: (?:Not bad\\.|Aw, I liked that one\\.|You'll do\\.|" +
                "That one was weak anyway\\.|I'm impressed\\.|Go, fight!|Go and live again!|" +
                "Hmmm\\.\\.\\. this one!|Very nice\\.|This guy looks like a fighter\\.)$"
        )

        val SECTION_DONE = Regex("""^(\w+) (?:activated|completed) a (terminal|lever|device)! \((\d+)/(\d+)\)$""")
        val CRYSTAL_PICKUP = Regex("""^(\w+) picked up an Energy Crystal!$""")
        val CRYSTAL_ACTIVE = Regex("""^(\d+)/(\d+) Energy Crystals are now active!$""")
    }
}
