package com.engineerclient.p3sim.clear.puzzles

import com.engineerclient.p3sim.run.PuzzleStatus

/**
 * The words the puzzle frame says, as Hypixel prints them. Pure functions, no Minecraft. Odin reads the plain string (colour codes
 * are stripped before it matches), so the wording and punctuation are what matter; the `§` colours only follow the usual
 * look of such lines and are not measured.
 */
object PuzzleLines {
    /** The tab's headline: `Puzzles: (3)` (Odin: `^Puzzles: \((\d+)\)$`). */
    fun tabHeadline(count: Int) = "Puzzles: ($count)"

    /** The tab glyphs by status: `✦` not done, `✔` done, `✖` failed. */
    fun glyph(status: PuzzleStatus) = when (status) { PuzzleStatus.UNSOLVED -> "✦"; PuzzleStatus.SOLVED -> "✔"; PuzzleStatus.FAILED -> "✖" }

    /**
     * One tab row (Odin: `^ (\w+(?: \w+)*|\?\?\?): \[([✖✔✦])] ?(?:\((\w+)\))?$`): `???` until the puzzle is revealed, then its name; the
     * solver's name in brackets once someone solved it.
     */
    fun tabRow(tabName: String, revealed: Boolean, status: PuzzleStatus, solver: String?): String {
        val name = if (revealed) tabName else "???"
        val who = if (status == PuzzleStatus.SOLVED && !solver.isNullOrEmpty()) " ($solver)" else ""
        return " $name: [${glyph(status)}]$who"
    }

    /** "Good job!" with one colour per letter, as Hypixel prints it at the end of `PUZZLE SOLVED!`. */
    const val GOOD_JOB = "§4G§r§co§r§6o§r§ed§r§a §r§2j§r§bo§r§3b§r§5!"

    /** "Yikes!" with one colour per letter, the end of `PUZZLE FAIL!`. */
    const val YIKES = "§4Y§r§ci§r§6k§r§ee§r§as§r§2!"

    /** "That's a whoopsie!" with one colour per letter: the Ice Path's own ending, in place of Yikes. */
    const val WHOOPSIE = "§4T§r§ch§r§6a§r§et§r§a'§r§2s§r§b §r§3a§r§5 §r§dw§r§4h§r§co§r§6o§r§ep§r§as§r§2i§r§be§r§3!"

    /** The Ice Path's fail reason as the puzzle reports it; [failed] prints it with Hypixel's own colours and no Yikes. */
    private const val TRAP_REASON_END = "That's a whoopsie!"

    /** `PUZZLE SOLVED! {player} {what} Good job!` where [what] is the puzzle's own phrase (`tied Tic Tac Toe!`). */
    fun solved(player: String, what: String) = "§a§lPUZZLE SOLVED! §r§b$player §r§e$what §r$GOOD_JOB"

    /**
     * `PUZZLE FAIL! {player} {reason} Yikes!` where [reason] carries its own punctuation (`killed a Blaze in the wrong order!`). The
     * Ice Path trap reason ends in "That's a whoopsie!" and takes that rainbow as its ending instead of Yikes.
     */
    fun failed(player: String, reason: String): String {
        if (reason.endsWith(TRAP_REASON_END)) {
            val lead = reason.removeSuffix(TRAP_REASON_END).trimEnd().replace("Trap!", "§r§aTrap§r§e!")
            return "§c§lPUZZLE FAIL! §r§b$player §r§e$lead §r$WHOOPSIE"
        }
        return "§c§lPUZZLE FAIL! §r§b$player §r§e$reason §r$YIKES"
    }

    /** The line the Architect's First Draft prints (green, the item's name dark purple, the puzzle's name gold). */
    fun reset(tabName: String) = "§r§aYou used the §r§5Architect's First Draft §r§ato reset §r§6$tabName§r§a!"

    /** What the sack command says when a player fetches the draft after a failed puzzle (green, the item yellow). */
    const val SACK_MOVED = "§aMoved §e1 Architect's First Draft§a from your Sacks to your inventory."

    /** `Oops! You stepped on the wrong block!` (Ice Fill: the cell under you is already covered). Red; only the player who stepped wrong sees it. */
    const val ICE_WRONG_BLOCK = "§cOops! You stepped on the wrong block!"

    /** `Don't move diagonally! Bad!` (Ice Fill: the cell is not next to the one you stepped on last). Red; only the player who stepped wrong sees it. */
    const val ICE_DIAGONAL = "§cDon't move diagonally! Bad!"
}
