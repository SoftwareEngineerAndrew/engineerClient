package com.engineerclient.p3sim.clear.puzzles

/**
 * The ten puzzles of an F7 dungeon by the code the packs and Odin use, with the words each is called by. Pure data.
 */
object PuzzleKinds {
    const val BLAZE = "BLAZE"
    const val BOULDER = "BOULDER"
    const val BEAMS = "BEAMS"
    const val WATER_BOARD = "WATER_BOARD"
    const val TP_MAZE = "TP_MAZE"
    const val ICE_PATH = "ICE_PATH"
    const val WEIRDOS = "WEIRDOS"
    const val QUIZ = "QUIZ"
    const val TTT = "TTT"
    const val ICE_FILL = "ICE_FILL"

    /** The name on the tab's `Puzzles` list (Odin matches these exact words). */
    val tabNames: Map<String, String> = linkedMapOf(
        BLAZE to "Higher Or Lower", BEAMS to "Creeper Beams", WEIRDOS to "Three Weirdos", TTT to "Tic Tac Toe",
        WATER_BOARD to "Water Board", TP_MAZE to "Teleport Maze", BOULDER to "Boulder", ICE_FILL to "Ice Fill",
        ICE_PATH to "Ice Path", QUIZ to "Quiz",
    )

    /** The room pack's `puzzle.kind` word (FORMAT-rooms 7.9) to the code. */
    private val byPackKind = mapOf(
        "blaze_lower" to BLAZE, "blaze_upper" to BLAZE, "boulder" to BOULDER, "creeper_beams" to BEAMS, "ice_fill" to ICE_FILL,
        "quiz" to QUIZ, "three_weirdos" to WEIRDOS, "tic_tac_toe" to TTT, "water_board" to WATER_BOARD, "teleport_maze" to TP_MAZE,
        "ice_path" to ICE_PATH,
    )

    /** The code for a room design's `puzzle.kind`, or null (unknown word, `bomb_defuse`: F7 never has it). */
    fun fromPackKind(kind: String?): String? = kind?.let { byPackKind[it.lowercase()] }

    /** The code a rooms-pack family belongs to (`Blaze_Lower` and `Blaze_Upper` are both BLAZE), or null. */
    fun fromFamily(family: String?): String? = family?.lowercase()?.let { byPackKind[it] }

    /**
     * Puzzles that go wrong and can be reset with the Architect's First Draft (measured: Higher Or Lower, Three Weirdos and
     * Tic Tac Toe). The Quiz starts itself over after a wrong answer.
     */
    val resettable: Set<String> = setOf(BLAZE, WEIRDOS, TTT)
}
