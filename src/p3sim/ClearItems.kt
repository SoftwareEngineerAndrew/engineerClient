package com.engineerclient.p3sim

/**
 * The plain numbers and lists behind the items a player carries through a full run's clear, kept free of Minecraft so they test headlessly.
 * What the recordings show (real Hypixel dungeon runs):
 *  - the Spirit Leap that lies on the floor of a room is an ender pearl stack of 5 with the ability "Spirit Leap", cooldown 5 s (the
 *    InfiniLeap's is 2 s), and the same teleport window;
 *  - the other secret items (Decoy, Inflatable Jerry, Trap, Defuse Kit, Training Weights, the Healing VIII splash potion) are carriers
 *    whose ability the server runs; ADS does not run them yet, so a click on one is eaten instead of falling through to vanilla.
 */
object ClearItems {
    // ---- Spirit Leap ---------------------------------------------------------------------------------------------------------

    /** The Spirit Leap floor item's custom-data id. */
    const val SPIRIT_LEAP = "SPIRIT_LEAP"

    /** The InfiniLeap's id (the item the hotbar carries). */
    const val INFINILEAP = "INFINITE_SPIRIT_LEAP"

    /**
     * Server ticks of cooldown after a leap: 2 s for the InfiniLeap (measured), 5 s for the floor item (its own lore). A Mage's
     * Cooldown Reduction takes its share off the InfiniLeap's: 49%, or 74% for the party's only Mage (doubled stats), so about 1 s or
     * 0.5 s. DR clears: Berserk/Tank refused to 38-39 ticks after a leap, the menu from 39; a Mage with another Mage refused at 18, the
     * menu from 18; the only Mage refused at 1-3, the menu at 13. The floor item's with a Mage is not recorded (left at 5 s).
     */
    fun leapCooldownTicks(id: String, mage: Boolean = false, doubled: Boolean = false): Int =
        if (id == SPIRIT_LEAP) 100 else if (mage) Math.round(40 * (1 - if (doubled) 0.74 else 0.49)).toInt() else 40

    /** Whether a completed leap uses one of the stack up (the floor item does, the InfiniLeap never). */
    fun leapConsumes(id: String): Boolean = id == SPIRIT_LEAP

    // ---- items whose ability ADS does not run ---------------------------------------------------------------------------------

    /**
     * Custom-data id to the item's name, for every secret item whose ability is missing. Both ids of the splash potion are here: the
     * secrets module hands out `HEALING_VIII_SPLASH_POTION`, a real one carries `POTION`.
     */
    val INERT: Map<String, String> = mapOf(
        "DUNGEON_DECOY" to "Decoy",
        "INFLATABLE_JERRY" to "Inflatable Jerry",
        "DUNGEON_TRAP" to "Trap",
        "DEFUSE_KIT" to "Defuse Kit",
        "TRAINING_WEIGHTS" to "Training Weights",
        "HEALING_VIII_SPLASH_POTION" to "Healing VIII Splash Potion",
        "POTION" to "Healing VIII Splash Potion",
    )

    /** The line a click on one of those answers with (the sim's own note, not something Hypixel says). */
    fun inertNote(name: String): String = "The $name ability is not in ADS yet."

    /** Ticks between two of those notes, so a held right click does not fill the chat. */
    const val INERT_NOTE_GAP = 40
}
