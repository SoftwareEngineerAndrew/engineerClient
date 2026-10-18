package com.engineerclient.clearbreakdown

/**
 * When a tracked run is over and won. Hypixel's end of a run:
 *
 *                          The Catacombs - Floor VII
 *
 *                     Team Score: 317 (S+)
 *            ☠ Defeated Maxor, Storm, Goldor, and Necron in 05m 31s
 *
 * The end line comes first and the boss-kill line after it, so [line] says yes once both have been
 * seen, in either order, and only once. A run that ends any other way never shows the second.
 *
 * Nothing here touches Minecraft, so it tests headlessly.
 */
class RunGate {
    private var ended = false
    private var won = false
    private var fired = false

    /** A chat line (colour codes stripped) of a tracked run: true the once the run is both over and won. */
    fun line(text: String): Boolean {
        if (fired) return false
        if (END.containsMatchIn(text)) ended = true
        else if (DEFEATED.containsMatchIn(text)) won = true
        else return false
        fired = ended && won
        return fired
    }

    fun reset() {
        ended = false; won = false; fired = false
    }

    private companion object {
        val END = Regex("^\\s*(?:Master Mode )?(?:The )?Catacombs - (?:Entrance|Floor .{1,3})$")
        val DEFEATED = Regex("^\\s*☠ Defeated ")
    }
}
