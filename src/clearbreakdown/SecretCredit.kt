package com.engineerclient.clearbreakdown

/** Secrets a player got in one room; [estimate] when some were split between players who could have found them. */
data class RoomSecrets(val room: String, val count: Int, val estimate: Boolean)

/** A player's secrets: [total] from the API (null when it didn't answer), the rooms, and what no room explains. */
data class PlayerSecrets(val total: Int?, val rooms: List<RoomSecrets>, val unknown: Int)

/**
 * Who found which secrets, and in which room. Two kinds of evidence come in:
 *
 *  - [sighting]: a secret seen happening (an item picked up, a chest lid opening, an essence skull
 *    going, a secret bat): one secret, with the players who could have got it. Keyed by what the
 *    server calls it (entity id, block), so the same secret seen by two clients counts once: the
 *    first sighting is the secret, an exact one (the pickup packet's collector, your own click)
 *    replaces a "who was in reach" one of the same moment, and two in-reach sightings of the same moment are joined.
 *  - [observe]: Odin's count of a room's secrets going up (action bar, its map sync, a green
 *    checkmark). Whatever the sightings since the last count don't explain is a gap, given to
 *    everyone who was in that room since that last count: a count is often first seen when someone
 *    walks in, long after the secrets were found.
 *
 * [resolve] settles them against each player's API total for the run: a secret with one possible
 * finder is theirs; one with several goes to the only one who still has secrets left to place,
 * over and over until nothing changes; what's still shared after that is split in proportion to
 * what each has left, as an estimate. What a total has beyond the rooms is "unknown rooms".
 *
 * Nothing here touches Minecraft, so it tests headlessly.
 */
class SecretCredit(private val mergeMs: Long = 1000, private val exactMs: Long = 3000) {
    private class Group(val roomKey: String, val roomName: String, var candidates: Set<String>, var exact: Boolean, val atMs: Long, var units: Int, val gap: Boolean = false)

    private val groups = ArrayList<Group>()
    private val byKey = HashMap<String, Group>()
    private val lastSeen = HashMap<String, Int>()
    private val directSince = HashMap<String, Int>()
    private val visited = HashMap<String, HashSet<String>>()
    private var present: Map<String, String?> = emptyMap()

    /** One client tick: player name -> room key (the same map the clear credit gets). */
    fun tick(presence: Map<String, String?>) {
        present = presence
        for ((player, room) in presence) if (room != null) visited.getOrPut(room) { HashSet() } += player
    }

    /** A secret seen directly, by this client or a party member's. [exact]: [candidates] is the known finder. */
    fun sighting(key: String, roomKey: String, roomName: String, candidates: Set<String>, exact: Boolean, atMs: Long) {
        val known = byKey[key]
        if (known != null) {
            // Exact beats in-reach only for the same moment (a party member's copy can be a few seconds
            // late): a click on a chest long after it was looted is a re-open, not its finder.
            if (exact && !known.exact && kotlin.math.abs(atMs - known.atMs) <= exactMs) { known.candidates = candidates; known.exact = true }
            else if (!exact && !known.exact && kotlin.math.abs(atMs - known.atMs) <= mergeMs) known.candidates = known.candidates + candidates
            return
        }
        // A party member's sighting can land just after Odin's count already made it a gap: it
        // takes that gap's place rather than counting the secret twice.
        val gap = groups.lastOrNull { it.gap && it.roomKey == roomKey && atMs - it.atMs in 0..GAP_CATCH_MS && it.units > 0 }
        if (gap != null) gap.units-- else directSince[roomKey] = (directSince[roomKey] ?: 0) + 1
        val g = Group(roomKey, roomName, candidates, exact, atMs, 1)
        groups += g
        byKey[key] = g
    }

