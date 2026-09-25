package com.engineerclient.splits

/**
 * What happened inside a split.
 *
 * [SplitTracker] times the big sections of a run — Blood, Watcher, Maxor, Terminals, Goldor and so
 * on. That tells you a section was slow but not why. This file is the other half: it turns a single
 * dungeon chat line into a short label, so the HUD can list the notable moments inside a section
 * next to the time they happened at ("Term 3/7 (Bob)  0:14", "Bob died  0:31").
 *
 * Everything here was written from the 32 recorded F7 runs on this machine: every pattern below was
 * matched against real chat, and the comment above each one is a line that actually arrived, with
 * how many times it turned up across those runs. Nothing is here that was not seen in that data —
 * F7 and M7 are what this team runs, and a pattern nobody can check is worse than no pattern.
 *
 * Two rules keep the labels usable on a HUD. They are short — roughly 24 characters, because they
 * sit beside a timestamp on a line that also has to fit a split name. And when a line names both a
 * player and an item, the player wins: "who picked up the wither key" is the thing you look back at
 * a run and want to know, and "Wither key (Bob)" fits where "Bob has obtained Wither Key" does not.
 *
 * Only dungeon progress gets a label. The recorded chat is mostly not that: party chat, ability
 * cooldowns, damage numbers, sack pickups, "There are blocks in the way!" spam from mining into a
 * wall. All of it returns null and never reaches the HUD.
 *
 * No Minecraft types are touched, so this tests headlessly: hand [label] a plain string.
 */
object SplitEvents {

    /**
     * A short label for a dungeon chat line, or null when the line is not an event worth timing.
     *
     * [line] is plain text with the colour codes already stripped. The first pattern that matches
     * wins, so [RULES] is ordered: anything specific ("wasn't fooled by Willmar", which is the
     * Three Weirdos puzzle) sits above the catch-all for its family.
     */
    fun label(line: String): String? {
        for ((pattern, build) in RULES) {
            val m = pattern.find(line) ?: continue
            return build(m)
        }
        return null
    }

    /**
     * Hypixel writes class milestones with circled digits rather than numbers, and a circled digit
     * is no use on a HUD line that is already tight, so it gets turned back into an ordinary one.
     */
    private const val CIRCLED = "❶❷❸❹❺❻❼❽❾"

