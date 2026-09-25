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

    /**
     * The door's blocks finished turning to air. That is the door the room just opened coming
     * down, so it is filed against the room that opened it rather than the one now being run.
     */
    fun onDoorFell(at: Stamp) {
        val r = rooms.lastOrNull() ?: return
        val opened = r.doorOpened ?: return
        if (r.doorFell == null && at.realMs >= opened.realMs) r.doorFell = at
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

    /** How much of a room is shown. */
    enum class Level { OFF, COMPACT, DETAILED, EXTREME }

    /** Every room so far, the one being run included. */
    fun rooms(): List<String> = (rooms + listOfNotNull(room)).map { it.name }

    /**
     * The rush, at the asked-for level of detail. [now] is what an unfinished line counts up to —
     * except the room total, which only appears once the room is actually over.
     */
    fun lines(level: Level, now: Stamp): List<String> = when (level) {
        Level.OFF -> emptyList()
        Level.COMPACT -> compact()
        Level.DETAILED -> detailed()
        Level.EXTREME -> extreme(now)
    }

    /** One row per room: the five times in a fixed order, so a column means the same thing every run. */
    private fun compact(): List<String> {
        val all = rooms + listOfNotNull(room)
        if (all.isEmpty()) return emptyList()
        val out = mutableListOf(HEADER + "Blood Rush")
        for (r in all) {
            val cells = stats(r).mapIndexed { i, ms -> COLOURS[i] + (ms?.let { SplitFormat.time(it, true) } ?: "-") }
            out += NAME + r.name + "§f: " + cells.joinToString(" §8| ")
        }
        averageStats()?.let { avg ->
            out += TOTAL + "Total§f: " + avg.mapIndexed { i, ms ->
                COLOURS[i] + (ms?.let { SplitFormat.time(it, true) } ?: "-")
            }.joinToString(" §8| ")
        }
        return out
    }

    /** The same five, one per line and labelled, with a blank line between rooms. */
    private fun detailed(): List<String> {
        val all = rooms + listOfNotNull(room)
        if (all.isEmpty()) return emptyList()
        val out = mutableListOf(HEADER + "Blood Rush")
        for (r in all) {
            out += NAME + r.name + ":"
            stats(r).forEachIndexed { i, ms ->
                if (ms != null) out += COLOURS[i] + LABELS[i] + " §f> §a" + SplitFormat.time(ms, true)
            }
            out += " "
        }
        averageStats()?.let { avg ->
            avg.forEachIndexed { i, ms ->
                if (ms != null) out += TOTAL + "total " + LABELS[i] + " avg §f> §a" + SplitFormat.time(ms, true)
            }
        }
        return out
    }

    /** Everything known, on both clocks, with who did what. */
    private fun extreme(now: Stamp): List<String> {
        val all = rooms + listOfNotNull(room)
        if (all.isEmpty()) return emptyList()
        val out = mutableListOf(HEADER + "Blood Rush")
        for (r in all) {
            out += NAME + r.name + ":"
            r.mobKilled?.let { out += row(r.start, it, COLOURS[0], LABELS[0]) }
            val key = r.keyPicked
            if (key != null) {
                r.mobKilled?.let { out += gap(key, it, COLOURS[1], LABELS[1] + by(r.keyBy)) }
            }
            val end = r.doorOpened
            if (end != null) {
                key?.let { out += gap(end, it, COLOURS[2], LABELS[2] + by(r.doorBy)) }
                r.doorFell?.let { out += gap(it, end, COLOURS[3], LABELS[3]) }
                out += row(r.start, end, COLOURS[4], LABELS[4])
            }
            out += " "
        }
        return out
    }

    /** The five numbers of a room, in the fixed order, null where the moment never happened. */
    private fun stats(r: Room): List<Long?> {
        val end = r.doorOpened
        return listOf(
            r.mobKilled?.let { it.realMs - r.start.realMs },
            ms(r.keyPicked, r.mobKilled),
            ms(end, r.keyPicked),
            ms(r.doorFell, end),
            // The total is the one line that is not live: a room's time means nothing until it ends.
            end?.let { it.realMs - r.start.realMs },
        )
    }

    private fun averageStats(): List<Long?>? {
        if (rooms.isEmpty()) return null
        val cols = (0..4).map { i -> rooms.mapNotNull { stats(it)[i] } }
        if (cols.all { it.isEmpty() }) return null
        return cols.map { if (it.isEmpty()) null else it.sum() / it.size }
    }

    private fun ms(later: Stamp?, earlier: Stamp?): Long? =
        if (later == null || earlier == null) null else later.realMs - earlier.realMs

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
        const val HEADER = "§a"
        const val NAME = "§d"
        const val TOTAL = "§6"

        /** The five, in the order they happen, and the colour each is read in. */
        val LABELS = listOf("droped", "pickup", "opened", "lowered", "room total")
        val COLOURS = listOf("§f", "§7", "§c", "§4", "§6")

        const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
        const val BLOOD_DOOR = "The BLOOD DOOR has been opened!"
        // Verified against the 32 recorded runs. The rank prefix is optional (unranked players have
        // none) and the whole name is missing when the key drops out of render distance.
        val KEY_PICKED = Regex("""^(?:\[[^\]]+] )?(\w+) has obtained (?:Wither|Blood) Key!$|^A (?:Wither|Blood) Key was picked up!$""")
        val WITHER_DOOR = Regex("""^(\w+) opened a WITHER door!$""")
    }
}
