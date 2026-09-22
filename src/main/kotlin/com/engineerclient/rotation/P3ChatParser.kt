package com.engineerclient.rotation

/**
 * Turns phase-3 chat into events. Kept free of Minecraft so the patterns can be tested against
 * real captured log lines.
 *
 * Hypixel's own wording is `IGN activated a terminal! (3/7)`, but by the time a line reaches us
 * other dungeon mods have often rewritten it — dropping the "a", colouring the name, appending
 * Odin's terminal splits. So: the tail is never anchored, the article is optional, and colour
 * codes are stripped first.
 */
object P3ChatParser {

    /** One completion line. [done] of [total] is the section's own counter, not a global one. */
    data class Completion(val ign: String, val type: String, val done: Int, val total: Int) {
        /** True when this line is the one that finishes its section. */
        val sectionDone: Boolean get() = done == total && total > 0
    }

    private val colorCodes = Regex("§[0-9a-fk-orA-FK-OR]")
    // Anchored at the start: Hypixel's line begins with the IGN. Mods relay completions into party
    // chat ("Party > p3wr: p3wr activated lever! (2/7)"), and those must never count.
    private val completed = Regex("^(\\w{1,16}) (?:activated|completed) (?:an? )?(terminal|lever|device)! \\((\\d+)/(\\d+)\\)")
    private val goldor = Regex("\\[BOSS] Goldor: Who dares trespass into my domain\\?")
    private val coreOpening = Regex("The Core entrance is opening!")
    private val gate = Regex("The gate has been destroyed!")

    fun clean(raw: String): String = colorCodes.replace(raw, "").trim()

    fun completion(line: String): Completion? {
        val cleaned = clean(line)
        if (cleaned.startsWith("Party >")) return null
        val m = completed.find(cleaned) ?: return null
        val (ign, type, done, total) = m.destructured
        return Completion(ign, type, done.toIntOrNull() ?: return null, total.toIntOrNull() ?: return null)
    }

    fun isPhaseStart(line: String): Boolean = goldor.containsMatchIn(clean(line))

    fun isPhaseEnd(line: String): Boolean = coreOpening.containsMatchIn(clean(line))

    fun isGateDestroyed(line: String): Boolean = gate.containsMatchIn(clean(line))

    /** One party message, with the rank prefix stripped. */
    data class PartyLine(val ign: String, val message: String)

    // "Party > [MVP++] m7kitten: Spirit Procced! (1/3)" — the rank bracket is absent for
    // unranked players, and Minecraft appends "(2)" to a repeated line.
    private val party = Regex("^Party > (?:\\[[^]]*] )?(\\w{1,16}): (.*)$")

    private val leaped = Regex("^Leaped to (\\w{1,16})!")
    private val startingRole = Regex("^brw s1 (\\S{1,16})$", RegexOption.IGNORE_CASE)

    /** A teammate stating their section-1 role: "brw s1 43". Returns the role NAME as typed. */
    fun startingRole(message: String): String? = startingRole.find(message.trim())?.groupValues?.get(1)

    private val teleported = Regex("^You have teleported to (\\w{1,16})!")

    /** Hypixel's own confirmation, sent only to the leaper. */
    fun teleportedTo(line: String): String? = teleported.find(clean(line))?.groupValues?.get(1)

    /** Odin's leap announcement inside a party message: who they leapt to. */
    fun leapedTo(message: String): String? = leaped.find(message.trim())?.groupValues?.get(1)

    fun partyLine(line: String): PartyLine? {
        val m = party.find(clean(line)) ?: return null
        return PartyLine(m.groupValues[1], m.groupValues[2].trim())
    }
}
