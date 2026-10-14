package com.engineerclient.adr

/**
 * The party you're in, worked out from Hypixel's chat as it happens - joins, leaves, kicks,
 * transfers, promotions, disbands, Party Finder joins, party chat - so it's known at every moment
 * without asking. Plain Kotlin, no game: [PartyTracker] feeds it, and it can be replayed over saved
 * chat to check it.
 *
 * It can't always know: right after the game starts (the party from before isn't announced again),
 * or after joining through the Party Finder (only "X joined the dungeon group!" says so, not who
 * else is there). Then [doubt] says why, and a /p list (read by [line] like everything else) settles
 * it. Anything that doesn't add up - party chat from someone it doesn't have, a leave from someone it
 * never saw - is a doubt too, rather than a guess.
 */
class PartyState(private val self: () -> String) {
    enum class Role { LEADER, MOD, MEMBER }
    class Member(val name: String, var role: Role, var online: Boolean = true)

    /** Whether the party is known at all; false until something says (see [doubt]). */
    var known = false
        private set
    /** Everyone in it, you included (lower case name -> member). Empty and [known]: not in a party. */
    val members = LinkedHashMap<String, Member>()
    /** Why a /p list is wanted, or null when sure. */
    var doubt: String? = "not known yet"
        private set
    /** Bumped on every change, so a reader can tell. */
    var version = 0
        private set

    val inParty get() = members.isNotEmpty()
    val leader get() = members.values.firstOrNull { it.role == Role.LEADER }?.name

    // A /p list being read: its lines come one after another (or as one message).
    private var list: ListRead? = null
    private class ListRead(val size: Int, val at: Long) {
        val members = LinkedHashMap<String, Member>()
    }

    private fun me() = self().lowercase()
    private fun changed() { version++ }
    private fun sure() { known = true; doubt = null }
    private fun unsure(why: String) { if (doubt == null) doubt = why; changed() }

    private fun clear() { members.clear(); sure(); changed() }

    private fun add(name: String, role: Role = Role.MEMBER) {
        val k = name.lowercase()
        val m = members[k]
        if (m == null) members[k] = Member(name, role)
        else { m.online = true; if (role != Role.MEMBER) m.role = role }
        changed()
    }

    private fun remove(name: String) {
        if (members.remove(name.lowercase()) == null) unsure("$name left, but wasn't known to be in the party")
        // Just you left: not a party any more (if it was known who else there was).
        if (doubt == null && members.size == 1 && members.containsKey(me())) members.clear()
        changed()
    }

    private fun setLeader(name: String) {
        for (m in members.values) if (m.role == Role.LEADER) m.role = Role.MOD
        add(name, Role.LEADER)
        // The leader first, as /p list has it.
        val l = members.remove(name.lowercase())!!
        val rest = members.values.toList()
        members.clear(); members[l.name.lowercase()] = l
        for (m in rest) members[m.name.lowercase()] = m
    }

    /** You're in a party now (it didn't say so before): you are in it, the rest as they come. */
    private fun ensureIn() {
        if (!members.containsKey(me())) members[me()] = Member(self(), Role.MEMBER)
    }

    /** Tells the state the game was (re)joined from outside Hypixel, or restarted: nothing is known. */
    fun reset(why: String) {
        members.clear(); known = false; doubt = why; list = null; changed()
    }

