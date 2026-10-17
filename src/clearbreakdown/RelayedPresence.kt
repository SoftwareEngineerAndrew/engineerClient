package com.engineerclient.clearbreakdown

/**
 * Where party members' clients say they see players (ADR Online). A sender resends everything it
 * sees every few seconds, so an entry not heard of for [staleMs] is dropped: a sender who left,
 * crashed or lost its socket must not pin someone in a room for the rest of the run.
 *
 * Nothing here touches Minecraft, so it tests headlessly.
 */
class RelayedPresence(private val staleMs: Long = 10_000) {
    private class Seen(val roomKey: String, val atMs: Long)

    private val seen = HashMap<Pair<String, String>, Seen>()

    /** [from] sees [player] in [roomKey] ("" in no room), or no longer sees them (null). */
    fun update(from: String, player: String, roomKey: String?, nowMs: Long) {
        if (roomKey == null) seen.remove(from to player) else seen[from to player] = Seen(roomKey, nowMs)
    }

    /** The freshest room someone sees [player] in: a room key, "" for no room, null when nobody does. */
    fun roomOf(player: String, nowMs: Long): String? =
        seen.entries.filter { it.key.second == player && nowMs - it.value.atMs <= staleMs }.maxByOrNull { it.value.atMs }?.value?.roomKey

    fun clear() = seen.clear()
}
