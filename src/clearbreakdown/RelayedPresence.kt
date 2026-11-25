package com.engineerclient.clearbreakdown

/**
 * Where party members' clients say they see players (ADR Online). A sender resends everything it sees every few seconds, so
 * an entry not heard of for [staleMs] is dropped: a sender who left must not pin someone in a room for the rest of the run.
 */
class RelayedPresence(private val staleMs: Long = 10_000) {
    private class Seen(var roomKey: String, var atMs: Long)

    // player -> sender -> what that sender sees
    private val seen = HashMap<String, HashMap<String, Seen>>()

    /** [from] sees [player] in [roomKey] ("" in no room), or no longer sees them (null). */
    fun update(from: String, player: String, roomKey: String?, nowMs: Long) {
        if (roomKey == null) { seen[player]?.remove(from); return }
        val s = seen.getOrPut(player) { HashMap() }[from]
        if (s != null) { s.roomKey = roomKey; s.atMs = nowMs } else seen.getValue(player)[from] = Seen(roomKey, nowMs)
    }

    /** The freshest room someone sees [player] in: a room key, "" for no room, null when nobody does. */
    fun roomOf(player: String, nowMs: Long): String? {
        var best: Seen? = null
        for (s in seen[player]?.values ?: return null) if (nowMs - s.atMs <= staleMs && (best == null || s.atMs > best.atMs)) best = s
        return best?.roomKey
    }

    fun clear() = seen.clear()
}