    /**
     * One line of system chat, without colour codes ([colored]: the same with them, for /p list's
     * online dots). Returns true if it was part of a /p list (the caller may hide it then).
     */
    fun line(text: String, colored: String? = null, now: Long = System.currentTimeMillis()): Boolean {
        val t = text.trim()
        // A /p list that stopped short (Hypixel sends it whole, so this is only ever a guard).
        list?.let { if (now - it.at > 3000) finishList() }
        if (readList(t, colored, now)) return true

        JOINED_SELF.matchEntire(t)?.let { m ->
            members.clear()
            add(m.groupValues[1], Role.LEADER)
            ensureIn(); sure(); changed()
            // The others (if any) come next: "You'll be partying with: ...".
            return false
        }
        PARTYING_WITH.matchEntire(t)?.let { m ->
            for (n in names(m.groupValues[1])) add(n)
            ensureIn(); changed()
            return false
        }
        JOINED_OTHER.matchEntire(t)?.let { m ->
            val n = m.groupValues[1]
            if (!inParty) {
                // Someone joining a party you didn't know you had: you invited them, so it's yours -
                // unless it wasn't known at all, then who leads isn't.
                if (known) { add(self(), Role.LEADER) } else { ensureIn(); unsure("$n joined a party that wasn't known") }
            }
            add(n)
            return false
        }
        LEFT.matchEntire(t)?.let { remove(it.groupValues[1]); return false }
        REMOVED.matchEntire(t)?.let { remove(it.groupValues[1]); return false }
        REMOVED_DC.matchEntire(t)?.let { remove(it.groupValues[1]); return false }
        KICKED_OFFLINE.matchEntire(t)?.let { m -> for (n in names(m.groupValues[1])) remove(n); return false }
        if (NOT_IN_PARTY.matches(t) || YOU_LEFT.matches(t) || YOU_KICKED.matches(t) || DISBANDED.matches(t) || DISBANDED_BY.matches(t)) { clear(); return false }
        TRANSFER_BY.matchEntire(t)?.let { m ->
            if (!inParty) unsure("a transfer in a party that wasn't known")
            ensureIn()
            setLeader(m.groupValues[1])
            add(m.groupValues[2], Role.MOD) // the old leader stays on as a moderator
            return false
        }
        TRANSFER_LEFT.matchEntire(t)?.let { m ->
            if (!inParty) unsure("a transfer in a party that wasn't known")
            ensureIn()
            members.remove(m.groupValues[2].lowercase())
            setLeader(m.groupValues[1])
            return false
        }
        PROMOTED.matchEntire(t)?.let { m ->
            ensureKnownIn("a promotion")
            if (m.groupValues[3] == "Leader") { setLeader(m.groupValues[2]); add(m.groupValues[1], Role.MOD) }
            else { add(m.groupValues[1]); add(m.groupValues[2], Role.MOD) }
            return false
        }
        DEMOTED.matchEntire(t)?.let { m ->
            ensureKnownIn("a demotion")
            add(m.groupValues[1])
            members[m.groupValues[2].lowercase()]?.let { it.role = Role.MEMBER; changed() } ?: add(m.groupValues[2])
            return false
        }
        INVITED.matchEntire(t)?.let { m ->
            val by = m.groupValues[1]
            if (by.equals(self(), true)) {
                // Inviting from no party makes one, led by you (just you until someone joins).
                if (!inParty && known) { add(self(), Role.LEADER); return false }
                if (!inParty) { ensureIn(); unsure("an invite from a party that wasn't known") }
            } else if (!members.containsKey(by.lowercase())) {
                ensureKnownIn("an invite from someone not known to be in the party")
                add(by)
            }
            return false
        }
        LEADER_DC.matchEntire(t)?.let { m -> ensureKnownIn("the leader disconnecting"); setLeader(m.groupValues[1]); members[m.groupValues[1].lowercase()]?.online = false; changed(); return false }
        LEADER_BACK.matchEntire(t)?.let { m -> ensureKnownIn("the leader rejoining"); setLeader(m.groupValues[1]); return false }
        MEMBER_DC.matchEntire(t)?.let { m ->
            ensureKnownIn("someone disconnecting")
            if (!members.containsKey(m.groupValues[1].lowercase())) { add(m.groupValues[1]); unsure("${m.groupValues[1]} disconnected, but wasn't known to be in the party") }
            members[m.groupValues[1].lowercase()]?.online = false
            changed()
            return false
        }
        SUMMONED.matchEntire(t)?.let { m -> leaderEvidence(m.groupValues[1]); return false }
        ALL_INVITE.matchEntire(t)?.let { m -> leaderEvidence(m.groupValues[1]); return false }
        PF_JOINED.matchEntire(t)?.let { m ->
            val n = m.groupValues[1]
            if (n.equals(self(), true)) {
                // You joined someone's Party Finder group: in their party, but nothing says who's there.
                members.clear(); ensureIn(); known = true; doubt = "joined a Party Finder group"; changed()
            } else {
                if (!inParty) { if (known) add(self(), Role.LEADER) else { ensureIn(); unsure("$n joined a party that wasn't known") } }
                add(n)
            }
            return false
        }
        PARTY_CHAT.matchEntire(t)?.let { m ->
            val n = m.groupValues[1]
            if (!members.containsKey(n.lowercase())) {
                ensureIn()
                if (!n.equals(self(), true)) add(n)
                unsure("party chat from $n, who wasn't known to be in the party")
            } else members[n.lowercase()]?.let { if (!it.online) { it.online = true; changed() } }
            return false
        }
        return false
    }

