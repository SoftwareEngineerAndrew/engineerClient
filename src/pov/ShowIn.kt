package com.engineerclient.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases

/**
 * Where POV Previews may show: in Goldor (F7/M7 P3) or on blood rush (the dungeon starting until the blood door opens).
 * POV Previews feeds [onChat] and [reset] from its own listeners, so it stays current while it is on.
 */
object ShowIn {
    enum class Place { BLOOD_RUSH, GOLDOR }

    private val FORMATTING = Regex("§.")

    /** The blood door has opened this dungeon: blood rush is over. */
    private var bloodOpened = false

    /** A system chat line, straight off the network. */
    fun onChat(raw: String) {
        if (raw.replace(FORMATTING, "") == "The BLOOD DOOR has been opened!") bloodOpened = true
    }

    /** A world load: a new dungeon (or none). */
    fun reset() { bloodOpened = false }

    /** Whether you are in [place] right now. */
    fun isIn(place: Place): Boolean = when (place) {
        Place.GOLDOR -> DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P3
        Place.BLOOD_RUSH -> DungeonUtils.inDungeons && !DungeonUtils.inBoss && !bloodOpened
    }

    /** Whether [places] allow it here and now: none picked is everywhere, otherwise any one of them. */
    fun allows(places: Collection<Place>): Boolean = places.isEmpty() || places.any(::isIn)
}
