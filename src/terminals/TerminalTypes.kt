package com.engineerclient.terminals

import com.engineerclient.terminals.sim.*
import net.minecraft.world.item.DyeColor

/** The six terminals. The order is on the wire (party frames carry the ordinal); new ones go at the end. */
enum class TerminalTypes(val termName: String, val regex: Regex, val windowSize: Int) {
    PANES("Correct all the panes!", Regex("^Correct all the panes!$"), 45),
    RUBIX("Change all to same color!", Regex("^Change all to same color!$"), 45),
    NUMBERS("Click in order!", Regex("^Click in order!$"), 36),
    STARTS_WITH("What starts with: \"*\"?", Regex("^What starts with: '(\\w)'\\?$"), 45),
    SELECT("Select all the \"*\" items!", Regex("^Select all the ([\\w ]+) items!$"), 54),
    MELODY("Click the button on time!", Regex("^Click the button on time!$"), 54);

    fun simulator(): TermSimGUI = when (this) {
        PANES -> PanesSim
        RUBIX -> RubixSim
        NUMBERS -> NumbersSim
        STARTS_WITH -> StartsWithSim()
        SELECT -> SelectAllSim()
        MELODY -> MelodySim
    }

    companion object {
        fun handlerFor(title: String): TerminalHandler? {
            val type = entries.firstOrNull { it.regex.matches(title) } ?: return null
            return when (type) {
                PANES -> PanesHandler()
                RUBIX -> RubixHandler()
                NUMBERS -> NumbersHandler()
                STARTS_WITH -> StartsWithHandler(type.regex.find(title)?.groupValues?.get(1) ?: return null)
                SELECT -> {
                    val name = type.regex.find(title)?.groupValues?.get(1)?.replace("SILVER", "LIGHT GRAY", true) ?: return null
                    SelectHandler(DyeColor.entries.firstOrNull { it.name.replace('_', ' ').equals(name, true) } ?: return null)
                }
                MELODY -> MelodyHandler()
            }
        }
    }
}
