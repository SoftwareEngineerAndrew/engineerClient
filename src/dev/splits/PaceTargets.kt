package com.devgineerclient.splits

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.StringSetting
import com.odtheking.odin.features.impl.skyblock.Splits
import com.odtheking.odin.utils.skyblock.dungeon.Floor
import com.odtheking.odin.utils.skyblock.floor7SplitGroup
import java.util.Locale

/**
 * What the sub splits take from Engineer Client's Odin Splits settings (its Pace target boxes, Color
 * Based Off Time, Show PB), read by name off Odin's Splits module so this mod doesn't need Engineer
 * Client; without it, the boxes are blank (PBs are used), colours are by time and bests aren't gold.
 * Mirrors Engineer Client's OdinSplitsLook.paceTarget, minus the boxes' own settings.
 */
object PaceTargets {
    private val LABELS = listOf("Open", "Blood", "Portal", "Maxor", "Storm", "Terms", "Goldor", "Necron", "Dragons")

    /** Odin keys a PB by the split's name as it has it, colour codes included. */
    private val PB_NAMES by lazy { listOf("§2Blood Open", "§bBlood Clear", "§dPortal Entry") + floor7SplitGroup.map { it.name } }

    fun install() {
        SubSplitGrades.byTime = { bool("Color Based Off Time", true) }
        SubSplitGrades.showBest = { bool("Show PB", false) }
    }

    private fun bool(name: String, default: Boolean) = (Splits.settings[name] as? BooleanSetting)?.value ?: default

    /** [label]'s (SplitTracker's) Pace target for SplitPace: its box, else the PB, else null (its dark green). */
    fun paceTarget(label: String, master: Boolean): SplitPace.Clocks? {
        val i = SplitPace.ORDER.indexOf(label).takeIf { it >= 0 } ?: return null
        val box = LABELS.getOrNull(i)?.let { Splits.settings["${if (master) "M7" else "F7"} $it"] as? StringSetting }
        box?.value?.let { parseSeconds(it) }?.let { return secs(it) }
        return pb(i, master)
    }

    /** The PB for split [i]: the faster of the kept best and Odin's PB, each only if it is a real time. */
    private fun pb(i: Int, master: Boolean): SplitPace.Clocks? {
        val floor = if (master) "M7" else "F7"
        val id = SplitPace.ORDER.getOrNull(i)?.let { SplitPace.SPLIT_IDS[it] }
        val ours = id?.let { sid -> DungeonSplits.bestOf(floor, sid)?.let { SplitPace.clocks(sid, it) } }
        val odin = PB_NAMES.getOrNull(i)?.let { Splits.dungeonPBsList[(if (master) Floor.M7 else Floor.F7).ordinal].get(it) }
            ?.let { secs(it.toDouble()) }
            ?.takeIf { c -> if (id != null) SubSplitGrades.canBeBest(id, SubSplitGrades.value(id, c.ms, c.ticks)) else c.ms >= 1000 }
        return listOfNotNull(ours, odin).minByOrNull { it.ms }
    }

    private fun secs(s: Double) = SplitPace.Clocks(Math.round(s * 1000), Math.round(s * 20))

    private val MINUTES = Regex("""^(\d+)\s*[:m]\s*(\d+(?:\.\d+)?)?\s*s?$""")
    private val SECONDS = Regex("""^(\d+(?:\.\d+)?)\s*s?$""")

    /** A box's text as seconds: `61.5` or `1:01.5`; null when blank or not a time. */
    private fun parseSeconds(text: String): Double? {
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
}