    /** Something only a party has happened: if no party was known, there is one (and who's in it isn't). */
    private fun ensureKnownIn(what: String) {
        if (!inParty) { ensureIn(); unsure("$what in a party that wasn't known") }
    }

    private fun leaderEvidence(name: String) {
        ensureKnownIn("a leader's action")
        val m = members[name.lowercase()]
        if (m?.role != Role.LEADER) {
            if (leader != null && !leader.equals(name, true)) unsure("$name acted as leader, but $leader was")
            setLeader(name)
        }
    }

    // ------------------------------------------------------------------ /p list

    private fun readList(t: String, colored: String?, now: Long): Boolean {
        LIST_HEAD.matchEntire(t)?.let { m -> list = ListRead(m.groupValues[1].toInt(), now); return true }
        val l = list ?: return SEPARATOR.matches(t) && false
        if (t.isEmpty() || SEPARATOR.matches(t)) { if (l.members.size >= l.size) finishList(); return true }
        val m = LIST_ROW.matchEntire(t) ?: run { finishList(); return false }
        val role = when (m.groupValues[1]) { "Leader" -> Role.LEADER; "Moderators" -> Role.MOD; else -> Role.MEMBER }
        // Online or not is the dot's colour: green or red.
        val dots = colored?.let(::dots)
        ENTRY.findAll(m.groupValues[2]).forEachIndexed { i, e ->
            val n = e.groupValues[1]
            l.members[n.lowercase()] = Member(n, role, dots?.getOrNull(i) ?: true)
        }
        if (l.members.size >= l.size) finishList()
        return true
    }

    private fun finishList() {
        val l = list ?: return
        list = null
        members.clear()
        // The leader first, as everywhere else.
        l.members.values.sortedBy { it.role.ordinal }.forEach { members[it.name.lowercase()] = it }
        if (members.size == 1) members.clear()
        sure(); changed()
    }

    // ------------------------------------------------------------------ in a dungeon

    /**
     * Who's in the dungeon with you (the tab list's teammates): the party is exactly them. A change
     * against what's known (someone missed) is taken as it is, and who leads stays as known.
     */
    fun teammates(names: List<String>) {
        if (names.isEmpty()) return
        val want = names.associateBy { it.lowercase() }
        if (want.keys == members.keys) return
        val lead = leader
        members.clear()
        for ((k, n) in want) members[k] = Member(n, if (lead.equals(n, true)) Role.LEADER else Role.MEMBER)
        if (members.size == 1) members.clear()
        known = true
        if (lead == null || !want.containsKey(lead.lowercase())) unsure("in a dungeon, but who leads isn't known") else changed()
    }