    /**
     * Every recognised line, most specific first. Each entry carries the real line it was written
     * from and how often that shape appeared across the recorded runs.
     */
    private val RULES: List<Pair<Regex, (MatchResult) -> String>> = listOf(

        // ---- Terminals: the whole of F7/M7 phase 3 ---------------------------------------------
        // Hypixel numbers these across the section you are in, not per device, so the count in the
        // label is the same one the team is calling out loud. Much the most common dungeon line in
        // the sample, which is what you would expect from four terminal sections a run.
        //   "TheBadOne activated a terminal! (6/8)"   x255
        //   "TheBadOne activated a lever! (4/7)"      x120
        //   "TheBadOne completed a device! (5/7)"      x63
        Regex("""^(\w+) (?:activated|completed) a (terminal|lever|device)! \((\d+)/(\d+)\)$""") to { m ->
            val what = when (m.groupValues[2]) {
                "terminal" -> "Term"
                "lever" -> "Lever"
                else -> "Device"
            }
            "$what ${m.groupValues[3]}/${m.groupValues[4]} (${m.groupValues[1]})"
        },

        // Each terminal section but the last ends on its gate, so this is the cleanest marker of a
        // section boundary there is — and the one SplitsModel itself counts sections with.
        //   "The gate has been destroyed!"   x45
        Regex("""^The gate has been destroyed!$""") to { "Gate destroyed" },

        // Goldor puts the gate back while the section is still running; the countdown says how long
        // the team is stuck for, which is worth seeing when a section looks inexplicably slow.
        //   "The gate will open in 5 seconds!"   x4
        Regex("""^The gate will open in (\d+) seconds?!$""") to { m -> "Gate back (${m.groupValues[1]}s)" },

        // The end of Terminals and the start of Goldor.
        //   "The Core entrance is opening!"   x15
        Regex("""^The Core entrance is opening!$""") to { "Core opening" },

        // ---- Doors and keys --------------------------------------------------------------------
        // Who opened which door is how you reconstruct a clear afterwards: a wither door opening
        // late usually means nobody had the key yet, and the two lines together show that.
        //   "TheBadOne opened a WITHER door!"   x76
        Regex("""^(\w+) opened a WITHER door!$""") to { m -> "Wither door (${m.groupValues[1]})" },

        //   "The BLOOD DOOR has been opened!"   x22
        Regex("""^The BLOOD DOOR has been opened!$""") to { "Blood door open" },

        // Always arrives immediately after the blood door opens — it is the game noticing you have
        // stepped into the blood room, so it times the walk in rather than the door itself.
        //   "A shiver runs down your spine..."   x22
        Regex("""^A shiver runs down your spine\.\.\.$""") to { "Entered blood room" },

        // The rank prefix is optional because unranked players get none. The item name is dropped
        // in favour of the player, per the rule above.
        //   "[MVP+] johnswizzlechang has obtained Wither Key!"   x76
        //   "[MVP+] johnswizzlechang has obtained Blood Key!"    x23
        Regex("""^(?:\[[^\]]+] )?(\w+) has obtained (Wither|Blood) Key!$""") to { m ->
            "${m.groupValues[2]} key (${m.groupValues[1]})"
        },

        // The same pickup as above when the server does not know who got it (it happens when the
        // key drops to someone outside render distance).
        //   "A Wither Key was picked up!"   x2
        Regex("""^A (Wither|Blood) Key was picked up!$""") to { m -> "${m.groupValues[1]} key found" },

        // ---- Blessings -------------------------------------------------------------------------
        // A blessing is a permanent team-wide stat buff for the rest of the run, so when one lands
        // matters: a Power V that arrives after the boss started was worth nothing. Hypixel prints
        // this three ways — named finder, "You", and a finder-less form for the capped tier — and
        // all three are the same event, so they get the same label shape. The tier is the useful
        // half, so the finder is dropped to keep the label short.
        //   "DUNGEON BUFF! johnswizzlechang found a Blessing of Power I!"        x27
        //   "DUNGEON BUFF! You found a Blessing of Life V! (02m 07s)"             x8
        Regex("""^DUNGEON BUFF! \w+ found a Blessing of (\w+) ([IVX]+)!""") to { m ->
            "Blessing: ${m.groupValues[1]} ${m.groupValues[2]}"
        },

        //   "DUNGEON BUFF! A Blessing of Life V was found! (02m 07s)"   x16
        Regex("""^DUNGEON BUFF! A Blessing of (\w+) ([IVX]+) was found!""") to { m ->
            "Blessing: ${m.groupValues[1]} ${m.groupValues[2]}"
        },

        // The item being taken off the ground, a beat or two before the DUNGEON BUFF line that
        // applies it. Kept separate so a slow pickup is visible as a gap between the two.
        //   "A Blessing of Life was picked up!"   x16
        Regex("""^A Blessing of (\w+) was picked up!$""") to { m -> "Bless pickup: ${m.groupValues[1]}" },

        // ---- Deaths and revives ------------------------------------------------------------------
        // Deaths cost score and stall a section, so they are the first thing you look for in a bad
        // split. Hypixel has a family of these — died, died to a mob, killed by a named mob — and
        // the killer's name is usually longer than the space available, so only the victim is kept.
        //   " ☠ You died and became a ghost."                                x4
        //   " ☠ stevenland died and became a ghost."                         x5
        //   " ☠ p3wr was killed by Boomer Parasite and became a ghost."      x2
        //   " ☠ You died to a mob and became a ghost."                       x2
        Regex("""^ ☠ (\w+) (?:died(?: to a mob)?|was killed by .+) and became a ghost\.$""") to { m ->
            "${m.groupValues[1]} died"
        },

        // A disconnect reads as a death to the dungeon but means something completely different to
        // the team, so it gets its own label.
        //   " ☠ bdoggzi disconnected and became a ghost."   x5
        Regex("""^ ☠ (\w+) disconnected and became a ghost\.$""") to { m -> "${m.groupValues[1]} DC'd" },

        //   " ☠ bdoggzi reconnected."   x2
        Regex("""^ ☠ (\w+) reconnected\.$""") to { m -> "${m.groupValues[1]} back" },

        // The reviver is dropped: what you want to see is how long the player was dead for, which
        // is this line's time minus the death's.
        //   " ❣ TheBadOne was revived by p3wr!"   x14
        Regex("""^ ❣ (\w+) was revived by .+!$""") to { m -> "${m.groupValues[1]} revived" },

        // The start of the revive, which is the part that takes the time.
        //   " ❣ p3wr is reviving bdoggzi!"   x9
        Regex("""^ ❣ \w+ is reviving (\w+)!$""") to { m -> "Reviving ${m.groupValues[1]}" },

        //   "Your Revive Stone revived you and broke!"   x2
        Regex("""^Your Revive Stone revived you and broke!$""") to { "Revive stone used" },

        // ---- Puzzles -----------------------------------------------------------------------------
        // Hypixel never names the puzzle, it describes what you did, so the puzzle has to be worked
        // out from the wording. "Wasn't fooled by <name>" is Three Weirdos — the name is whichever
        // of the three NPCs was lying that run, so it is not worth showing.
        //   "PUZZLE SOLVED! TurtleGuy240 wasn't fooled by Willmar! Good job!"   x9 (across NPCs)
        Regex("""^PUZZLE SOLVED! (\w+) wasn't fooled by \w+! Good job!$""") to { m ->
            "3 Weirdos (${m.groupValues[1]})"
        },

        //   "PUZZLE SOLVED! TurtleGuy240 tied Tic Tac Toe! Good job!"   x4
        Regex("""^PUZZLE SOLVED! (\w+) tied Tic Tac Toe! Good job!$""") to { m ->
            "Tic Tac Toe (${m.groupValues[1]})"
        },

        //   "PUZZLE FAIL! p3wr killed a Blaze in the wrong order! Yikes!"   x1
        Regex("""^PUZZLE FAIL! (\w+) killed a Blaze in the wrong order! Yikes!$""") to { m ->
            "Blaze FAIL (${m.groupValues[1]})"
        },

        // The catch-alls, for the puzzles that did roll in the recorded runs but too rarely to
        // write a rule of their own for. Anything announcing itself in the PUZZLE SOLVED / PUZZLE
        // FAIL shape lands here rather than being missed.
        Regex("""^PUZZLE SOLVED! (\w+) .*$""") to { m -> "Puzzle done (${m.groupValues[1]})" },
        Regex("""^PUZZLE FAIL! (\w+) .*$""") to { m -> "Puzzle FAIL (${m.groupValues[1]})" },

        // ---- Oruo's quiz ---------------------------------------------------------------------------
        // The quiz room is three questions and a reward, and a wrong answer costs the whole party
        // its buff, so each question landing is worth a mark.
        //   "[STATUE] Oruo the Omniscient: TurtleGuy240 answered Question #2 correctly!"   x8
        Regex("""^\[STATUE] Oruo the Omniscient: (\w+) answered Question #(\d+) correctly!$""") to { m ->
            "Quiz ${m.groupValues[2]} (${m.groupValues[1]})"
        },

        //   "[STATUE] Oruo the Omniscient: TurtleGuy240 answered the final question correctly!"   x4
        Regex("""^\[STATUE] Oruo the Omniscient: (\w+) answered the final question correctly!$""") to { m ->
            "Quiz done (${m.groupValues[1]})"
        },

        // Oruo handing over the buff: the quiz room is finished at this line, not the answer above.
        //   "[STATUE] Oruo the Omniscient: I bestow upon you all the power of a hundred years!"   x4
        Regex("""^\[STATUE] Oruo the Omniscient: I bestow upon you all the power""") to { "Quiz reward" },

        // ---- The Wither King crystals (end of F7/M7) ------------------------------------------------
        // After Necron dies the team carries energy crystals to the pillars. Both halves are timed:
        // the pickup, and each crystal going live.
        //   "bdoggzi picked up an Energy Crystal!"   x60
        Regex("""^(\w+) picked up an Energy Crystal!$""") to { m -> "Crystal (${m.groupValues[1]})" },

        //   "1/2 Energy Crystals are now active!"   x60
        Regex("""^(\d+)/(\d+) Energy Crystals are now active!$""") to { m ->
            "Crystal ${m.groupValues[1]}/${m.groupValues[2]} live"
        },

        //   "The Energy Laser is charging up!"   x30
        Regex("""^The Energy Laser is charging up!$""") to { "Laser charging" },

        // ---- Secrets and bonuses ----------------------------------------------------------------
        // The bonus essence from a secret chest — the only per-secret line Hypixel actually sends,
        // so it is the closest thing to a secret timestamp available.
        //   "MrCapybara96 found a Wither Essence! Everyone gains an extra essence!"   x50
        Regex("""^(\w+) found a Wither Essence!""") to { m -> "Bonus essence (${m.groupValues[1]})" },

        // A death that did not happen. Worth a mark because it explains a sudden stall.
        //   "Second Wind Activated! Your Spirit Mask saved your life!"   x8
        Regex("""^Second Wind Activated!""") to { "Spirit mask saved" },

        // ---- Class milestones --------------------------------------------------------------------
        // Each class hits numbered milestones as it does its job; how far into a split the first
        // few land is a rough read on whether that player was contributing yet.
        //   "Berserk Milestone ❶: You have dealt 750,000 Total Damage so far! 01s"   x14
        //   "Tank Milestone ❾: You have tanked and dealt 135,000,000 Total Damage so far! 03m 07s"
        Regex("""^(\w+) Milestone (.): You have """) to { m ->
            val n = CIRCLED.indexOf(m.groupValues[2][0]) + 1
            "${m.groupValues[1]} MS $n"
        },

        // ---- Run start and end ----------------------------------------------------------------
        // Mort handing over the map is the first thing that happens in a run; SplitsModel starts
        // its clock here too, so the event and the split agree.
        //   "[NPC] Mort: Here, I found this map when I first entered the dungeon."   x28
        Regex("""^\[NPC] Mort: Here, I found this map""") to { "Run start" },

        // The EXTRA STATS screen. The boss time it quotes is Hypixel's own, which is handy to keep
        // beside the mod's, since the two are measured slightly differently.
        //   "     ☠ Defeated Maxor, Storm, Goldor, and Necron in 07m 02s"   x30
        Regex("""^\s*☠ Defeated .+ in (.+)$""") to { m -> "Boss done ${m.groupValues[1]}" },

        //   "                            Team Score: 305 (S+)"   x28
        Regex("""^\s*Team Score: (\d+) \((\S+)\)$""") to { m ->
            "Score ${m.groupValues[1]} (${m.groupValues[2]})"
        },

        // ---- Boss dialogue beats: F7 / M7 -------------------------------------------------------
        // Necron's four phases each open on a line of dialogue. SplitsModel uses these same lines to
        // start its splits, so they are listed here as well: on the sub-split HUD they mark the
        // moment the phase actually changed, which is not always where the split boundary landed.
        //   "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"   x15
        Regex("""^\[BOSS] Maxor: WELL! WELL! WELL!""") to { "Maxor start" },

        // Enrage is the wither going berserk at low health — the point the phase becomes dangerous.
        //   "⚠ Maxor is enraged! ⚠"   x15
        //   "⚠ Storm is enraged! ⚠"   x15
        Regex("""^⚠ (\w+) is enraged! ⚠$""") to { m -> "${m.groupValues[1]} enraged" },

        //   "[BOSS] Storm: Pathetic Maxor, just like expected."   x15
        Regex("""^\[BOSS] Storm: Pathetic Maxor""") to { "Storm start" },

        //   "[BOSS] Storm: I should have known that I stood no chance."   x15
        Regex("""^\[BOSS] Storm: I should have known""") to { "Storm dead" },

        //   "[BOSS] Goldor: Who dares trespass into my domain?"   x15
        Regex("""^\[BOSS] Goldor: Who dares trespass""") to { "Goldor greets" },

        // Goldor conceding the terminals — the phase is won at this line.
        //   "[BOSS] Goldor: You have done it, you destroyed the factory..."   x10
        Regex("""^\[BOSS] Goldor: You have done it""") to { "Factory destroyed" },

        //   "[BOSS] Goldor: Necron, forgive me."   x15
        Regex("""^\[BOSS] Goldor: Necron, forgive me""") to { "Goldor dead" },

        //   "[BOSS] Necron: You went further than any human before, congratulations."   x15
        Regex("""^\[BOSS] Necron: You went further""") to { "Necron start" },

        // Necron flattening the arena between phases — everyone has to reposition, so it explains a
        // lull in damage.
        //   "[BOSS] Necron: Let's make some space!"   x15
        Regex("""^\[BOSS] Necron: Let's make some space!$""") to { "Necron clears arena" },

        //   "[BOSS] Necron: All this, for nothing..."   x15
        Regex("""^\[BOSS] Necron: All this, for nothing""") to { "Wither King start" },

        // ---- The Watcher ------------------------------------------------------------------------
        // The Watcher throws waves of mobs at you and taunts on each one, with the taunt picked at
        // random from a set. The whole set is here as one pattern because the wording carries no
        // information — only that another wave just landed.
        //   "[BOSS] The Watcher: Go and live again!"          x31
        //   "[BOSS] The Watcher: Hmmm... this one!"           x30
        //   "[BOSS] The Watcher: You'll do."                  x29
        //   "[BOSS] The Watcher: Go, fight!"                  x29
        //   "[BOSS] The Watcher: This guy looks like a fighter."   x27
        //   "[BOSS] The Watcher: Aw, I liked that one."       x26
        //   "[BOSS] The Watcher: Very nice."                  x26
        //   "[BOSS] The Watcher: Not bad."                    x21
        //   "[BOSS] The Watcher: I'm impressed."              x20
        //   "[BOSS] The Watcher: That one was weak anyway."   x19
        Regex(
            """^\[BOSS] The Watcher: (?:Go and live again!|Hmmm\.\.\. this one!|You'll do\.|Go, fight!""" +
                """|This guy looks like a fighter\.|Aw, I liked that one\.|Very nice\.|Not bad\.""" +
                """|I'm impressed\.|That one was weak anyway\.)$"""
        ) to { "Watcher wave" },

        // The last wave, and then the door. SplitsModel ends its Watcher Dialog split on the first
        // of these and the whole Watcher split on the second.
        //   "[BOSS] The Watcher: Let's see how you can handle this."   x17
        Regex("""^\[BOSS] The Watcher: Let's see how you can handle this\.$""") to { "Watcher final wave" },

        //   "[BOSS] The Watcher: You have proven yourself. You may pass."   x15
        Regex("""^\[BOSS] The Watcher: You have proven yourself""") to { "Watcher cleared" },

    )
}
