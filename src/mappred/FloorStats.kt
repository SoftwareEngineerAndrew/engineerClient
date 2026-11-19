package com.engineerclient.mappred

/** From the complete maps of each floor recorded on ADR (F and M alike). */
object FloorStats {
    /**
     * Room names a floor never has. A room on one floor is on every floor above it, so these are the names first
     * seen on a higher floor where the floors up to it would have had them at least 5 times between them.
     */
    val MISSING_1 = setOf("Deathmite", "Wizard", "Buttons", "Lava Ravine", "Withermancer", "Well", "Zodd", "Ice Fill", "Ice Path", "Quiz", "Boulder", "Lower Blaze", "Higher Blaze", "Shadow Assassin", "King Midas")
    val MISSING_2 = setOf("Deathmite", "Wizard", "Buttons", "Lava Ravine", "Withermancer", "Well", "Zodd", "Ice Fill", "Ice Path", "Quiz", "Boulder", "Lower Blaze", "Higher Blaze", "Shadow Assassin", "King Midas")
    val MISSING_3 = setOf("Wizard", "Buttons", "Lava Ravine", "Withermancer", "Zodd", "Ice Fill", "Quiz")
    val MISSING_4 = setOf("Zodd", "Ice Fill")
    val MISSING_5 = setOf("Ice Fill")
    val MISSING_6 = setOf("Ice Fill")
}
