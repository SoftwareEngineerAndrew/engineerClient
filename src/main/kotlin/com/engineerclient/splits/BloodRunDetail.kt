package com.engineerclient.splits

/**
 * The blood rush, room by room, rebuilt every frame so the room being run counts up live.
 *
 * A room starts the moment the door into it starts falling and ends when the door out of it
 * starts falling, so the rooms tile end to end. Every time in a room is measured from that room's
 * own start. What a real run looks like (ticks, from a recording):
 *
 *     128  [NPC] Mort: Here, I found this map...
 *     131  36 door blocks turn to barrier            <- the start door starts falling: room 1
 *     144  those 36 barriers turn to air             <- it is down; it joins Entrance and Pipes
 *     292  a "Wither Key" armor stand appears        <- the room's last mob died
 *     304  ... has obtained Wither Key!
 *     306  TheBadOne opened a WITHER door!           <- room 1 ends, room 2 starts falling in
 *     319  36 barriers turn to air, joining Pipes and Duncan
 *     ...
 *     680  The BLOOD DOOR has been opened!           <- the rush ends
 *
 * The rooms are named from the door itself: each door that comes down sits between two rooms on
 * the dungeon map, one the rush came from and one it is going into. That works whoever is
 * rushing, and it is how the fairy room is spotted — when the door that falls is fairy's own
 * door out, the rush went through fairy, and the room before it is the one that led there.
 *
 * Nothing names the player who killed the last mob: Minecraft has no kill attribution, so that
 * line is timed and never credited.
 */
class BloodRunDetail {

    /** A room on the dungeon map, as the module looked it up. [id] tells two rooms of one name apart. */
    data class MapRoom(val id: String, val name: String, val fairy: Boolean, val entrance: Boolean)

    private class Room(var start: Stamp) {
        var name = UNNAMED
        var mapId: String? = null
        var toFairy = false
        var doorFell: Stamp? = null
        var mobKilled: Stamp? = null
        var keyPicked: Stamp? = null
        var keyBy = ""
        var doorOpened: Stamp? = null
        var doorBy = ""
    }

    private val rooms = mutableListOf<Room>()
    private var room: Room? = null
    private var started = false
    private var done = false

    fun reset() {
        rooms.clear(); room = null; started = false; done = false
    }

    /** True from Mort to the blood door — the only time the module needs to watch doors and keys. */
    val active: Boolean get() = started && !done

    /**
     * A door's blocks turned to barrier: it has started falling. Only the start door matters here
     * — every later door starts falling on the same tick as its chat line — so this just moves the
     * first room's start from Mort's line to the door itself.
     */
    fun onDoorStart(at: Stamp, a: MapRoom?, b: MapRoom?) {
        val r = room ?: return
        if (rooms.isEmpty() && r.doorFell == null && (a?.entrance == true || b?.entrance == true)) r.start = at
    }

    /** A door's barriers turned to air: it is down. [a] and [b] are the rooms either side of it. */
    fun onDoorDown(at: Stamp, a: MapRoom?, b: MapRoom?) {
        val r = room ?: return
        if (r.doorFell != null) return
        val sides = listOfNotNull(a, b)
        if (rooms.isEmpty()) {
            // At the start other doors fall too (fairy's, for one); the first room's is the one
            // out of Entrance.
            if (sides.none { it.entrance }) return
        } else if (r.doorOpened == null && at.realMs < r.start.realMs) return

        r.doorFell = at
        val previous = rooms.lastOrNull()
        val fairy = sides.firstOrNull { it.fairy }
        if (fairy != null && sides.any { it.id == previous?.mapId }) {
            // A door straight from the last room into fairy: this stretch of the rush is fairy.
            r.name = fairy.name; r.mapId = fairy.id
            previous?.toFairy = true
            return
        }
        val next = sides.firstOrNull { !it.entrance && !it.fairy && it.id != previous?.mapId }
        if (next != null) { r.name = next.name; r.mapId = next.id }
        // The door out of fairy, which the rush walked into through fairy's own open door: the
        // room before this one is the one that led there.
        if (fairy != null && previous != null && previous.mapId != fairy.id) previous.toFairy = true
    }

