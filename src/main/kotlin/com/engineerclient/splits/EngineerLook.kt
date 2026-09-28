package com.engineerclient.splits

import java.util.Locale

/**
 * Odin's splits, drawn the Engineer Splits way (see [OdinSplitsLook], which feeds it Odin's rows
 * and settings and puts the result on screen). Pure: rows and options in, lines out, so every case
 * tests without the game.
 *
 * The look is the team's original EngineerSplits (tools/reference/chattriggers):
 *
 *     Pace   > 3m 48.2s (3m 47.9s)     the projected finish, from the targets
 *     Open   > 14.00s (14.00s)
 *     Blood  > 63.00s (62.85s)
 *     Portal > 4.20s (4.20s)
 *     Enter  > 1m 21.2s (1m 21.1s)     Odin's Boss Entry
 *     Maxor  > 25.50s (25.45s)
 *     ...
 *
 * Everything else is Odin's: which splits there are and when each starts (its SplitsManager rows),
 * Boss Entry, Show 0 splits, Show Tick Time, Fixed Width and Split Location all mean what they mean
 * for Odin's own look.
 */
object EngineerLook {

    /** One of Odin's split rows: its name (with colour codes), time, server ticks, and whether it is running. */
    data class Row(val name: String, val ms: Long, val ticks: Long, val current: Boolean)

    /** Where the rows are from: floor 7 (F7 or M7), another dungeon floor, or elsewhere (Kuudra). */
    enum class Place { FLOOR7, DUNGEON, OTHER }

    /** [enterAfterEntry]: the Enter line only once the boss has been entered (the first three splits over). */
    data class Options(val bossEntry: Boolean, val show0: Boolean, val showTicks: Boolean, val enterAfterEntry: Boolean = false)

    /** A line: its label (colour codes included), the colour its time is drawn in, the time, and the tick time if shown. */
    data class Line(val label: String, val colour: String, val time: String, val ticks: String?)

    /** Odin's floor 7 splits, in order: what the pace targets are for. */
    val TARGET_SPLITS = listOf("Blood Open", "Blood Clear", "Portal Entry", "Maxor", "Storm", "Terminals", "Goldor", "Necron", "Cleared")

    /** The same splits by their Engineer names; the last is "Dragons" on M7. */
    fun targetLabels(master: Boolean) = listOf("Open", "Blood", "Portal", "Maxor", "Storm", "Terms", "Goldor", "Necron", if (master) "Dragons" else "Animation")

    private val DUNGEON_NAMES = mapOf("Blood Open" to "§aOpen", "Blood Clear" to "§cBlood", "Portal Entry" to "§dPortal", "Boss Entry" to "§9Enter")
    private val FLOOR7_NAMES = mapOf("Maxor" to "§5Maxor", "Storm" to "§bStorm", "Terminals" to "§6Terms", "Goldor" to "§eGoldor", "Necron" to "§cNecron")
    private const val PACE = "§3Pace"
    private const val BOSS_ENTRY = "§9Boss Entry"

    private val CODES = Regex("§.")
    private fun strip(s: String) = s.replace(CODES, "").trim()

    /** A split's label in this look: EngineerSplits' name in a dungeon, Odin's own elsewhere. */
    fun label(odinName: String, place: Place, master: Boolean): String {
        val name = strip(odinName)
        if (place != Place.OTHER) DUNGEON_NAMES[name]?.let { return it }
        if (place == Place.FLOOR7) {
            FLOOR7_NAMES[name]?.let { return it }
            if (name == "Cleared") return if (master) "§dDragons" else "§dAnimation"
        }
        return if (odinName.startsWith("§")) odinName else "§f$odinName"
    }

    /** The colour code a label starts with, which its time is drawn in too. */
    private fun colourOf(label: String) = if (label.length >= 2 && label[0] == '§') label.take(2) else "§f"

