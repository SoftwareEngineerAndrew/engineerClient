package com.bloodrushwaypoints.rotation

/**
 * Who currently has an invincibility available. "Mask" is shorthand for all three types.
 *
 * VENDORED from Odin `InvincibilityTimer` — its `InvincibilityType` enum is private, so the
 * regexes and cooldown lengths are copied rather than called. Keep them in step when re-vendoring.
 *
 * Two sources, and they differ in what they can know:
 *
 *  - **Yourself**: the proc lines are sent only to you, so your own state is exact.
 *  - **Teammates**: Odin already announces every proc to party chat when "Announce Invincibility"
 *    is on (it is by default, and the party's logs show every member doing it), so a teammate's
 *    cooldowns can be followed without adding any traffic of our own. What that cannot reveal is
 *    which invincibility items a player actually *owns* — someone with no Bonzo mask never procs
 *    one, and looks indistinguishable from someone holding an unused one. So a teammate's count is
 *    an UPPER BOUND, which is exactly why the deciding player has to state their own qualification
 *    rather than have it inferred.
 */
object MaskTracker {

    enum class Kind(val selfLine: Regex, val cooldownTicks: Int) {
        SPIRIT(Regex("^Second Wind Activated! Your Spirit Mask saved your life!$"), 600),
        BONZO(Regex("^Your (?:. )?Bonzo's Mask saved your life!$"), 3600),
        PHOENIX(Regex("^Your Phoenix Pet saved you from certain death!$"), 1200);

        companion object {
            fun ofSelfLine(line: String): Kind? = entries.firstOrNull { it.selfLine.matches(line) }

            /** The word Odin puts in its party announcement: "Spirit Procced! (1/3)". */
            fun ofAnnouncement(word: String): Kind? = entries.firstOrNull { it.name.equals(word, true) }
        }
    }

    /** Remaining cooldown in ticks, per player, per type. Absent means ready. */
    private val cooldowns = HashMap<String, MutableMap<Kind, Int>>()

    fun reset() = cooldowns.clear()

    /** Advance every tracked cooldown by one server tick. */
    fun tick() {
        val empty = mutableListOf<String>()
        cooldowns.forEach { (ign, byKind) ->
            byKind.entries.removeIf { (_, left) -> left <= 1 }
            byKind.replaceAll { _, left -> left - 1 }
            if (byKind.isEmpty()) empty += ign
        }
        empty.forEach { cooldowns.remove(it) }
    }

    fun proc(ign: String, kind: Kind) {
        cooldowns.getOrPut(ign) { mutableMapOf() }[kind] = kind.cooldownTicks
        BrwLog.log("MASK", "$ign ${kind.name} procced; ${available(ign)}/3 left")
    }

    /** How many of the three are off cooldown for this player. For a teammate this is an upper bound. */
    fun available(ign: String): Int = Kind.entries.count { cooldowns[ign]?.containsKey(it) != true }

    fun onCooldown(ign: String): List<Kind> = Kind.entries.filter { cooldowns[ign]?.containsKey(it) == true }

    fun cooldownTicks(ign: String, kind: Kind): Int = cooldowns[ign]?.get(kind) ?: 0

    /** Your own proc line. Returns the type when it matched. */
    fun onSelfLine(self: String, line: String): Kind? =
        Kind.ofSelfLine(line)?.also { proc(self, it) }

    /**
     * Odin's own party announcement, e.g. "Spirit Procced! (1/3)". Returns the type when it matched.
     * The count is how many that player has on cooldown at that moment — we trust the type and
     * re-derive the timing ourselves, since the count alone says nothing about when they recover.
     */
    private val announcement = Regex("^(\\w+) Procced! \\((\\d)/(\\d)\\)")

    fun onPartyAnnouncement(ign: String, message: String): Kind? {
        val m = announcement.find(message) ?: return null
        val kind = Kind.ofAnnouncement(m.groupValues[1]) ?: return null
        proc(ign, kind)
        return kind
    }
}
