package com.engineerclient.splits

/**
 * The blood rush, one column per room, built fresh every frame so the room being run counts up as
 * it happens rather than appearing once it is over.
 *
 * A room starts the moment the door into it begins falling and ends when the door out of it does,
 * so the rush tiles end to end with nothing counted twice. The first room starts with the run
 * itself — the entrance door falls as Mort speaks — and the last ends on the blood door. Every time
 * in a room's column is measured from that room's own start, so two rooms can be compared by eye.
 *
 * The chain comes straight out of chat, and a real run reads like this:
 *
 *     135  [NPC] Mort: Here, I found this map...     <- room 1 starts
 *     279  ... has obtained Wither Key!              <- room 1's key
 *     328  ... opened a WITHER door!                 <- room 1 ends, room 2 starts
 *     442  ... has obtained Wither Key!
 *     489  ... opened a WITHER door!
 *     ...
 *     878  The BLOOD DOOR has been opened!           <- the rush ends
 *
 * Two of the lines are not in chat and are measured from the world instead. **Door fell** is the
 * door's blocks turning to air, which the module watches for and feeds in. **Last mob killed** is
 * the wither key appearing on the ground: the key drops off the mob that was holding it, so the
 * moment the key item exists is the moment that mob died. Neither of those can name a player —
 * Minecraft has no kill attribution at all — so "last mob killed" is timed but never credited.
 */
class BloodRunDetail {

    private class Room(val name: String, val start: Stamp) {
        var doorFell: Stamp? = null
        var mobKilled: Stamp? = null
        var keyPicked: Stamp? = null
        var keyBy: String = ""
        var doorOpened: Stamp? = null
        var doorBy: String = ""
    }

    private val rooms = mutableListOf<Room>()
    private var room: Room? = null
    private var done = false
    private var roomName = ""

    fun reset() {
        rooms.clear(); room = null; done = false; roomName = ""
    }

    /** The room name Odin reports, kept so the next room's column can be titled with it. */
    fun onRoom(name: String) {
        if (name.isNotBlank()) roomName = name
    }

    /** The door's blocks finished turning to air. */
    fun onDoorFell(at: Stamp) {
        val r = room ?: return
        if (r.doorFell == null && at.realMs >= r.start.realMs) r.doorFell = at
    }

    /** A wither or blood key has appeared on the ground: the mob holding it just died. */
    fun onKeyDropped(at: Stamp) {
        val r = room ?: return
        if (r.mobKilled == null) r.mobKilled = at
    }

    fun onChat(msg: String, at: Stamp) {
        if (done) return

        // The run starting is the entrance door falling, which is the first room's start.
        if (room == null && msg == MORT) {
            room = Room(roomName.ifBlank { "Entrance" }, at)
            return
        }
        val r = room ?: return

        val key = KEY_PICKED.find(msg)
        if (key != null) {
            if (r.keyPicked == null) {
                r.keyPicked = at
                r.keyBy = key.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
                // A key picked up with no drop seen means the item was never in render distance.
                if (r.mobKilled == null) r.mobKilled = at
            }
            return
        }

        val door = WITHER_DOOR.find(msg)
        if (door != null) {
            r.doorOpened = at
            r.doorBy = door.groupValues[1]
            rooms += r
            room = Room(roomName.ifBlank { "Room" }, at)
            return
        }

        if (msg == BLOOD_DOOR) {
            r.doorOpened = at
            rooms += r
            room = null
            done = true
        }
    }

    /**
     * A column per room, the one being run included, and the averages once the rush is over. [now]
     * is what an unfinished line counts up to.
     */
    fun columns(now: Stamp): List<List<String>> {
        val all = rooms + listOfNotNull(room)
        if (all.isEmpty()) return emptyList()
        val out = all.map { column(it, now) }.toMutableList()
        if (done) averages()?.let { out += it }
        return out
    }

