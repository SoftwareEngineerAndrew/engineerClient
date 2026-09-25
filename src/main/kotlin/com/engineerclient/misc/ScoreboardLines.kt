package com.engineerclient.misc

import com.odtheking.odin.utils.modMessage
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import java.util.Optional

/**
 * The sidebar ("scoreboard") on the right of the screen: the dump that prints what is really on it,
 * and the hider that drops the noisy Skyblock lines — the date/time, the season, and in dungeons
 * the Keys and Cleared lines.
 *
 * ## How a sidebar line is actually built on this version
 *
 * Hypixel does not put a line's text in one place. Each line is a *score entry*: a score holder
 * name (usually junk, sometimes the text), an optional display Component on the entry itself, and a
 * team the holder belongs to which carries a prefix and a suffix. The visible line is all three
 * glued together, exactly the way the vanilla renderer does it in `Gui.displayScoreboardSidebar`:
 *
 *     PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName())
 *
 * where `ownerName()` is the entry's display Component if it has one and the raw holder name
 * otherwise, and `formatNameForTeam` wraps that in the team's prefix and suffix (and is null-safe
 * when the holder has no team). Mirroring that one line is the whole trick, and it is why the old
 * dump printed the right number of blank lines: it looked the team up with `getPlayerTeam(owner)`,
 * which searches teams *by team name*, not `getPlayersTeam(owner)`, which finds the team a holder
 * is *a member of*. Hypixel's teams are named things like `team_0`, so the lookup always missed,
 * every prefix and suffix came back empty, and the holder's own name was never printed at all.
 */
object ScoreboardLines {

    // --- config, written by the Random Stuff module each tick ---------------------------------

    /** Master switch. Nothing is hidden while this is false, whatever the toggles below say. */
    @JvmField var hideLines: Boolean = false

    /** Hide the real-world date/server-id line and the Skyblock time-of-day line. */
    @JvmField var hideDateTime: Boolean = false

    /** Hide the season + day line ("Late Summer 13th"). */
    @JvmField var hideSeason: Boolean = false

    /** Hide the dungeon "Keys:" line. */
    @JvmField var hideKeys: Boolean = false

    /** Hide the dungeon "Cleared: 42%" line. */
    @JvmField var hideCleared: Boolean = false

    /**
     * Extra patterns from the Random Stuff setting, separated by `;`. Plain text is matched as a
     * substring, or write a regex to anchor it. Nobody can check the built-in patterns below
     * without being on Hypixel, so this is the way out when one of them misses: run the dump, see
     * the real line, type a piece of it here, no rebuild.
     */
    @JvmField var customPatterns: String = ""

    // --- what each toggle matches --------------------------------------------------------------
    //
    // UNVERIFIED. These are written from memory of Hypixel's sidebar, not from a capture: nobody
    // has run the fixed dump in-game yet. Run "Dump Scoreboard" on Skyblock and in a dungeon and
    // correct these against the strings it prints — that is what the dump exists for. Each is
    // matched against the line's *plain* text (colour codes stripped, ends trimmed), so the
    // patterns never have to mention §-codes, and each is anchored so a pattern cannot quietly
    // eat a line it was not meant to.

    /** UNVERIFIED — the top line, e.g. "11/12/23 m1CK" (real-world date plus the server id). */
    private val REAL_DATE_PATTERN = Regex("""^\W*\d{2}/\d{2}/\d{2}\b.*$""")

    /** UNVERIFIED — the Skyblock clock, e.g. " ☀ 12:10pm" (the sun/moon glyph leads the line). */
    private val TIME_OF_DAY_PATTERN = Regex("""^\W*\d{1,2}:\d{2}\s*[ap]m\b.*$""", RegexOption.IGNORE_CASE)

    /** UNVERIFIED — the season and day, e.g. "Late Summer 13th" or "Spring 1st". */
    private val SEASON_PATTERN = Regex("""^\W*(?:Early|Late)?\s*(?:Spring|Summer|Autumn|Winter)\s+\d{1,2}(?:st|nd|rd|th)\b.*$""", RegexOption.IGNORE_CASE)

    /** UNVERIFIED — the dungeon key counter, e.g. "Keys: ☠ x1 ✦". */
    private val KEYS_PATTERN = Regex("""^\W*Keys:.*$""", RegexOption.IGNORE_CASE)

    /** UNVERIFIED — the dungeon clear percentage, e.g. "Cleared: 42% (180)". */
    private val CLEARED_PATTERN = Regex("""^\W*(?:Dungeon\s+)?Cleared:\s*\d+%.*$""", RegexOption.IGNORE_CASE)

    // --- reading the sidebar -------------------------------------------------------------------

    private val mc: Minecraft get() = Minecraft.getInstance()

    /** Vanilla draws at most this many sidebar lines, however many scores the server sent. */
    private const val MAX_SIDEBAR_LINES = 15

