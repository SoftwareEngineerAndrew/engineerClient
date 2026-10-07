package com.engineerclient.misc

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import java.util.Optional

/**
 * The sidebar ("scoreboard") on the right of the screen: the hider that drops the noisy Skyblock lines — the date/time, the season, and in dungeons
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

    /** Random Stuff's Hide Scoreboard Lines, and the module being on. */
    @JvmField var hideLines: Boolean = false

    // --- what gets hidden ----------------------------------------------------------------------
    //
    // Every line the sidebar showed across the recordings falls into one of these kinds (see
    // tools/scoreboard/classify.py and the Scoreboard Lines page, where each was chosen Show or
    // Hide). Patterns match the plain text: codes and Hypixel's salt stripped, ends trimmed. A line
    // that is none of them is left alone — the recordings never covered the boss fights or the other
    // islands, and an unknown line is more likely wanted than not.

    enum class Kind {
        BLANK, DATE, SEASON, CLOCK, LOCATION_CATACOMBS, LOCATION_OTHER, PURSE, BITS, OBJECTIVE,
        OBJECTIVE_TASK, HYPE, PROTOTYPE_LOBBY, FOOTER, STARTING, TEAMMATE_LOBBY, TEAMMATE_RUN, SOLO,
        TIME_ELAPSED, KEYS, CLEARED,
    }

    /** The three kinds with a setting of their own (Random Stuff); everything else is fixed. */
    @JvmField var hideCatacombsLocation: Boolean = false
    @JvmField var hideTimeElapsed: Boolean = true
    @JvmField var hideCleared: Boolean = true

    /** Chosen Hide on the Scoreboard Lines page. Purse, Bits and both teammate kinds stay. */
    private val ALWAYS_HIDDEN = setOf(
        Kind.BLANK, Kind.DATE, Kind.SEASON, Kind.CLOCK, Kind.LOCATION_OTHER, Kind.OBJECTIVE,
        Kind.OBJECTIVE_TASK, Kind.HYPE, Kind.PROTOTYPE_LOBBY, Kind.FOOTER, Kind.STARTING, Kind.SOLO,
        Kind.KEYS,
    )

    private const val NUM = """\d[\d,.]*"""

    /** First match wins, so the Catacombs location sits ahead of every other location. */
    private val KINDS: List<Pair<Kind, Regex>> = listOf(
        Kind.DATE to Regex("""^\d{2}/\d{2}/\d{2}\s+\S+.*$"""),
        Kind.SEASON to Regex("""^(?:Early |Late )?(?:Spring|Summer|Autumn|Winter) \d{1,2}(?:st|nd|rd|th)$"""),
        Kind.CLOCK to Regex("""^\d{1,2}:\d{2}(?:am|pm)(?: [☀☽])?$"""),
        // Locations lead with Hypixel's location glyph (U+E067), which is how they are told apart.
        Kind.LOCATION_CATACOMBS to Regex("""^\W*The Catacombs \((?:[FM][1-7]|E)\)$"""),
        Kind.LOCATION_OTHER to Regex("""^\uE067 .+$"""),
        Kind.PURSE to Regex("""^(?:Purse|Piggy): $NUM(?: \(\+$NUM\))?$"""),
        Kind.BITS to Regex("""^Bits: $NUM(?: \(\+$NUM\))?$"""),
        Kind.OBJECTIVE to Regex("""^Objective(?: \S)?$"""),
        Kind.HYPE to Regex("""^Hype: $NUM/$NUM$"""),
        Kind.PROTOTYPE_LOBBY to Regex("""^(?:Games in this lobby are|under heavy development!|Report bugs and leave|feedback at|hypixel\.net/ptl)$"""),
        Kind.FOOTER to Regex("""^www\.hypixel\.net$"""),
        Kind.STARTING to Regex("""^(?:Starting in|Auto-closing in): .+$"""),
        Kind.TEAMMATE_LOBBY to Regex("""^\[[MHABT]\] \w{1,16} \[Lv\d*\]?$"""),
        Kind.TEAMMATE_RUN to Regex("""^\[[MHABT]\] \w{1,16} $NUM❤?$"""),
        Kind.SOLO to Regex("""^Solo$"""),
        Kind.TIME_ELAPSED to Regex("""^Time Elapsed: .+$"""),
        Kind.KEYS to Regex("""^Keys: .+$"""),
        Kind.CLEARED to Regex("""^Cleared: $NUM% \($NUM\)$"""),
    )

    /**
     * Which kind a line is, from its plain text and the kind of the line above it — the objective's
     * task ("Talk to Enid") is free text, known only by sitting right under "Objective".
     */
    internal fun kindOf(text: String, previous: Kind?): Kind? {
        val t = text.trim()
        if (t.isEmpty()) return Kind.BLANK
        KINDS.firstOrNull { it.second.matches(t) }?.let { return it.first }
        return if (previous == Kind.OBJECTIVE) Kind.OBJECTIVE_TASK else null
    }

    internal fun hidesKind(kind: Kind?): Boolean = when (kind) {
        null -> false
        Kind.LOCATION_CATACOMBS -> hideCatacombsLocation
        Kind.TIME_ELAPSED -> hideTimeElapsed
        Kind.CLEARED -> hideCleared
        else -> kind in ALWAYS_HIDDEN
    }

    /** Vanilla's sidebar order: highest score first, ties by holder name. */
    private val DISPLAY_ORDER = compareByDescending<PlayerScoreEntry> { it.value() }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.owner() }

    /** Vanilla draws at most this many sidebar lines, however many scores the server sent. */
    private const val MAX_SIDEBAR_LINES = 15

    /** The line as the renderer will draw it. */
    internal fun lineText(scoreboard: Scoreboard, entry: PlayerScoreEntry): Component =
        PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName())

    /**
     * Sidebar lines in the order they appear on screen: hidden holders (the `#`-prefixed ones the
     * server uses as scratch space) dropped, sorted highest score first with ties broken on the
     * holder name, and cut to the fifteen lines vanilla will actually draw — same as
     * `Gui.displayScoreboardSidebar`.
     */
    internal fun sidebarEntries(scoreboard: Scoreboard, objective: Objective): List<PlayerScoreEntry> =
        scoreboard.listPlayerScores(objective)
            .filter { !it.isHidden }
            .sortedWith(DISPLAY_ORDER)
            .take(MAX_SIDEBAR_LINES)

    // --- hiding --------------------------------------------------------------------------------

    /** True if this line is one the user asked not to see. Called per line, per frame. */
    fun shouldHide(line: Component): Boolean = hideLines && hides(plain(line))

    /**
     * The sidebar's title, which the renderer draws separately from the lines — hiding it needs its
     * own hook, see ScoreboardSidebarMixin.
     */
    fun hidesTitle(): Boolean = hideLines

    /**
     * The whole decision, over a line's plain text. Split out from [shouldHide] so it can be tested
     * without a running game: everything above this point needs Minecraft, nothing below it does.
     */
    /** Like [hides] but takes a line exactly as Hypixel sent it, salt and all. */
    internal fun hidesRaw(raw: String): Boolean = hides(raw.replace(FORMATTING, ""))

    /** A line on its own, out of context; the objective's task is only recognised in [visibleEntries]. */
    internal fun hides(raw: String, previous: Kind? = null): Boolean =
        hidesKind(kindOf(raw, previous))

    /**
     * What the sidebar renderer gets to draw — see ScoreboardSidebarMixin. Filtering here rather
     * than at the draw call means the box shrinks around the lines that are left, instead of
     * leaving a gap where a hidden line was.
     */
    fun visibleEntries(scoreboard: Scoreboard, objective: Objective): Collection<PlayerScoreEntry> {
        val all = scoreboard.listPlayerScores(objective)
        if (!hideLines) return all
        val hidden = HashSet<PlayerScoreEntry>()
        var previous: Kind? = null
        for (entry in all.filter { !it.isHidden }.sortedWith(DISPLAY_ORDER)) {
            val text = plain(lineText(scoreboard, entry))
            val kind = kindOf(text, previous)
            if (hidesKind(kind)) hidden += entry
            previous = kind
        }
        return all.filter { it !in hidden }
    }

    // --- text helpers --------------------------------------------------------------------------

    /** Codes stripped, ends trimmed: the form every pattern above is written against. */
    internal fun plain(component: Component): String = component.string.replace(FORMATTING, "").trim()

    /**
     * Every § and the character after it, not just the legacy colour and format codes.
     *
     * A scoreboard cannot hold two identical lines, so Hypixel makes each one unique by salting it
     * with a § followed by some letter outside the legacy set — and it lands mid-word, so a real
     * sidebar says "Early Summer 19§wth" and "The Catac§uombs (F7)". Stripping only the legacy
     * codes left that salt in place and every pattern here missed. One line is nothing but salt,
     * "§j", which is how Hypixel draws a blank spacer.
     */
    private val FORMATTING = Regex("§.")

    /**
     * Component tree flattened back to a §-coded string. Components carry style as objects, not as
     * codes, so this walks the tree and re-emits the legacy code for each run — the form Hypixel
     * sent and the form anyone writing a pattern is used to reading.
     */
    internal fun toLegacy(component: Component): String {
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
        style.color?.let { color -> legacyColour(color)?.let { out.append(it.toString()) } }
        if (style.isBold) out.append("§l")
        if (style.isStrikethrough) out.append("§m")
        if (style.isUnderlined) out.append("§n")
        if (style.isItalic) out.append("§o")
        if (style.isObfuscated) out.append("§k")
        return out.toString()
    }

    private val LEGACY_COLOURS: Map<TextColor, ChatFormatting> =
        ChatFormatting.values().filter { TextColor.fromLegacyFormat(it) != null }.associateBy { TextColor.fromLegacyFormat(it)!! }

    private fun legacyColour(color: TextColor): ChatFormatting? = LEGACY_COLOURS[color]
}
