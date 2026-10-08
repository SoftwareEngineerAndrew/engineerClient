package com.engineerclient.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases

/**
 * Where a feature may show: in boss, in Goldor (F7/M7 P3), or on blood rush (the dungeon starting
 * until the blood door opens). POV Previews ticks any number of them ([allows] with a set of
 * places); Leap Extras picks one or everywhere ([Option]). The modules using it feed [onChat] and
 * [reset] from their own listeners, so it stays current while they are on.
 */
object ShowIn {
    enum class Place { BLOOD_RUSH, BOSS, GOLDOR }

    /** A single choice: everywhere, or one [Place]. */
    enum class Option(val place: Place?) {
        EVERYWHERE(null), ONLY_IN_BOSS(Place.BOSS), ONLY_IN_GOLDOR(Place.GOLDOR), ONLY_IN_BLOOD_RUSH(Place.BLOOD_RUSH)
    }

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
        Place.BOSS -> DungeonUtils.inBoss
        Place.GOLDOR -> DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P3
        Place.BLOOD_RUSH -> DungeonUtils.inDungeons && !DungeonUtils.inBoss && !bloodOpened
    }

    /** Whether [places] allow it here and now: none picked is everywhere, otherwise any one of them. */
    fun allows(places: Collection<Place>): Boolean = places.isEmpty() || places.any(::isIn)

    /** Whether [option] (an index into [Option]) allows it here and now. */
    fun allows(option: Int): Boolean = allows(listOfNotNull(Option.entries.getOrNull(option)?.place))
}
