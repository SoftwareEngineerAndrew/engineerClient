package com.engineerclient.p3sim

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.ARCHER
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.BERSERK
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.HEALER
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.MAGE
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.TANK
import net.minecraft.world.phys.Vec3

/**
 * The skill presets: who does what (a role per class) and when each thing is done. Edit them here.
 *
 * **Roles**: one line per class, its four sections split by `/`, in the usual shorthand:
 * - digits: terminals, in the order done (`43` = T4 then T3)
 * - `ss`: Simon Says (S1's device); `i4`: the S4 device (done in S1); `dev`: the section's device
 * - `ll` `rl` `bl`: left, right, both levers (which is which: [LEFT])
 * - `ee2` `ee3`: early enter into S2 / S3; `core`: into the core (after their other jobs)
 * - anything else (`recore`, `(leap to 2nd term)`) is a note, ignored.
 *
 * A job in two roles is a stack: if one of them is yours it's always yours; otherwise the first bot
 * listed does it. A gate goes to whoever has the section's `ll`/`bl`.
 *
 * **Times**: one line per section, `|`-separated groups of "jobs seconds" (seconds into that
 * section, when it's done). Jobs as in the roles, plus `terms`, `levers`, `gate`, and `lights` /
 * `arrows` / `i4` for a later section's device done early. A bot with several jobs at one time
 * does them evenly spread since its previous one.
 *
 * **Moves**: one line per section, `|`-separated:
 * - `i4 leaps 8.1`: the i4 bot leaps back into the section at 8.1 s
 * - `ee2 spot 11.2`: the ee2 bot is on its spot by 11.2 s (it walks there after its last job)
 * - `leap ee2 12.1`: everyone free pre-leaps onto the ee2 bot at 12.1 s (onto you as soon as you're
 *   there, if it's yours); the others right after their last job
 * - `hold 2`: the next section's 2nd-terminal bot waits on the early-enter spot (doesn't walk on)
 * - `ee3 waits 1 2 3 4`: the ee3 bot stays on its spot until those have leapt onto it (the next
 *   section's terminals by number, or `ee3` / `core`...: whoever does that); without it, until
 *   everyone free has
 * Bots that pre-leapt walk on to their next terminals; at the core, everyone leaps in at once.
 *
 * **Spots**: early-enter spots this preset stands on instead of the menu's (`ee2`, `ee3`, `core`).
 */
object Roles {
    class Preset(val name: String, val roles: Map<DungeonClass, String>, val times: List<String>, val moves: List<String>, val helps: List<Help> = emptyList(), val spots: Map<String, Vec3> = emptyMap())

    /**
     * Help on a stack (the menu's Helper): when you're on [yours] (that terminal and the stack's
     * jobs [jobs]), whoever does [by] gets [jobs] done by [at] s into section [section] too
     * (whoever's first).
     */
    class Help(val section: Int, val yours: String, val by: String, val jobs: List<String>, val at: Double)

    // Normal PF and Quality PF as the user plays them (2026-10-09). Roles listed mage, archer, healer, tank, bers: in a
    // stack the first listed bot does it (S3's levers and gate: the archer, before the tank). The S2 device (Lights) is
    // the ee2 mage's, done in S1; S2's ll is the ee3 player's (the gate follows it).
    val PF = Preset(
        "PF",
        roles = linkedMapOf(
            MAGE to "bl ee2 / dev 2 / core / recore",
            ARCHER to "43 / 4 rl / 4 bl / 2",
            HEALER to "ss / ll ee3 / 2 dev / 4 bl",
            TANK to "21 / 13 / 1 bl / 1",
            BERSERK to "i4 / 53 / 3 / 3 bl",
        ),
        times = listOf(
            // ll 0.35, rl 2.15, gate 2.85 (into S2 at 3.15), i4 5.1, Lights 7.1, the four terms by 9, SS 9.6.
            "ll 0.35 | rl 2.15 | gate 2.85 | i4 5.1 | lights 7.1 | terms 9.0 | ss 9.6",
            "ll 2.8 | 1 4 5 3.0 | gate 3.4 | 2 3 rl 7.0",
            "1 4 3.0 | 2 3 5.0 | levers gate 8.0 | dev 9.0",
            "1 2 4.0 | 3 4 6.0 | levers 8.0",
        ),
        moves = listOf(
            // The i4 bot leaps out to the tank at 5.8; the mage on the ee2 spot by 7.8; everyone but SS leapt there by 9.5.
            "i4 leaps 5.8 | ee2 spot 7.8 | leap ee2 9.4",
            "ee3 spot 5.0 | ee3 waits 1 2 3 4",
            "", "",
        ),
    )

