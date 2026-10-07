package com.engineerclient.pov

import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import com.odtheking.odin.utils.skyblock.dungeon.M7Phases

/**
 * POV Previews' "Show In" choice: everywhere, only in boss, only in Goldor (F7/M7 P3), or only on
 * blood rush (the dungeon starting until the blood door opens). The module feeds [onChat] and
 * [reset] from its own listeners, so it stays current while it is on.
 */
object ShowIn {
    val OPTIONS = arrayListOf("Everywhere", "Only In Boss", "Only In Goldor", "Only In Blood Rush")
    const val DESC = "Where it works. Blood rush is from the dungeon starting until the blood door opens."

    private val FORMATTING = Regex("§.")

    /** The blood door has opened this dungeon: blood rush is over. */
    private var bloodOpened = false

    /** A system chat line, straight off the network. */
    fun onChat(raw: String) {
        if (raw.replace(FORMATTING, "") == "The BLOOD DOOR has been opened!") bloodOpened = true
    }

    /** A world load: a new dungeon (or none). */
    fun reset() { bloodOpened = false }

    /** Whether [option] (an index into [OPTIONS]) allows it here and now. */
    fun allows(option: Int): Boolean = when (option) {
        1 -> DungeonUtils.inBoss
        2 -> DungeonUtils.inBoss && DungeonUtils.getF7Phase() == M7Phases.P3
        3 -> DungeonUtils.inDungeons && !DungeonUtils.inBoss && !bloodOpened
        else -> true
    }
}