    /** A room's found count, from Odin. Counts at or below the last one seen change nothing. */
    fun observe(roomKey: String, roomName: String, found: Int, atMs: Long = System.currentTimeMillis()) {
        val last = lastSeen[roomKey] ?: 0
        if (found <= last) return
        val gap = found - last - (directSince[roomKey] ?: 0)
        if (gap > 0) groups += Group(roomKey, roomName, visited[roomKey].orEmpty().toSet(), false, atMs, gap, gap = true)
        lastSeen[roomKey] = found
        directSince[roomKey] = 0
        visited[roomKey] = present.filterValues { it == roomKey }.keys.toHashSet()
    }

    /** Every player's secrets, one entry per name in [apiTotals] (name -> run total, null if the API failed). */
    fun resolve(apiTotals: Map<String, Int?>): Map<String, PlayerSecrets> {
        val left = HashMap<String, Int>()
        for ((p, t) in apiTotals) left[p] = t ?: Int.MAX_VALUE
        // (group index, player, count, estimate)
        val credits = ArrayList<Credit>()
        val remaining = IntArray(groups.size)
        val cands = groups.map { g -> g.candidates.filter { it in left }.sorted() }

        fun give(i: Int, p: String, n: Int, estimate: Boolean) {
            if (n <= 0) return
            credits += Credit(i, p, n, estimate)
            left[p] = (left.getValue(p) - n).coerceAtLeast(0)
        }

        // Certain: one possible finder.
        for ((i, g) in groups.withIndex()) {
            if (g.units <= 0) continue
            if (cands[i].size == 1) give(i, cands[i][0], g.units, false) else if (cands[i].size > 1) remaining[i] = g.units
        }
        // Elimination: a secret only one candidate still has room for is theirs.
        var changed = true
        while (changed) {
            changed = false
            for (i in groups.indices) {
                while (remaining[i] > 0) {
                    val eligible = cands[i].filter { left.getValue(it) > 0 }
                    if (eligible.size > 1) break
                    if (eligible.size == 1) give(i, eligible[0], 1, false)
                    remaining[i]--
                    changed = true
                }
            }
        }
        // Still shared: split in proportion to what each has left to place.
        for (i in groups.indices) {
            val n = remaining[i]
            if (n <= 0) continue
            val eligible = cands[i].filter { left.getValue(it) > 0 }
            if (eligible.isEmpty()) continue
            val unlimited = eligible.any { left.getValue(it) == Int.MAX_VALUE }
            val weights = eligible.map { if (unlimited) 1.0 else left.getValue(it).toDouble() }
            val sum = weights.sum()
            val exact = weights.map { n * it / sum }
            val shares = exact.map { it.toInt() }.toIntArray()
            // Largest remainder; ties go to the earlier name.
            val byRemainder = eligible.indices.sortedWith(compareByDescending<Int> { exact[it] - shares[it] }.thenBy { it })
            for (k in 0 until n - shares.sum()) shares[byRemainder[k % eligible.size]]++
            for ((k, p) in eligible.withIndex()) give(i, p, shares[k].coerceAtMost(left.getValue(p)), true)
        }

        return apiTotals.mapValues { (p, total) ->
            val rooms = LinkedHashMap<String, RoomSecrets>()
            for (c in credits.filter { it.player == p }.sortedBy { it.group }) {
                val name = groups[c.group].roomName
                val had = rooms[name]
                rooms[name] = RoomSecrets(name, (had?.count ?: 0) + c.count, (had?.estimate ?: false) || c.estimate)
            }
            val placed = rooms.values.sumOf { it.count }
            PlayerSecrets(total, rooms.values.toList(), if (total != null) (total - placed).coerceAtLeast(0) else 0)
        }
    }

    fun reset() {
        groups.clear(); byKey.clear(); lastSeen.clear(); directSince.clear(); visited.clear(); present = emptyMap()
    }

    private class Credit(val group: Int, val player: String, val count: Int, val estimate: Boolean)

    private companion object {
        /** How late a party member's sighting may arrive after Odin's count of the same secret. */
        const val GAP_CATCH_MS = 3000L
    }
}