    val QUALITY_PF = Preset(
        "Quality PF",
        roles = linkedMapOf(
            MAGE to "bl ee2 / dev 2 3 / core / recore",
            ARCHER to "43 / 4 rl / 4 bl / 2",
            HEALER to "ss / (leap to 2nd term) 1 / 2 dev / 4 bl",
            TANK to "21 / ll ee3 / 1 bl / 1",
            BERSERK to "i4 / 53 / 3 / 3 bl",
        ),
        times = listOf(
            // ll 0.15, rl 1.75, gate 2.05 (into S2 at 2.55), i4 3.4, Lights 5.1, the four terms by 6, SS 8.2.
            "ll 0.15 | rl 1.75 | gate 2.05 | i4 3.4 | lights 5.1 | terms 6.0 | ss 8.2",
            "ll 0.8 | gate 1.0 | 1 4 5 2.1 | 2 3 rl 5.0",
            "1 3 4 2.1 | 2 4.0 | levers gate 6.0 | dev 6.5",
            "1 3 4 2.1 | 2 4.0 | levers 5.2",
        ),
        moves = listOf(
            // The i4 bot leaps out to the tank at 4.0; the mage on the ee2 spot by 5.8; everyone but SS leapt there by 6.4,
            // and the mage stays till the ee3 tank and S2's 4th and 5th have (it moves to its 2nd term about 7.2).
            "i4 leaps 4.0 | ee2 spot 5.8 | leap ee2 6.3 | ee2 waits ee3 4 5",
            "ee3 spot 3.2 | ee3 waits 1 2 3 4",
            "", "",
        ),
        // Stack help: S3 4 bl -> 1st term gets rl, 1 bl -> 4th term gets ll and the
        // gate (4.8 s); S4 3 bl -> 4th term gets rl, 4 bl -> 3rd term gets ll (4.2 s).
        helps = listOf(
            Help(3, "S3 T4", "S3 T1", listOf("S3 east lever"), 4.8),
            Help(3, "S3 T1", "S3 T4", listOf("S3 west lever", "gate 3"), 4.8),
            Help(4, "S4 T3", "S4 T4", listOf("S4 high lever"), 4.2),
            Help(4, "S4 T4", "S4 T3", listOf("S4 low lever"), 4.2),
        ),
    )

    /** The same as Quality PF. */
    val DYNAMIC = Preset("Dynamic", QUALITY_PF.roles, QUALITY_PF.times, QUALITY_PF.moves, QUALITY_PF.helps)

    /**
     * A route planner's prototype roles (a 399-tick P3), times from its simulation. The tank pre-does Lights and waits on S2's high path for the archer, mage and bers;
     * the bers waits on S3 T3 for the healer, tank and archer; nobody early-enters S4 (no EE4: never needed).
     * (The planner's bers also leaps onto the archer at T4 for S3's levers: the bots walk it.)
     */
    val PROTOTYPE_1 = Preset(
        "Prototype 1",
        roles = linkedMapOf(
            HEALER to "ss (leap archer) / 1 (leap bers) / 1 (leap mage) / 1 recore",
            MAGE to "bl 43 / 53 / / 4 recore",
            BERSERK to "i4 / ee3 / 3 bl / bl",
            ARCHER to "21 / 2 / dev 4 (leap healer) / 2 recore",
            TANK to "ee2 / dev bl 4 / 2 / 3 recore",
        ),
        times = listOf(
            "ll 0.1 | lights 0.1 | rl 0.45 | gate 0.6 | 2 2.75 | i4 3.05 | 4 3.35 | 1 4.85 | 3 5.45 | ss 8.1",
            "ll 0.1 | rl 0.8 | gate 1.0 | 5 2 2.1 | 1 3.0 | arrows 3.6 | 3 4.3 | 4 4.4",
            "4 2.1 | 3 2.15 | 1 2.45 | 2 2.95 | ll 4.0 | gate 4.2 | rl 4.4",
            "ll 1.1 | rl 1.75 | terms 2.1",
        ),
        moves = listOf(
            "ee2 waits 2 5 ee3",
            "ee3 waits 1 2 4",
            "",
            "",
        ),
        spots = mapOf(
            "ee2" to Vec3(34.05, 131.0, 139.05),
            "ee3" to Vec3(16.5, 123.0, 93.7),
        ),
    )

    /** In the skill menu as listed, with Random after the first three ([P3Plan.SKILLS]). */
    val PRESETS = listOf(PF, QUALITY_PF, DYNAMIC, PROTOTYPE_1)

    /** `ll` and `rl` per section coming in along the track (S2: the high lever on the left, the low one by T4 on the right; S4, heading east: the low lever by T3 on the left, the high one by T4 on the right). */
    val LEFT = mapOf(1 to "S1 west lever", 2 to "S2 high lever", 3 to "S3 west lever", 4 to "S4 low lever")
    val RIGHT = mapOf(1 to "S1 east lever", 2 to "S2 low lever", 3 to "S3 east lever", 4 to "S4 high lever")

    // ------------------------------------------------------------------ parsed

    /** A move (see the header): [kind] "leaps", "spot", "preleap" or "hold"; [who] the role token it's about. */
    class Move(val section: Int, val kind: String, val who: String, val at: Double?, val args: List<String> = emptyList())

