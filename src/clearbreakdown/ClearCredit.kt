package com.engineerclient.clearbreakdown

/** A room's checkmark, as far as clearing goes: Odin's other map states are all [OTHER]. */
enum class Mark { WHITE, GREEN, RED, OTHER }

/** One room a player helped clear: [solo] when nobody else helped. */
data class Clear(val room: String, val solo: Boolean)

/**
 * Who cleared which room. A player helps clear a room by spending more than [helpTicks] in it
 * (added up over every visit) while it is still uncleared, or by being in it when it gets its
 * checkmark. The room is credited at its first white or green checkmark, once, to every helper;
 * it is a solo clear when there was only one.
 *
 * A failed puzzle's red X credits nobody and throws away the time spent on the failed attempt:
 * Odin never says when a reset puzzle is back to normal (it stays red until it turns white or
 * green), so the time after the red X is what counts toward the attempt that clears it.
 *
 * Nothing here touches Minecraft, so it tests headlessly: the module feeds it each tick's
 * presence (player name to room key) and the checkmarks of the room types that count.
 */
class ClearCredit(private val helpTicks: Int = 40) {
    private val ticks = HashMap<String, HashMap<String, Int>>()
    private val credited = HashSet<String>()
    private val order = ArrayList<Pair<String, Set<String>>>()
    private var present: Map<String, String?> = emptyMap()

    /** One client tick: player name -> the room key they are in (absent or null: none, or dead). */
    fun tick(presence: Map<String, String?>) {
        present = presence
        for ((player, room) in presence) {
            if (room == null || room in credited) continue
            val inRoom = ticks.getOrPut(room) { HashMap() }
            inRoom[player] = (inRoom[player] ?: 0) + 1
        }
    }

    /** A room's checkmark changed. Called only for the room types that count as clears. */
    fun onCheckmark(roomKey: String, roomName: String, mark: Mark) {
        if (roomKey in credited) return
        when (mark) {
            Mark.RED -> ticks.remove(roomKey)
            Mark.WHITE, Mark.GREEN -> {
                val helpers = LinkedHashSet<String>()
                ticks[roomKey]?.forEach { (player, n) -> if (n > helpTicks) helpers += player }
                present.forEach { (player, room) -> if (room == roomKey) helpers += player }
                credited += roomKey
                ticks.remove(roomKey)
                if (helpers.isNotEmpty()) order += roomName to helpers
            }
            Mark.OTHER -> {}
        }
    }

    /** Each player's clears, in the order the rooms got their checkmark (only players with any). */
    fun clears(): Map<String, List<Clear>> {
        val out = LinkedHashMap<String, MutableList<Clear>>()
        for ((room, helpers) in order) for (p in helpers) out.getOrPut(p) { ArrayList() } += Clear(room, helpers.size == 1)
        return out
    }

    fun reset() {
        ticks.clear(); credited.clear(); order.clear(); present = emptyMap()
    }
}
