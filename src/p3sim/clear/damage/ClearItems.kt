package com.engineerclient.p3sim.clear.damage

/**
 * The plain numbers and lists behind the items a player carries through a full run's clear, kept free of Minecraft so they test headlessly.
 * What the recordings show (real Hypixel dungeon runs):
 *  - an Ender Pearl flies everywhere in the clear, trap rooms included: the click makes a pearl entity at 1.5 blocks a tick, the throw
 *    sound is `entity.arrow.shoot` (neutral, volume 0.5, pitch 0.4 / (random * 0.4 + 0.8)) and the stack loses one pearl (133 of 135
 *    recorded throws; the hotbar pearls are used up like any other). Only the boss room refuses a pearl;
 *  - the Spirit Leap that lies on the floor of a room is an ender pearl stack of 5 with the ability "Spirit Leap", cooldown 5 s (the
 *    InfiniLeap's is 2 s), and the same teleport window;
 *  - the other secret items (Decoy, Inflatable Jerry, Trap, Defuse Kit, Training Weights, the Healing VIII splash potion) are carriers
 *    whose ability the server runs; ADS does not run them yet, so a click on one is eaten instead of falling through to vanilla.
 */
object ClearItems {
    // ---- Ender Pearl ---------------------------------------------------------------------------------------------------------

    /** Throw sound volume (`entity.arrow.shoot`, neutral). */
    const val PEARL_VOLUME = 0.5f

    /** How fast the pearl leaves the hand, in blocks a tick (measured 1.47 to 1.53 once the spread is in). */
    const val PEARL_SPEED = 1.5f

    /** The spread of the throw, vanilla's (a gaussian of 0.0075 per unit on each axis). */
    const val PEARL_SPREAD = 1.0f

    /** The throw sound's pitch for a random draw [r] in [0,1): measured 0.333 to 0.492. */
    fun pearlPitch(r: Float): Float = 0.4f / (r * 0.4f + 0.8f)

    /** Portal puffs where the pearl lands (measured 32, each at the pearl's x/z and a random height up to 2 above it). */
    const val PEARL_PUFFS = 32

    // ---- Spirit Leap ---------------------------------------------------------------------------------------------------------

    /** The Spirit Leap floor item's custom-data id. */
    const val SPIRIT_LEAP = "SPIRIT_LEAP"

    /** The InfiniLeap's id (the item the hotbar carries). */
    const val INFINILEAP = "INFINITE_SPIRIT_LEAP"

    /** Server ticks of cooldown after a leap: 2 s for the InfiniLeap (measured), 5 s for the floor item (its own lore). */
    fun leapCooldownTicks(id: String): Int = if (id == SPIRIT_LEAP) 100 else 40

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
