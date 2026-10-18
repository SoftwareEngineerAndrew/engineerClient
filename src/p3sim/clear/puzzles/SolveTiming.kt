package com.engineerclient.p3sim.clear.puzzles

/**
 * When the three things a solve does land, in server ticks after the puzzle calls for the solve: the `PUZZLE SOLVED!` line, the
 * room's check mark turning (GREEN, or WHITE while a secret is still unfound) and the puzzle counting as Completed (the tab row then
 * shows it at its next 20-tick refresh). Pure: no Minecraft.
 */
class SolveTiming(val chat: Int, val green: Int, val complete: Int) {
    companion object {
        /**
         * The old single-number form: a lead of 0 or more turns the room green first and finishes [lead] ticks later (the tab follows the
         * green mark); a negative lead finishes at once and turns green that many ticks later (Three Weirdos: the line first, green 3 later).
         */
        fun fromLead(lead: Int): SolveTiming =
            if (lead >= 0) SolveTiming(chat = lead, green = 0, complete = lead) else SolveTiming(chat = 0, green = -lead, complete = 0)
    }
}
