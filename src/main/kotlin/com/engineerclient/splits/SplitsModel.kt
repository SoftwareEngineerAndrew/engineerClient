package com.engineerclient.splits

import com.engineerclient.recorder.EcRec
import java.util.Locale

/**
 * The run's splits, copied from the team's own ChatTriggers module
 * (its EngineerSplits.js): the same eleven lines,
 * names, colours, order and format.
 *
 *     Pace       &3   everything so far
 *     Open       &a   run start -> the Watcher's first line (the blood rush)
 *     Blood      &c   the Watcher's first line -> "You have proven yourself" (the camp)
 *     Portal     &d   -> Maxor's first line
 *     Enter      &9   Open + Blood + Portal
 *     Maxor      &5   -> Storm's first line
 *     Storm      &b   -> Goldor's first line
 *     Terms      &6   -> "The Core entrance is opening!"
 *     Goldor     &e   -> Necron's first line
 *     Necron     &c   -> "All this, for nothing..."
 *     Animation  &d   -> EXTRA STATS
 *
 * Two things differ from the original, both on purpose. A split only appears once it has started,
 * rather than as a blank line. And Pace has no PB targets behind it — the original's
 * `/splits pace` numbers — so it is the run's running total.
 *
 * Nothing here touches Minecraft, so it tests headlessly: feed it chat lines with the clock
 * readings they arrived at, then ask for the lines.
 */

/** The two clocks a split is measured on: real time, and the server's own tick count. */
data class Stamp(val realMs: Long, val tick: Int)

/** One timed section. [stop] is null while it is still running — the HUD counts it up to now. */
data class Split(val label: String, val start: Stamp, val stop: Stamp?)

object SplitFormat {

    /** Plain seconds to two places, the way the original's `.toFixed(2)` wrote every split. */
    fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.2f", ms / 1000.0) + "s"

    /** The original's `formatTime` for Pace and Enter: `3m 8.2s`. */
    fun minutes(ms: Long): String {
        val s = ms / 1000.0
        return "${(s / 60).toInt()}m " + String.format(Locale.ROOT, "%.1f", s % 60) + "s"
    }

    /**
     * One line, exactly as the original wrote it:
     *
     *     ${colour}${name} &b> ${colour}${time}s &8(&7${serverTime}s&8)
     *
     * The name and the real time share the colour, the arrow is aqua, and the server's tick time
     * sits in dark-grey brackets with grey digits.
     */
    fun line(label: String, realMs: Long, ticks: Long, format: (Long) -> String = ::seconds): String {
        val colour = label.take(2).replace('&', '§')
        val name = label.drop(2)
        return "$colour$name §b> $colour${format(realMs)} §8(§7${format(ticks * 50L)}§8)"
    }

    fun line(split: Split, now: Stamp): String {
        val stop = split.stop ?: now
        return line(split.label, stop.realMs - split.start.realMs, (stop.tick - split.start.tick).toLong())
    }
}

/**
 * One run's splits. Feed every chat line to [onChat] (colour codes stripped) and read [lines] back.
 * [reset] on world load.
 */
class SplitTracker {

    private var start: Stamp? = null
    private val starts = arrayOfNulls<Stamp>(PHASES.size)
    private var end: Stamp? = null

    fun reset() {
        start = null; end = null
        starts.fill(null)
        pending = null
    }

    /** A sim run starting at a phase: its index, how long each earlier phase counts as, and how long ago it began. */
    private class Pending(val phase: Int, val before: (String) -> Clock, val head: Clock)

    /** A length on both clocks: ms and ticks. */
    data class Clock(val ms: Long, val ticks: Int)

    private var pending: Pending? = null

    /**
     * The P3 Sim: a run that begins partway, at [label]'s phase. Every phase before it is filled in
     * as [before] long, back from the moment it starts, so Pace and Enter read as if they had been
     * played. With [now], it starts then (no line of its own comes), [head] already into it;
     * otherwise on its usual line.
     */
    fun startAt(label: String, before: (String) -> Clock, now: Stamp? = null, head: Clock = Clock(0, 0)) {
        reset()
        val i = PHASES.indexOfFirst { it.label == label }.takeIf { it >= 0 } ?: return
        pending = Pending(i, before, head)
        if (now != null) fill(now)
    }