    /**
     * The line as the renderer will draw it. Kept in one place so the dump and the hider can never
     * disagree about what a line says — if they did, a pattern built from the dump would not match.
     */
    private fun lineText(scoreboard: Scoreboard, entry: PlayerScoreEntry): Component =
        PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName())

    /**
     * Sidebar lines in the order they appear on screen: hidden holders (the `#`-prefixed ones the
     * server uses as scratch space) dropped, sorted highest score first with ties broken on the
     * holder name, and cut to the fifteen lines vanilla will actually draw — same as
     * `Gui.displayScoreboardSidebar`, so a dump index is the line you are looking at.
     */
    private fun sidebarEntries(scoreboard: Scoreboard, objective: Objective): List<PlayerScoreEntry> =
        scoreboard.listPlayerScores(objective)
            .filter { !it.isHidden }
            .sortedWith(
                compareByDescending<PlayerScoreEntry> { it.value() }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() }
            )
            .take(MAX_SIDEBAR_LINES)

    /**
     * Prints every sidebar line to chat so a hider pattern can be written against the real text.
     *
     * §-codes are printed as `&` on purpose: printed as §, chat would *apply* them and you would
     * see colour instead of the codes you need to match. The plain text is printed alongside,
     * because that — not the coloured form — is what the patterns above are matched against.
     */
    fun dump() {
        val scoreboard = mc.level?.scoreboard
        val objective = scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (scoreboard == null || objective == null) {
            modMessage("§cNo sidebar is showing right now — open one first.")
            return
        }
        val entries = sidebarEntries(scoreboard, objective)
        modMessage("§a--- Scoreboard dump: §f${objective.displayName.string}§a (${entries.size} lines) ---")
        modMessage("§8(§ shown as &; the second column is what the hider matches)")
        entries.forEachIndexed { i, entry ->
            val component = lineText(scoreboard, entry)
            val coded = toLegacy(component).replace('§', '&')
            modMessage("§7$i: §f$coded §8| §7${plain(component)}")
        }
    }

    // --- hiding --------------------------------------------------------------------------------

    /** True if this line is one the user asked not to see. Called per line, per frame. */
    fun shouldHide(line: Component): Boolean = hideLines && hides(plain(line))

    /**
     * The whole decision, over a line's plain text. Split out from [shouldHide] so it can be tested
     * without a running game: everything above this point needs Minecraft, nothing below it does.
     */
    internal fun hides(text: String): Boolean {
        if (text.isEmpty()) return false
        if (hideDateTime && (REAL_DATE_PATTERN.matches(text) || TIME_OF_DAY_PATTERN.matches(text))) return true
        if (hideSeason && SEASON_PATTERN.matches(text)) return true
        if (hideKeys && KEYS_PATTERN.matches(text)) return true
        if (hideCleared && CLEARED_PATTERN.matches(text)) return true
        return matchesCustom(text)
    }

    /**
     * The user's own patterns. Each is tried as a regex and, if it isn't one (or matches nothing),
     * as a plain case-insensitive substring — someone pasting a piece of a line out of the dump
     * should not have to know what a regex is, or escape the brackets Hypixel puts in its lines.
     */
    private fun matchesCustom(text: String): Boolean {
        if (customPatterns.isBlank()) return false
        if (customPatterns != compiledFrom) {
            compiledFrom = customPatterns
            compiled = customPatterns.split(';').mapNotNull { part ->
                val p = part.trim()
                if (p.isEmpty()) null
                else runCatching { Regex(p, RegexOption.IGNORE_CASE) }.getOrNull() to p
            }
        }
        return compiled.any { (regex, literal) ->
            regex?.containsMatchIn(text) == true || text.contains(literal, ignoreCase = true)
        }
    }

    private var compiledFrom: String? = null
    private var compiled: List<Pair<Regex?, String>> = emptyList()

    /**
     * What the sidebar renderer gets to draw — see ScoreboardSidebarMixin. Filtering here rather
     * than at the draw call means the box shrinks around the lines that are left, instead of
     * leaving a gap where a hidden line was.
     */
    fun visibleEntries(scoreboard: Scoreboard, objective: Objective): Collection<PlayerScoreEntry> {
        val all = scoreboard.listPlayerScores(objective)
        if (!hideLines) return all
        return all.filter { !shouldHide(lineText(scoreboard, it)) }
    }

    // --- text helpers --------------------------------------------------------------------------

    /** Colour codes stripped, ends trimmed: the form every pattern above is written against. */
    private fun plain(component: Component): String = component.string.replace(FORMATTING, "").trim()

    private val FORMATTING = Regex("§[0-9a-fk-orA-FK-OR]")

    /**
     * Component tree flattened back to a §-coded string. Components carry style as objects, not as
     * codes, so this walks the tree and re-emits the legacy code for each run — the form Hypixel
     * sent and the form anyone writing a pattern is used to reading.
     */
    private fun toLegacy(component: Component): String {
        val out = StringBuilder()
        component.visit<Unit>({ style: Style, text: String ->
            out.append(codesFor(style)).append(text)
            Optional.empty()
        }, Style.EMPTY)
        return out.toString()
    }

    private fun codesFor(style: Style): String {
        val out = StringBuilder()
        // Colour first: in the legacy scheme a colour code clears bold/italic/etc., so anything
        // else has to come after it to survive. A custom RGB colour has no legacy code at all and
        // is simply left out — Hypixel's sidebar uses the sixteen named colours.
        style.color?.let { color -> legacyColour(color)?.let { out.append('§').append(it.char) } }
        if (style.isBold) out.append("§l")
        if (style.isStrikethrough) out.append("§m")
        if (style.isUnderlined) out.append("§n")
        if (style.isItalic) out.append("§o")
        if (style.isObfuscated) out.append("§k")
        return out.toString()
    }

    private val LEGACY_COLOURS: Map<TextColor, ChatFormatting> =
        ChatFormatting.values().filter { it.isColor }.associateBy { TextColor.fromLegacyFormat(it)!! }

    private fun legacyColour(color: TextColor): ChatFormatting? = LEGACY_COLOURS[color]
}