    /** One room, every time measured from that room's own start. */
    private fun column(r: Room, now: Stamp): List<String> {
        val lines = mutableListOf(NAME_COLOUR + r.name)
        r.doorFell?.let { lines += row(r.start, it, DOOR_FELL_COLOUR, "door fell") }
        r.mobKilled?.let { lines += row(r.start, it, DOOR_FELL_COLOUR, "last mob killed") }
        val key = r.keyPicked
        if (key != null) {
            lines += row(r.start, key, KEY_COLOUR, "key picked up" + by(r.keyBy))
            r.mobKilled?.let { lines += gap(key, it, KEY_COLOUR, "key delta") }
        }
        val end = r.doorOpened
        if (end != null) {
            lines += row(r.start, end, DOOR_COLOUR, "door opened" + by(r.doorBy))
            key?.let { lines += gap(end, it, DOOR_COLOUR, "door delta") }
        }
        // The room's total runs to the door out of it, or up to now while it is still being run.
        lines += row(r.start, end ?: now, TOTAL_COLOUR, "total room time")
        return lines
    }

    /** The whole rush averaged: gold text, green times, like every other line. */
    private fun averages(): List<String>? {
        val fromStart = { pick: (Room) -> Stamp? -> rooms.mapNotNull { r -> span(pick(r), r.start) } }
        val rows = listOf(
            fromStart { it.doorFell } to "average door fell",
            fromStart { it.mobKilled } to "average last mob killed",
            fromStart { it.keyPicked } to "average key picked up",
            rooms.mapNotNull { span(it.keyPicked, it.mobKilled) } to "average key delta",
            fromStart { it.doorOpened } to "average door opened",
            rooms.mapNotNull { span(it.doorOpened, it.keyPicked) } to "average door delta",
            fromStart { it.doorOpened } to "average total room time",
        )
        val out = rows.mapNotNull { (spans, what) ->
            if (spans.isEmpty()) null
            else times(spans.sumOf { it.first } / spans.size, spans.sumOf { it.second } / spans.size) +
                " " + AVERAGE_COLOUR + what
        }
        return out.ifEmpty { null }
    }

    /** How long between two moments, on both clocks, or null if either never happened. */
    private fun span(later: Stamp?, earlier: Stamp?): Pair<Long, Long>? =
        if (later == null || earlier == null) null
        else (later.realMs - earlier.realMs) to (later.tick - earlier.tick).toLong()

    private fun by(who: String) = if (who.isEmpty()) "" else " §7($who)"

    private fun row(from: Stamp, at: Stamp, colour: String, what: String) =
        times(at.realMs - from.realMs, (at.tick - from.tick).toLong()) + " " + colour + what

    private fun gap(later: Stamp, earlier: Stamp, colour: String, what: String) =
        times(later.realMs - earlier.realMs, (later.tick - earlier.tick).toLong()) + " " + colour + what

    /**
     * "§a0.86s §7(§b0.85s§7)" — the real clock in green, then the server's own in brackets. The
     * tick count is the server's own, not the real time divided down: the two coming apart is the
     * lag the run ate, and deriving one from the other would hide exactly that.
     */
    private fun times(ms: Long, ticks: Long) =
        "§a" + SplitFormat.time(ms, true) + " §7(§b" + SplitFormat.time(ticks * 50L, true) + "§7)"

    private companion object {
        const val NAME_COLOUR = "§f"
        const val DOOR_FELL_COLOUR = "§7"
        const val KEY_COLOUR = "§8"
        const val DOOR_COLOUR = "§c"
        const val TOTAL_COLOUR = "§6"
        const val AVERAGE_COLOUR = "§6"

        const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
        const val BLOOD_DOOR = "The BLOOD DOOR has been opened!"
        // Verified against the 32 recorded runs. The rank prefix is optional (unranked players have
        // none) and the whole name is missing when the key drops out of render distance.
        val KEY_PICKED = Regex("""^(?:\[[^\]]+] )?(\w+) has obtained (?:Wither|Blood) Key!$|^A (?:Wither|Blood) Key was picked up!$""")
        val WITHER_DOOR = Regex("""^(\w+) opened a WITHER door!$""")
    }
}
