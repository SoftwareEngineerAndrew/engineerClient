package com.engineerclient.splits

/**
 * The blood rush, room by room.
 *
 * Between Mort's map and the blood door the party runs a chain of rooms, each one ending in a wither
 * door and the last in the blood door. The Blood split times that whole chain as one number, which
 * tells you the rush was slow but not which room ate the time. This fills the Blood split's detail
 * HUD with a line per room instead: the door into it, the wither key that opened the next one, how
 * long the room took and how long the party spent walking to the one after it. When the blood door
 * finally opens it adds an average of each of those, which is the number worth comparing between
 * runs — a single slow room is noise, a door average of three seconds is a habit.
 *
 * Only two things are known for certain here, and both come from chat Hypixel actually sends: who
 * opened a wither door, and who picked up a wither key. Everything else — which room those belong
 * to, when the party got there — comes from the room name, read once a tick. That is why a room is
 * only counted when a door opened into it: the room name also changes when someone backtracks
 * through a door that was already open, and those are not rooms the rush is waiting on.
 *
 * ASKED FOR AND DELIBERATELY ABSENT: "how long the last mob took to die, and who killed it". There
 * is no kill attribution anywhere in Minecraft, Odin or Hypixel chat, and no chat line for a mob
 * dying at all, so neither the time nor the player can be known — not approximately, not by guessing
 * from the key pickup. It is left out rather than shipped as something that looks like it works.
 *
 * No Minecraft types are touched, so this tests headlessly: feed it chat lines, room names and the
 * clock readings they arrived at.
 */
class BloodRunDetail(private val detail: SplitDetail) {

    // The room being timed right now. Null between the door opening and the party walking in.
    private var enteredAt: Stamp? = null
    private var keyAt: Stamp? = null

    // The door that opened into the room we are waiting to walk into.
    private var doorAt: Stamp? = null

    // The most recent wither key pickup, held until a door is opened with it and then consumed. A
    // door opened with no key waiting is one that was already unlocked by an earlier key, so it gets
    // no time — see [onChat].
    private var keyInHand: Stamp? = null

    // The room name the last tick reported, and which cell of the dungeon's 32-block grid the
    // player was standing in. The cell is what actually decides "we are in the next room now":
    // a rush runs through rooms that often share a name, so waiting for the name to change misses
    // most of the entries and with them most of the gaps.
    private var lastRoomName = ""
    private var lastCell = Int.MIN_VALUE

    // Set once the blood door is open: the rush is over and the blood room is not one of its rooms.
    private var done = false

    private val doorTimes = mutableListOf<Long>()
    private val keyTimes = mutableListOf<Long>()
    private val roomTimes = mutableListOf<Long>()
    private val gapTimes = mutableListOf<Long>()

    fun reset() {
        enteredAt = null; keyAt = null; doorAt = null; keyInHand = null
        lastRoomName = ""; lastCell = Int.MIN_VALUE; done = false
        doorTimes.clear(); keyTimes.clear(); roomTimes.clear(); gapTimes.clear()
    }

    /** Every chat line, colour codes already stripped. */
    fun onChat(msg: String, at: Stamp) {
        if (done) return

        WITHER_DOOR.find(msg)?.let { m ->
            // Opening the door out of a room is the moment that room is finished with, so it closes
            // the one being timed before starting the wait for the next.
            closeRoom(at)
            doorAt = at
            // Time from the key being in someone's hand to the door actually opening: a door that is
            // slow here is the party regrouping, not the room being hard. The first door of the rush
            // has no key before it, and so no time — it still names the opener, and is left out of
            // the average rather than being counted as zero.
            val key = keyInHand
            keyInHand = null
            val who = m.groupValues[1]
            if (key == null) {
                detail.add(SplitTracker.BLOOD, at, "Door §7($who)")
            } else {
                val ms = at.realMs - key.realMs
                doorTimes += ms
                detail.add(SplitTracker.BLOOD, at, "Door ${SplitFormat.time(ms, true)} §7($who)")
            }
            return
        }

        WITHER_KEY.find(msg)?.let { m ->
            // The key drops in the room the party is standing in and opens the next room's door, so
            // it is timed from walking into this room: that is how long the room took to solve.
            keyInHand = at
            val start = enteredAt ?: return
            if (keyAt != null) return   // a second key in one room is a spare; the first is the one that cost time
            keyAt = at
            val ms = at.realMs - start.realMs
            keyTimes += ms
            // Hypixel drops the player's name when the key falls to someone out of render distance,
            // so the line is written without an opener rather than with a guessed one.
            val who = m.groupValues.getOrNull(1).orEmpty()
            val suffix = if (who.isEmpty()) "" else " §7($who)"
            detail.add(SplitTracker.BLOOD, at, "Key ${SplitFormat.time(ms, true)}$suffix")
            return
        }

        // The blood door ends the last room. Hypixel announces it without a name — unlike a wither
        // door, nobody is credited with opening it — so there is no opener to record here.
        if (msg == BLOOD_DOOR || msg == SHIVER) closeRoom(at)
    }