    /** A "Wither Key" or "Blood Key" armor stand appeared: the mob holding it just died. */
    fun onKeySpawned(at: Stamp) {
        val r = room ?: return
        if (r.mobKilled == null) r.mobKilled = at
    }

    fun onChat(msg: String, at: Stamp) {
        if (done) return
        if (!started) {
            if (msg == MORT) { started = true; room = Room(at) }
            return
        }
        val r = room ?: return

        KEY_PICKED.find(msg)?.let { key ->
            if (r.keyPicked == null) {
                r.keyPicked = at
                r.keyBy = key.groupValues[1]
                // The key never seen on the ground (out of render distance): the best we know.
                if (r.mobKilled == null) r.mobKilled = at
            }
            return
        }

        WITHER_DOOR.find(msg)?.let { door ->
            r.doorOpened = at
            r.doorBy = door.groupValues[1]
            rooms += r
            room = Room(at)
            return
        }

        if (msg == BLOOD_DOOR) {
            r.doorOpened = at
            rooms += r
            room = null
            done = true
        }
    }

    enum class Level { OFF, COMPACT, DETAILED, EXTREME }

    /** The room names so far, the one being run included. */
    fun rooms(): List<String> = all().map { it.name }

    private fun all() = rooms + listOfNotNull(room)

    fun lines(level: Level, now: Stamp, totalRow: Boolean = true): List<String> = when (level) {
        Level.OFF -> emptyList()
        Level.COMPACT -> compact(now, totalRow)
        Level.DETAILED -> detailed(now)
        Level.EXTREME -> extreme(now)
    }

    /**
     * One row per room, the five in a fixed order: `Pipes: 0.65s | 8.10s | 0.60s | 0.10s | 9.45s`,
     * the separators drawn by the HUD.
     * A row fills in as the room is run; the total waits for the room to end.
     */
    private fun compact(now: Stamp, totalRow: Boolean): List<String> {
        val out = mutableListOf<String>()
        for (r in all()) out += row(name(r) + ": ", stats(r, now))
        if (totalRow) averages()?.let { out += row(TOTAL + "Total: ", it) }
        return out
    }

    /**
     * One compact row as tab-separated cells — the name, then the five times, a missing one left
     * empty so every time stays in its own column. The HUD lays the cells out as a table, which is
     * what keeps the separators in line from row to row.
     */
    private fun row(name: String, stats: List<Pair<Long, Long>?>): String {
        val cells = stats.mapIndexed { i, s -> s?.let { COLOURS[i] + SplitFormat.seconds(it.first) }.orEmpty() }
        return (listOf(name) + cells).joinToString("\t").trimEnd('\t')
    }

    /** The same five, vertical and labelled, a blank line between rooms, then the averages. */
    private fun detailed(now: Stamp): List<String> {
        val out = mutableListOf<String>()
        for (r in all()) {
            out += name(r)
            stats(r, now).forEachIndexed { i, s -> if (s != null) out += labelled(COLOURS[i], LABELS[i], s) }
            out += ""
        }
        averages()?.let { avg ->
            avg.forEachIndexed { i, s -> if (s != null) out += labelled(COLOURS[i], LABELS[i] + " avg", s) }
        }
        return out
    }

    /**
     * Everything: the seven lines of a room with who did what, then the average of each.
     *
     *     door fell, last mob killed, key picked up {player}, key delta,
     *     door opened {player}, door delta, total room time
     */
    private fun extreme(now: Stamp): List<String> {
        val out = mutableListOf<String>()
        for (r in all()) {
            out += name(r)
            full(r, now).forEach { (i, s, who) -> if (s != null) out += labelled(FULL_COLOURS[i], FULL_LABELS[i], s) + by(who) }
            out += ""
        }
        if (done && rooms.isNotEmpty()) {
            for (i in FULL_LABELS.indices) {
                val vals = rooms.mapNotNull { full(it, now)[i].second }
                if (vals.isNotEmpty()) out += labelled(FULL_COLOURS[i], "average " + FULL_LABELS[i], mean(vals))
            }
        }
        return out
    }

