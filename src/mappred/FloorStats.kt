package com.engineerclient.mappred

/**
 * The numbers [FloorRules] samples F1-F6 with (F7's are [MapStats]), from the complete maps of each floor
 * recorded on ADR (F and M alike; tools/mappred gen-floors.mjs): F1 222, F2 70, F3 69, F4 120, F5 617, F6 111.
 */
object FloorStats {
    /** Big room shape counts (1x2, 1x3, 1x4, 2x2, L) and how many maps had them: F1 alone, F2 always one of each. */
    val COMBOS_1 = arrayOf(
        intArrayOf(1, 1, 0, 1, 1, 116),
        intArrayOf(1, 1, 1, 0, 1, 82),
        intArrayOf(1, 0, 1, 1, 1, 16),
        intArrayOf(1, 1, 1, 1, 0, 7),
        intArrayOf(0, 1, 1, 1, 1, 1),
    )
    val COMBOS_2 = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 70),
    )

    /** F3 and F4 alike: 5 big rooms, sometimes 6. */
    val COMBOS_3_4 = arrayOf(
        intArrayOf(2, 2, 0, 0, 2, 26),
        intArrayOf(1, 1, 0, 1, 2, 26),
        intArrayOf(1, 2, 0, 1, 1, 25),
        intArrayOf(1, 1, 1, 0, 2, 17),
        intArrayOf(2, 0, 0, 2, 1, 16),
        intArrayOf(1, 2, 1, 0, 1, 16),
        intArrayOf(2, 0, 1, 1, 1, 14),
        intArrayOf(2, 1, 1, 1, 0, 11),
        intArrayOf(2, 1, 0, 2, 0, 9),
        intArrayOf(1, 1, 1, 1, 1, 6),
        intArrayOf(2, 1, 2, 0, 0, 5),
        intArrayOf(1, 1, 0, 2, 1, 4),
        intArrayOf(1, 0, 2, 0, 2, 3),
        intArrayOf(2, 0, 2, 0, 1, 3),
        intArrayOf(1, 2, 2, 0, 0, 2),
        intArrayOf(1, 2, 1, 1, 0, 2),
        intArrayOf(1, 0, 1, 2, 1, 1),
        intArrayOf(0, 2, 1, 0, 2, 1),
        intArrayOf(1, 0, 1, 1, 2, 1),
        intArrayOf(1, 1, 2, 0, 1, 1),
    )

    /** F5 and F6 alike: 6 big rooms. */
    val COMBOS_5_6 = arrayOf(
        intArrayOf(1, 2, 0, 1, 2, 141),
        intArrayOf(2, 1, 1, 1, 1, 124),
        intArrayOf(1, 2, 1, 0, 2, 102),
        intArrayOf(2, 1, 0, 2, 1, 43),
        intArrayOf(2, 0, 1, 1, 2, 42),
        intArrayOf(2, 2, 0, 2, 0, 38),
        intArrayOf(2, 0, 2, 0, 2, 30),
        intArrayOf(2, 1, 2, 0, 1, 27),
        intArrayOf(2, 2, 1, 1, 0, 25),
        intArrayOf(1, 2, 0, 2, 1, 22),
        intArrayOf(1, 1, 1, 1, 2, 20),
        intArrayOf(1, 2, 1, 1, 1, 18),
        intArrayOf(2, 0, 0, 2, 2, 18),
        intArrayOf(1, 1, 2, 0, 2, 9),
        intArrayOf(2, 2, 2, 0, 0, 8),
        intArrayOf(1, 1, 0, 2, 2, 8),
        intArrayOf(2, 0, 2, 1, 1, 7),
        intArrayOf(2, 0, 1, 2, 1, 7),
        intArrayOf(2, 1, 2, 1, 0, 7),
        intArrayOf(2, 1, 1, 2, 0, 6),
        intArrayOf(1, 2, 2, 0, 1, 5),
        intArrayOf(0, 1, 2, 1, 1, 5),
        intArrayOf(0, 2, 0, 2, 2, 3),
        intArrayOf(0, 0, 1, 2, 2, 2),
        intArrayOf(0, 2, 1, 2, 0, 2),
        intArrayOf(0, 1, 1, 2, 1, 2),
        intArrayOf(0, 2, 1, 1, 2, 2),
        intArrayOf(1, 0, 2, 2, 0, 1),
        intArrayOf(1, 2, 1, 2, 0, 1),
        intArrayOf(0, 0, 2, 2, 1, 1),
        intArrayOf(1, 0, 1, 2, 2, 1),
        intArrayOf(1, 1, 2, 1, 1, 1),
    )

    /** Weight of the wither path's walk length on F1-F4 (7 or 8 steps); F5-F6 have F7's 9 or 10. */
    val WALK_SHORT = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.023f, 0.06f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    /** Weights of blood's and the fairy's distance from the entrance (tiles), fitted per main grid: F1's 4x5. */
    val BLOOD_FROM_ENTRANCE_1 = floatArrayOf(0f, 0.484f, 0.545f, 0.682f, 0.637f, 0.827f, 1f, 0.851f, 0.727f, 0.727f, 0.727f, 0.727f, 0.727f, 0.727f, 0.727f, 0.727f)
    val FAIRY_FROM_ENTRANCE_1 = floatArrayOf(0f, 0.668f, 0.653f, 1f, 0.852f, 0.26f, 0.751f, 0.751f, 0.751f, 0.751f, 0.751f, 0.751f, 0.751f, 0.751f, 0.751f, 0.751f)

    /** F2-F4's 5x5. */
    val BLOOD_FROM_ENTRANCE_2_4 = floatArrayOf(0f, 0.392f, 0.155f, 0.499f, 0.626f, 0.797f, 0.87f, 1f, 0.716f, 0.716f, 0.716f, 0.716f, 0.716f, 0.716f, 0.716f, 0.716f)
    val FAIRY_FROM_ENTRANCE_2_4 = floatArrayOf(0f, 1f, 0.463f, 0.514f, 0.521f, 0.391f, 0.488f, 0.488f, 0.488f, 0.488f, 0.488f, 0.488f, 0.488f, 0.488f, 0.488f, 0.488f)

    /** F5-F6's 5x6. */
    val BLOOD_FROM_ENTRANCE_5_6 = floatArrayOf(0f, 0.113f, 0.25f, 0.353f, 0.349f, 0.661f, 0.65f, 1f, 0.818f, 0.656f, 0.656f, 0.656f, 0.656f, 0.656f, 0.656f, 0.656f)
    val FAIRY_FROM_ENTRANCE_5_6 = floatArrayOf(0f, 0.431f, 0.623f, 0.82f, 0.757f, 1f, 0.874f, 0.851f, 0.851f, 0.851f, 0.851f, 0.851f, 0.851f, 0.851f, 0.851f, 0.851f)

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
