package com.engineerclient.splits

/**
 * The blood rush, one block of lines per room.
 *
 * A room starts the moment the door into it begins falling and ends when the door out of it does,
 * so the rush tiles end to end with nothing counted twice. The first room starts with the run
 * itself — the entrance door falls as Mort speaks — and the last ends on the blood door. Every time
 * in a room's block is measured from that room's own start, so two rooms can be compared by eye.
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
class BloodRunDetail(private val detail: SplitDetail) {

    private class Room(val name: String, val start: Stamp) {
        var doorFell: Stamp? = null
        var mobKilled: Stamp? = null
        var keyPicked: Stamp? = null
        var keyBy: String = ""
        var doorOpened: Stamp? = null
        var doorBy: String = ""
    }

    private var room: Room? = null
    private var done = false
    private var roomName = ""

    /** Every room's measurements, for the averages at the end. */
    private val doorFell = mutableListOf<Long>()
    private val mobKilled = mutableListOf<Long>()
    private val keyPicked = mutableListOf<Long>()
    private val keyDelta = mutableListOf<Long>()
    private val doorOpened = mutableListOf<Long>()
    private val doorDelta = mutableListOf<Long>()
    private val totals = mutableListOf<Long>()

    fun reset() {
        room = null; done = false; roomName = ""
        for (l in listOf(doorFell, mobKilled, keyPicked, keyDelta, doorOpened, doorDelta, totals)) l.clear()
    }

    /** The room name Odin reports, kept so the next room's block can be titled with it. */
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
            emit(r)
            room = Room(roomName.ifBlank { "Room" }, at)
            return
        }

        if (msg == BLOOD_DOOR) {
            r.doorOpened = at
            emit(r)
            room = null
            done = true
            emitAverages(at)
        }
    }

    /** One room's block of lines, every time measured from that room's own start. */
    private fun emit(r: Room) {
        val end = r.doorOpened ?: return
        detail.add(SplitTracker.BLOOD, r.start, "§f" + r.name, raw = true)

        r.doorFell?.let {
            detail.add(SplitTracker.BLOOD, it, line(r.start, it, "door fell"), raw = true)
            doorFell += it.realMs - r.start.realMs
        }
        val mob = r.mobKilled
        if (mob != null) {
            detail.add(SplitTracker.BLOOD, mob, line(r.start, mob, "last mob killed"), raw = true)
            mobKilled += mob.realMs - r.start.realMs
        }
        val key = r.keyPicked
        if (key != null) {
            detail.add(SplitTracker.BLOOD, key, line(r.start, key, "key picked up" + by(r.keyBy)), raw = true)
            keyPicked += key.realMs - r.start.realMs
        }
        // The deltas are gaps between two moments rather than offsets from the room's start.
        if (mob != null && key != null) {
            detail.add(SplitTracker.BLOOD, key, gap(key.realMs - mob.realMs, key.tick - mob.tick, "key delta"), raw = true)
            keyDelta += key.realMs - mob.realMs
        }
        detail.add(SplitTracker.BLOOD, end, line(r.start, end, "door opened" + by(r.doorBy)), raw = true)
        doorOpened += end.realMs - r.start.realMs
        if (key != null) {
            detail.add(SplitTracker.BLOOD, end, gap(end.realMs - key.realMs, end.tick - key.tick, "door delta"), raw = true)
            doorDelta += end.realMs - key.realMs
        }
        detail.add(SplitTracker.BLOOD, end, line(r.start, end, "total room time"), raw = true)
        totals += end.realMs - r.start.realMs

        // A blank line, so one room's block reads apart from the next.
        detail.add(SplitTracker.BLOOD, end, " ", raw = true)
    }

    /** The whole rush averaged, in gold, once the blood door is down. */
    private fun emitAverages(at: Stamp) {
        val rows = listOf(
            doorFell to "average door fell",
            mobKilled to "average last mob killed",
            keyPicked to "average key picked up",
            keyDelta to "average key delta",
            doorOpened to "average door opened",
            doorDelta to "average door delta",
            totals to "average total room time",
        )
        for ((values, what) in rows) {
            if (values.isEmpty()) continue
            val ms = values.sum() / values.size
            detail.add(SplitTracker.BLOOD, at, "§6" + times(ms, ms / 50) + " " + what, raw = true)
        }
    }

    private fun by(who: String) = if (who.isEmpty()) "" else " §7($who)"

    private fun line(from: Stamp, at: Stamp, what: String) =
        "§f" + times(at.realMs - from.realMs, (at.tick - from.tick).toLong()) + " " + what

    private fun gap(ms: Long, ticks: Int, what: String) = "§f" + times(ms, ticks.toLong()) + " " + what

    /** "0.86s (0.85s)" — the real clock, then the server's own. */
    private fun times(ms: Long, ticks: Long) =
        SplitFormat.time(ms, true) + " §7(§b" + SplitFormat.time(ticks * 50L, true) + "§7)§f"

    private companion object {
        const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
        const val BLOOD_DOOR = "The BLOOD DOOR has been opened!"
        // Verified against the 32 recorded runs. The rank prefix is optional (unranked players have
        // none) and the whole name is missing when the key drops out of render distance.
        val KEY_PICKED = Regex("""^(?:\[[^\]]+] )?(\w+) has obtained (?:Wither|Blood) Key!$|^A (?:Wither|Blood) Key was picked up!$""")
        val WITHER_DOOR = Regex("""^(\w+) opened a WITHER door!$""")
    }
}