    /** A time on both clocks, real then server ticks. */
    private fun span(from: Stamp?, to: Stamp?): Pair<Long, Long>? =
        if (from == null || to == null) null else (to.realMs - from.realMs) to (to.tick - from.tick).toLong()

    /**
     * door fell, last mob killed, key pickup delta, door opened delta, total — in that order. In
     * the room being run, the next thing still to happen counts up to [now]; the total is the one
     * line that is not live, since a room's time means nothing until it is over.
     */
    private fun stats(r: Room, now: Stamp): List<Pair<Long, Long>?> {
        val live = if (r === room) now else null
        return listOf(
            span(r.start, r.doorFell ?: live),
            span(r.start, r.mobKilled ?: live),
            span(r.mobKilled, r.keyPicked ?: live),
            span(r.keyPicked, r.doorOpened ?: live),
            span(r.start, r.doorOpened),
        )
    }

    private fun full(r: Room, now: Stamp): List<Triple<Int, Pair<Long, Long>?, String>> {
        val live = if (r === room) now else null
        return listOf(
            Triple(0, span(r.start, r.doorFell ?: live), ""),
            Triple(1, span(r.start, r.mobKilled ?: live), ""),
            Triple(2, span(r.start, r.keyPicked ?: live?.takeIf { r.mobKilled != null }), r.keyBy),
            Triple(3, span(r.mobKilled, r.keyPicked ?: live), ""),
            Triple(4, span(r.start, r.doorOpened ?: live?.takeIf { r.keyPicked != null }), r.doorBy),
            Triple(5, span(r.keyPicked, r.doorOpened ?: live), ""),
            Triple(6, span(r.start, r.doorOpened), ""),
        )
    }

    /** The averages, only once the rush is over — a running average of one room is noise. */
    private fun averages(): List<Pair<Long, Long>?>? {
        if (!done || rooms.isEmpty()) return null
        val cols = (0..4).map { i -> rooms.mapNotNull { stats(it, it.doorOpened!!)[i] } }
        return cols.map { if (it.isEmpty()) null else mean(it) }
    }

    private fun mean(vals: List<Pair<Long, Long>>) = vals.sumOf { it.first } / vals.size to vals.sumOf { it.second } / vals.size

    /** `label > time (ticks)`, the label and time in the line's colour — the detailed screenshot's shape. */
    private fun labelled(colour: String, label: String, s: Pair<Long, Long>) =
        "$colour$label §b> $colour" + SplitFormat.seconds(s.first) + " §8(§7" + SplitFormat.seconds(s.second * 50L) + "§8)"

    /** The room's name: purple, or pink for the room that leads into fairy. */
    private fun name(r: Room) = (if (r.toFairy) FAIRY else NAME) + r.name

    private fun by(who: String) = if (who.isEmpty()) "" else " §7$who"

    private companion object {
        const val UNNAMED = "..."
        const val NAME = "§5"
        const val FAIRY = "§d"
        const val TOTAL = "§6"

        /** The five columns, in order, and their colours: door fell dark grey, last mob light grey, then the screenshot's. */
        val LABELS = listOf("door fell", "last mob", "pickup", "opened", "room total")
        val COLOURS = listOf("§8", "§7", "§c", "§4", "§6")

        /** Extreme's seven lines, coloured to match the column each one feeds. */
        val FULL_LABELS = listOf("door fell", "last mob killed", "key picked up", "key delta", "door opened", "door delta", "total room time")
        val FULL_COLOURS = listOf("§8", "§7", "§c", "§c", "§4", "§4", "§6")

        const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."
        const val BLOOD_DOOR = "The BLOOD DOOR has been opened!"
        // Verified against the recorded runs. The rank prefix is optional (unranked players have none).
        val KEY_PICKED = Regex("""^(?:\[[^\]]+] )?(\w+) has obtained (?:Wither|Blood) Key!$|^A (?:Wither|Blood) Key was picked up!$""")
        val WITHER_DOOR = Regex("""^(\w+) opened a WITHER door!$""")
    }
}
