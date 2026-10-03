package com.engineerclient.p3sim

import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.ARCHER
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.BERSERK
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.HEALER
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.MAGE
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass.TANK

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
 * Bots that pre-leapt walk on to their next terminals; at the core, everyone leaps in at once.
 */
object Roles {
    class Preset(val name: String, val roles: Map<DungeonClass, String>, val times: List<String>, val moves: List<String>)

    val PF = Preset(
        "PF",
        roles = linkedMapOf(
            HEALER to "ss / ll ee3 / 2 dev / 4 bl",
            MAGE to "43 / 2 core / core / recore",
            BERSERK to "i4 / 53 / 3 dev / 3 bl",
            TANK to "21 / 13 / 1 bl / 1",
            ARCHER to "bl ee2 / dev 4 rl / 4 bl / 2",
        ),
        times = listOf(
            "levers 2.2 | gate 2.4 | i4 7.3 | terms 9.0 | lights 10.0 | ss 13.5",
            "1 2 4 5 ll 4.4 | gate 4.6 | 3 rl 6.5",
            "1 2.4 | 2 3 4 3.5 | dev 4.2 | gate 5.0 | levers 5.2",
            "terms 4.5 | levers 5.2",
        ),
        moves = listOf(
            "i4 leaps 8.1 | ee2 spot 11.2 | leap ee2 12.1 | hold 2",
            "", "", "",
        ),
    )

    val QUALITY_PF = Preset(
        "Quality PF",
        roles = linkedMapOf(
            HEALER to "ss / (leap to 2nd term) 1 / 2 dev / 4 bl",
            // S2's dev: not in the roles as given; the mage does it (dev at 8.3, on the ee2 spot at 8.6).
            MAGE to "bl ee2 / dev 2 3 core / core / recore",
            BERSERK to "i4 / 53 / 3 dev / 3 bl",
            TANK to "21 / ll ee3 / 1 bl / 1",
            ARCHER to "43 / 4 rl / 4 bl / 2",
        ),
        times = listOf(
            "levers 1.1 | gate 1.3 | i4 6.8 | terms 8.1 | lights 8.3 | ss 12.3",
            "1 2 4 5 ll 1.9 | gate 2.1 | 3 rl 4.2",
            "2 3 4 2.1 | 1 2.8 | dev 3.7 | gate 3.9 | levers 4.1",
            "terms 3.1 | levers 4.2",
        ),
        moves = listOf(
            "i4 leaps 7.2 | ee2 spot 8.6 | leap ee2 9.1",
            "", "", "",
        ),
    )

    /** For now the same as Quality PF. */
    val DYNAMIC = Preset("Dynamic", QUALITY_PF.roles, QUALITY_PF.times, QUALITY_PF.moves)

    val PRESETS = listOf(PF, QUALITY_PF, DYNAMIC)

    /** `ll` and `rl` per section (S2: the high lever is on the left coming in, the low one by T4 on the right). */
    val LEFT = mapOf(1 to "S1 west lever", 2 to "S2 high lever", 3 to "S3 west lever", 4 to "S4 high lever")
    val RIGHT = mapOf(1 to "S1 east lever", 2 to "S2 low lever", 3 to "S3 east lever", 4 to "S4 low lever")

    // ------------------------------------------------------------------ parsed

    /** A move (see the header): [kind] "leaps", "spot", "preleap" or "hold"; [who] the role token it's about. */
    class Move(val section: Int, val kind: String, val who: String, val at: Double?)

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
                    ts.size >= 2 -> moves += Move(s, ts[1], ts[0], ts.getOrNull(2)?.toDoubleOrNull())
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