    private fun fill(at: Stamp) {
        val p = pending ?: return
        pending = null
        var s = Stamp(at.realMs - p.head.ms, at.tick - p.head.ticks)
        starts[p.phase] = s
        for (j in p.phase - 1 downTo 0) {
            val c = p.before(PHASES[j].label)
            s = Stamp(s.realMs - c.ms, s.tick - c.ticks)
            starts[j] = s
        }
        start = starts[0]
    }

    fun onChat(msg: String, at: Stamp) {
        pending?.let { p ->
            if (PHASES[p.phase].starts(msg)) fill(at)
            return
        }
        if (start == null) {
            if (msg == MORT) { start = at; starts[0] = at; rec(0, at, msg) }
            return
        }
        if (end != null) return
        if (EXTRA_STATS.matches(msg)) { end = at; rec(-1, at, msg); return }
        for (i in 1 until PHASES.size) {
            if (starts[i] == null && PHASES[i].starts(msg)) { starts[i] = at; rec(i, at, msg) }
        }
    }

    /** A split starting (or, [idx] -1, the run's end) for the recorder, with the line that did it. */
    private fun rec(idx: Int, at: Stamp, msg: String) = EcRec.line("ec.split") { o ->
        o.num("idx", idx).str("label", if (idx < 0) "end" else PHASES[idx].label).at(at).str("msg", msg)
    }

    /** Every phase that has started, in order, each running until the next one starts. */
    fun splits(): List<Split> = PHASES.indices.mapNotNull { i ->
        val from = starts[i] ?: return@mapNotNull null
        val next = (i + 1 until PHASES.size).firstNotNullOfOrNull { starts[it] }
        Split(PHASES[i].label, from, next ?: end)
    }

    /** The split with this label, if it has started. */
    fun split(label: String): Split? = splits().firstOrNull { it.label == label }

    /** The HUD: Pace, then the phases so far with Enter after Portal. */
    fun lines(now: Stamp): List<String> {
        val splits = splits()
        if (splits.isEmpty()) return emptyList()
        val out = mutableListOf(total(PACE, splits, now))
        for (split in splits) {
            out += SplitFormat.line(split, now)
            if (split.label == PORTAL) out += total(ENTER, splits.take(3), now)
        }
        return out
    }

    private fun total(label: String, splits: List<Split>, now: Stamp): String {
        var ms = 0L; var ticks = 0L
        for (s in splits) {
            val stop = s.stop ?: now
            ms += stop.realMs - s.start.realMs
            ticks += stop.tick - s.start.tick
        }
        return SplitFormat.line(label, ms, ticks, SplitFormat::minutes)
    }

    private class Phase(val label: String, val starts: (String) -> Boolean)

    companion object {
        const val MORT = "[NPC] Mort: Here, I found this map when I first entered the dungeon."

        const val PACE = "&3Pace"
        const val OPEN = "&aOpen"
        const val BLOOD = "&cBlood"
        const val PORTAL = "&dPortal"
        const val ENTER = "&9Enter"
        const val MAXOR = "&5Maxor"
        const val STORM = "&bStorm"
        const val TERMS = "&6Terms"
        const val GOLDOR = "&eGoldor"
        const val NECRON = "&cNecron"
        const val ANIMATION = "&dAnimation"

        val EXTRA_STATS = Regex("^ +> EXTRA STATS <$")

        /**
         * What starts each phase. The Watcher's greeting varies, so any line of his opens Blood;
         * the one-shot guard in [onChat] makes it the first. Every other line is the one the
         * original listed, checked against the 32 recorded F7 runs.
         */
        private val PHASES = listOf(
            Phase(OPEN) { false },
            Phase(BLOOD) { it.startsWith("[BOSS] The Watcher: ") },
            Phase(PORTAL) { it == "[BOSS] The Watcher: You have proven yourself. You may pass." },
            Phase(MAXOR) { it == "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" },
            Phase(STORM) { it == "[BOSS] Storm: Pathetic Maxor, just like expected." },
            Phase(TERMS) { it == "[BOSS] Goldor: Who dares trespass into my domain?" },
            Phase(GOLDOR) { it == "The Core entrance is opening!" },
            // The original waited for "Finally, I heard so much about you.", which F7 never says;
            // in every recording Necron's first line is this one.
            Phase(NECRON) { it == "[BOSS] Necron: You went further than any human before, congratulations." },
            Phase(ANIMATION) { it == "[BOSS] Necron: All this, for nothing..." },
        )
    }
}