    class Plan(
        /** Job -> the classes it's in, in role order (two or more: a stack). */
        val owners: Map<String, List<DungeonClass>>,
        /** Each class's jobs, in role order. */
        val jobsOf: Map<DungeonClass, List<String>>,
        /** Section entered early (5 = the core) -> class. */
        val ee: Map<Int, DungeonClass>,
        /** Job -> (the section it's done in, seconds into it). */
        val times: Map<String, Pair<Int, Double>>,
        val moves: List<Move>,
    )

    private val cache = HashMap<Preset, Plan>()
    fun plan(p: Preset): Plan = cache.getOrPut(p) { parse(p) }

    private fun device(s: Int) = when (s) { 1 -> "S1 SS"; 2 -> "S2 Lights"; 3 -> "S3 Arrows"; else -> "S4 Target" }
    private fun terms(s: Int) = (1..(if (s == 2) 5 else 4)).map { "S$s T$it" }

    /** A role or time token in section [s] -> its jobs (empty: not a job). */
    private fun jobs(token: String, s: Int): List<String> = when {
        token.all { it.isDigit() } -> token.map { "S$s T$it" }.filter { it in terms(s) }
        token == "ss" -> listOf("S1 SS")
        token == "i4" -> listOf("S4 Target")
        token == "lights" -> listOf("S2 Lights")
        token == "arrows" -> listOf("S3 Arrows")
        token == "dev" -> listOf(device(s))
        token == "ll" -> listOf(LEFT.getValue(s))
        token == "rl" -> listOf(RIGHT.getValue(s))
        token == "bl" || token == "levers" -> listOf(LEFT.getValue(s), RIGHT.getValue(s))
        token == "terms" -> terms(s)
        token == "gate" && s <= 3 -> listOf("gate $s")
        else -> emptyList()
    }

    private fun tokens(line: String) = line.replace(Regex("\\([^)]*\\)"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }

    private fun parse(p: Preset): Plan {
        val owners = LinkedHashMap<String, MutableList<DungeonClass>>()
        val jobsOf = LinkedHashMap<DungeonClass, MutableList<String>>()
        val ee = HashMap<Int, DungeonClass>()
        // Who has each section's ll/bl (gets the gate), else its rl.
        val leverFirst = HashMap<Int, MutableList<DungeonClass>>()
        val leverRight = HashMap<Int, MutableList<DungeonClass>>()
        for ((c, line) in p.roles) {
            val mine = jobsOf.getOrPut(c) { ArrayList() }
            line.split('/').forEachIndexed { i, part ->
                val s = i + 1
                if (s > 4) return@forEachIndexed
                for (t in tokens(part)) {
                    when {
                        t.startsWith("ee") && t.drop(2).toIntOrNull() != null -> ee[t.drop(2).toInt()] = c
                        t == "core" -> ee[5] = c
                    }
                    if (t == "ll" || t == "bl") leverFirst.getOrPut(s) { ArrayList() } += c
                    if (t == "rl") leverRight.getOrPut(s) { ArrayList() } += c
                    for (j in jobs(t, s)) {
                        if (j !in mine) mine += j
                        val o = owners.getOrPut(j) { ArrayList() }
                        if (c !in o) o += c
                    }
                }
            }
        }
        for (s in 1..3) (leverFirst[s] ?: leverRight[s])?.distinct()?.forEach { c ->
            jobsOf.getValue(c) += "gate $s"
            owners.getOrPut("gate $s") { ArrayList() } += c
        }
        val times = HashMap<String, Pair<Int, Double>>()
        p.times.forEachIndexed { i, line ->
            val s = i + 1
            for (group in line.split('|')) {
                val ts = tokens(group)
                val at = ts.lastOrNull()?.toDoubleOrNull() ?: continue
                ts.dropLast(1).flatMap { jobs(it, s) }.forEach { times[it] = s to at }
            }
        }
        val moves = ArrayList<Move>()
        p.moves.forEachIndexed { i, line ->
            val s = i + 1
            for (group in line.split('|')) {
                val ts = tokens(group)
                when {
                    ts.size >= 2 && ts[0] == "leap" -> moves += Move(s, "preleap", ts[1], ts.getOrNull(2)?.toDoubleOrNull())
                    ts.size >= 2 && ts[0] == "hold" -> moves += Move(s, "hold", ts[1], null)
                    ts.size >= 2 -> moves += Move(s, ts[1], ts[0], ts.getOrNull(2)?.toDoubleOrNull(), ts.drop(2))
                }
            }
        }
        return Plan(owners, jobsOf, ee, times, moves)
    }

    /** "ee2" / "ee3" / "core" / "i4" / "ss"... -> the class it's about in [plan]. */
    fun whoIs(plan: Plan, token: String): DungeonClass? = when {
        token.startsWith("ee") -> token.drop(2).toIntOrNull()?.let { plan.ee[it] }
        token == "core" -> plan.ee[5]
        else -> jobs(token, 1).firstOrNull()?.let { plan.owners[it]?.firstOrNull() }
    }

    /** Short label for a class (the bots' names). */
    fun label(c: DungeonClass) = c.name.lowercase().replaceFirstChar { it.uppercase() }
}