    /**
     * Once a tick, with the room's name and the player's position. Rooms sit on a 32-block grid,
     * so the cell the player stands in says which room they are in even when two rooms in a row
     * are both called "Corridor".
     */
    fun onTick(currentRoom: String, x: Double, z: Double, at: Stamp) {
        if (done) return
        if (currentRoom.isNotBlank()) lastRoomName = currentRoom
        val cell = cellOf(x, z)
        if (cell == lastCell) return
        lastCell = cell

        // A new room with no door waiting on it is the party walking back through something already
        // open, which is not a room the rush is held up by. Ignored on purpose.
        val door = doorAt ?: return
        if (enteredAt != null) return
        doorAt = null
        enteredAt = at
        keyAt = null
        detail.add(SplitTracker.BLOOD, at, "Entered " + lastRoomName.ifBlank { "room" })
        // The walk from the door opening to being inside: the "space before next room", and the part
        // of a slow rush that no amount of clearing faster will fix.
        val ms = at.realMs - door.realMs
        gapTimes += ms
        detail.add(SplitTracker.BLOOD, at, "Gap ${SplitFormat.time(ms, true)}")
    }

    /** Called when the blood door opens: emit the averages. */
    fun onBloodDoorOpen(at: Stamp) {
        if (done) return
        closeRoom(at)
        done = true
        detail.average(doorTimes)?.let { detail.add(SplitTracker.BLOOD, at, "Door $it") }
        detail.average(keyTimes)?.let { detail.add(SplitTracker.BLOOD, at, "Key $it") }
        detail.average(roomTimes)?.let { detail.add(SplitTracker.BLOOD, at, "Room $it") }
        detail.average(gapTimes)?.let { detail.add(SplitTracker.BLOOD, at, "Gap $it") }
    }

    /**
     * Finishes the room being timed, from walking in to the door out of it opening. The walk in is
     * left to the gap, so a room's time is the time the party was actually inside it, and gap plus
     * room covers the rush end to end with nothing counted twice.
     */
    private fun closeRoom(at: Stamp) {
        val start = enteredAt ?: return
        enteredAt = null
        keyAt = null
        val ms = at.realMs - start.realMs
        roomTimes += ms
        detail.add(SplitTracker.BLOOD, at, "Room ${SplitFormat.time(ms, true)}")
    }

    /** Which 32-block room cell a position is in — the same grid the dungeon map is drawn on. */
    private fun cellOf(x: Double, z: Double): Int {
        val cx = (Math.floor(x).toInt() + 200) shr 5
        val cz = (Math.floor(z).toInt() + 200) shr 5
        return cx * 64 + cz
    }

    private companion object {
        // Both verified against the 32 recorded F7 runs; see SplitEvents for the lines themselves.
        // The rank prefix is optional because unranked players have none, and the player group is
        // optional because the pickup line loses the name when the key drops out of render distance.
        val WITHER_DOOR = Regex("""^(\w+) opened a WITHER door!$""")
        val WITHER_KEY = Regex("""^(?:\[[^\]]+] )?(\w+) has obtained Wither Key!$|^A Wither Key was picked up!$""")
        const val BLOOD_DOOR = "The BLOOD DOOR has been opened!"
        const val SHIVER = "A shiver runs down your spine..."
    }
}
