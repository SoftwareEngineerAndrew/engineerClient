package com.engineerclient.misc

import net.minecraft.network.chat.Component
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard

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
 * when the holder has no team). Mirroring that one line is the whole trick. Note it is
 * `getPlayersTeam(owner)`, which finds the team a holder is *a member of*, not
 * `getPlayerTeam(owner)`, which searches teams *by team name*: Hypixel's teams are named things
 * like `team_0`, so that lookup always misses and every prefix and suffix comes back empty.
 */
object ScoreboardLines {

    // --- config, written by the Random Stuff module each tick ---------------------------------

    /** Random Stuff's Hide Scoreboard Lines, and the module being on. */
    @JvmField var hideLines: Boolean = false

    // --- what gets hidden ----------------------------------------------------------------------
    //
    // Every sidebar line seen in recorded dungeon runs and their lobbies falls into one of these
    // kinds. Patterns match the plain text: codes and Hypixel's salt stripped, ends trimmed. A line
    // that is none of them is left alone — boss fights and the other islands were not covered, and
    // an unknown line is more likely wanted than not.

    enum class Kind {
        BLANK, DATE, SEASON, CLOCK, LOCATION_CATACOMBS, LOCATION_OTHER, PURSE, BITS, OBJECTIVE,
        OBJECTIVE_TASK, HYPE, PROTOTYPE_LOBBY, FOOTER, STARTING, TEAMMATE_LOBBY, TEAMMATE_RUN, SOLO,
        TIME_ELAPSED, KEYS, CLEARED,
    }

    /** The three kinds with a setting of their own (Random Stuff); everything else is fixed. */
    @JvmField var hideCatacombsLocation: Boolean = false
    @JvmField var hideTimeElapsed: Boolean = true
    @JvmField var hideCleared: Boolean = true

    /** Hidden whenever Hide Scoreboard Lines is on. Purse, Bits and both teammate kinds stay. */
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

    /** The line as the renderer will draw it. */
    internal fun lineText(scoreboard: Scoreboard, entry: PlayerScoreEntry): Component =
        PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName())

    // --- hiding --------------------------------------------------------------------------------

    /** True if this line is one the user asked not to see. Called per line, per frame. */
    fun shouldHide(line: Component): Boolean = hideLines && hides(plain(line))

    /**
     * The sidebar's title, which the renderer draws separately from the lines — hiding it needs its
     * own hook, see ScoreboardSidebarMixin.
     */
    fun hidesTitle(): Boolean = hideLines

    /**
     * The whole decision, over a line's plain text, so it can be tested without a running game. A
     * line on its own, out of context; the objective's task is only recognised in [visibleEntries].
     */
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
     * codes would leave that salt in place and every pattern here would miss. One line is nothing but salt,
     * "§j", which is how Hypixel draws a blank spacer.
     */
    private val FORMATTING = Regex("§.")
}