    /** A short line for logs and tests. */
    override fun toString() = (if (known) "" else "? ") + (if (!inParty) (if (known) "solo" else "") else members.values.joinToString(" ") { (if (it.role == Role.LEADER) "*" else if (it.role == Role.MOD) "+" else "") + it.name + (if (it.online) "" else "(off)") }) + (doubt?.let { " [doubt: $it]" } ?: "")

    companion object {
        /** A name with its rank, if any: "[MVP++] Name" or "Name". */
        private const val N = """(?:\[[^\]]{1,20}] )?(\w{1,16})"""
        private val JOINED_SELF = Regex("""^You have joined $N's? party!$""")
        private val PARTYING_WITH = Regex("""^You'll be partying with: (.+)$""")
        private val JOINED_OTHER = Regex("""^$N joined the party\.$""")
        private val LEFT = Regex("""^$N has left the party\.$""")
        private val REMOVED = Regex("""^$N has been removed from the party\.$""")
        private val REMOVED_DC = Regex("""^$N was removed from your party because they disconnected\.$""")
        private val KICKED_OFFLINE = Regex("""^Kicked (.+?) because they were offline\.$""")
        private val NOT_IN_PARTY = Regex("""^You are not (?:currently )?in a party\.$""")
        private val YOU_LEFT = Regex("""^You left the party\.$""")
        private val YOU_KICKED = Regex("""^You have been kicked from the party by .+$""")
        private val DISBANDED = Regex("""^The party was disbanded because .+$""")
        private val DISBANDED_BY = Regex("""^$N has disbanded the party!$""")
        private val TRANSFER_BY = Regex("""^The party was transferred to $N by $N$""")
        private val TRANSFER_LEFT = Regex("""^The party was transferred to $N because $N left$""")
        private val PROMOTED = Regex("""^$N has promoted $N to Party (Leader|Moderator)$""")
        private val DEMOTED = Regex("""^$N has demoted $N to Party Member$""")
        private val INVITED = Regex("""^$N invited $N to the party! They have 60 seconds to accept\.$""")
        private val LEADER_DC = Regex("""^The party leader, $N has disconnected, .+$""")
        private val LEADER_BACK = Regex("""^The party leader $N has rejoined\.$""")
        private val MEMBER_DC = Regex("""^$N has disconnected, they have \d+ minutes? to rejoin before they are removed from the party\.$""")
        private val SUMMONED = Regex("""^Party Leader, $N, summoned you to their server\.$""")
        private val ALL_INVITE = Regex("""^$N (?:enabled|disabled) All Invite$""")
        private val PF_JOINED = Regex("""^Party Finder > (\w{1,16}) joined the (?:dungeon )?group! \(.+\)$""")
        private val PARTY_CHAT = Regex("""^Party > $N: .*$""")
        private val LIST_HEAD = Regex("""^Party Members \((\d+)\)$""")
        private val LIST_ROW = Regex("""^Party (Leader|Moderators|Members): (.+)$""")
        private val ENTRY = Regex("""(?:\[[^\]]{1,20}] )?(\w{1,16}) ●""")
        private val SEPARATOR = Regex("""^-{10,}$""")

        /** For each ● in a coloured /p list row, whether it is green (online) rather than red: the last colour before it. */
        fun dots(colored: String): List<Boolean> {
            val out = ArrayList<Boolean>()
            var color = 'f'
            var i = 0
            while (i < colored.length) {
                val c = colored[i]
                if (c == '§' && i + 1 < colored.length) { val k = colored[i + 1].lowercaseChar(); if (k in "0123456789abcdef") color = k; i += 2; continue }
                if (c == '●') out += color != 'c' && color != '4'
                i++
            }
            return out
        }

        /** "[MVP+] A, B, [VIP] C" (or "A and B") -> names. */
        fun names(s: String): List<String> = s.split(Regex(""",\s*|\s+and\s+""")).mapNotNull { Regex("""^$N$""").matchEntire(it.trim())?.groupValues?.get(1) }
    }
}