    /**
     * The lines, top to bottom. [targets] are the expected times in seconds for [TARGET_SPLITS]
     * (null for a blank box), or null where there are none (anywhere but floor 7).
     *
     * Pace is the projected finish: a split that is over counts what it took, the one running
     * counts the longer of its time so far and its target (so Pace only ever moves later once the
     * target is passed), and one not reached yet counts its target - or nothing, blank or without
     * targets, so there Pace is the time so far. On both clocks, the same way.
     *
     * As in Odin's own look, a split that hasn't started (time 0) only shows with Show 0 splits,
     * and before the run starts that is all there is to show - Pace then is the targets' total.
     */
    fun lines(rows: List<Row>, opts: Options, place: Place, master: Boolean, targets: List<Double?>?): List<Line> {
        if (rows.isEmpty()) return emptyList()
        val segments = rows.dropLast(1)
        val out = mutableListOf<Line>()

        val current = segments.indexOfFirst { it.current }
        val started = current >= 0 || rows.any { it.ms > 0 }
        if (started || opts.show0) {
            var ms = 0L; var ticks = 0L
            segments.forEachIndexed { i, s ->
                val target = targetFor(s.name, targets)
                when {
                    !started || (current >= 0 && i > current) -> { ms += secondsMs(target); ticks += secondsTicks(target) }
                    i == current -> { ms += maxOf(s.ms, secondsMs(target)); ticks += maxOf(s.ticks, secondsTicks(target)) }
                    else -> { ms += s.ms; ticks += s.ticks }
                }
            }
            out += line(PACE, ms, ticks, opts, SplitFormat::minutes)
        }

        segments.forEachIndexed { i, s ->
            if (s.ms != 0L || opts.show0) out += line(label(s.name, place, master), s.ms, s.ticks, opts, SplitFormat::seconds)
            // Odin's Boss Entry: after the third split, the first three together - with Enter
            // After Entry, only once they are all over.
            val entered = current > 2 || (current == -1 && started)
            if (opts.bossEntry && i == 2 && rows.size > 3 && (entered || !opts.enterAfterEntry)) {
                val ms = segments.take(3).sumOf { it.ms }
                val ticks = segments.take(3).sumOf { it.ticks }
                if (ms != 0L || opts.show0) out += line(label(BOSS_ENTRY, place, master), ms, ticks, opts, SplitFormat::minutes)
            }
        }
        return out
    }

    /** Every label [lines] could show for these rows, shown or not yet: what Fixed Width sizes its name column by. */
    fun allLabels(rows: List<Row>, opts: Options, place: Place, master: Boolean): List<String> {
        val segments = rows.dropLast(1)
        return listOf(PACE) + segments.map { label(it.name, place, master) } +
            (if (opts.bossEntry && rows.size > 3) listOf(label(BOSS_ENTRY, place, master)) else emptyList())
    }

    /** The running split as one line, for the Current Split HUD; null between splits or outside a run. */
    fun currentLine(rows: List<Row>, opts: Options, place: Place, master: Boolean): Line? {
        val s = rows.dropLast(1).firstOrNull { it.current } ?: return null
        return line(label(s.name, place, master), s.ms, s.ticks, opts, SplitFormat::seconds)
    }

    /** A line as text, the way EngineerSplits wrote it: `Name > time (ticks)`, the arrow aqua. */
    fun text(l: Line): String = "${l.label} §b> ${l.colour}${l.time}" + (l.ticks?.let { " §8(§7$it§8)" } ?: "")

    private fun line(label: String, ms: Long, ticks: Long, opts: Options, format: (Long) -> String) =
        Line(label, colourOf(label), format(ms), if (opts.showTicks) format(ticks * 50L) else null)

    private fun targetFor(odinName: String, targets: List<Double?>?): Double? {
        targets ?: return null
        val i = TARGET_SPLITS.indexOf(strip(odinName))
        return if (i < 0) null else targets.getOrNull(i)
    }

    private fun secondsMs(s: Double?) = if (s == null) 0L else Math.round(s * 1000)
    private fun secondsTicks(s: Double?) = if (s == null) 0L else Math.round(s * 20)

    private val MINUTES = Regex("""^(\d+)\s*[:m]\s*(\d+(?:\.\d+)?)?\s*s?$""")
    private val SECONDS = Regex("""^(\d+(?:\.\d+)?)\s*s?$""")

    /**
     * A target box's text as seconds: `61`, `61.5`, `61.5s`, `1:01.5`, `1m 1.5s`, `1m`. Blank, or
     * anything else, is null (no target).
     */
    fun parseSeconds(text: String): Double? {
        val t = text.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return null
        SECONDS.matchEntire(t)?.let { return it.groupValues[1].toDouble() }
        MINUTES.matchEntire(t)?.let { m ->
            val sec = m.groupValues[2].ifEmpty { "0" }.toDouble()
            if (t.contains(':') && m.groupValues[2].isEmpty()) return null // "1:" is not a time
            return m.groupValues[1].toDouble() * 60 + sec
        }
        return null
    }

    /** Seconds as a target box shows them: `57.06`, no trailing zeros. */
    fun formatSeconds(s: Double): String =
        String.format(Locale.ROOT, "%.2f", s).trimEnd('0').trimEnd('.')
}
