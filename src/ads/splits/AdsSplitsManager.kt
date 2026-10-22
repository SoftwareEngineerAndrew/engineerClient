// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.splits

import com.engineerclient.EngineerClient
import com.engineerclient.EngineerClient.mc
import com.engineerclient.ads.AdsWorlds
import com.engineerclient.splits.SplitPace
import com.engineerclient.splits.SplitTracker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/** One split: the line that starts it, and the moment it came on both clocks (0: not yet). */
data class AdsSplit(val regex: Regex, val name: String, var time: Long = 0L, var ticks: Long = 0L, var real: Boolean = true)

/** A split as the HUD shows it: how long it took (or has taken so far), on both clocks. */
data class AdsSplitRow(val name: String, val time: Long, val tickTime: Long, val isCurrent: Boolean)

/**
 * The run's splits in ADS's worlds, as Odin's SplitsManager keeps them: each split is the moment its chat line came
 * (real time and the server's ticks), and a split lasts until the next one's moment. The lines are Hypixel's, which
 * the Rustclear engine and the P3 Sim both say.
 *
 * A Rustclear run starts on "Starting in 1 second.", as on Hypixel. The sim starts partway (P1, P2, P3, a section,
 * the core, P4) and says no such line, so [simStart] starts it there, and every split before is filled in backwards
 * at your F7 PB for it (or its dark green time); those splits are marked not [AdsSplit.real] and never make a PB.
 *
 * Off ADS's worlds it does nothing at all: Hypixel's runs are Odin's to time.
 */
object AdsSplitsManager {

    private var splits: List<AdsSplit> = emptyList()
    /** Which PBs this run counts towards: "F7", "M7" (Rustclear) or "Sim". */
    private var group = "F7"
    private var tickCounter = 0L
    /** The run's end lines, said this many client ticks after its last split (Odin's schedule(10)). */
    private var finishIn = -1
    private var finish: (() -> Unit)? = null

    fun init() {
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { _, _ -> reset() }
        ClientTickEvents.END_CLIENT_TICK.register {
            if (finishIn >= 0 && --finishIn < 0) EngineerClient.safely("ads splits finish") { finish?.invoke() }
        }
    }

    fun reset() {
        splits = emptyList()
        tickCounter = 0L
        finishIn = -1
        finish = null
    }

    @JvmStatic
    fun onServerTick() {
        tickCounter++
    }

    /** A chat line, as it came off the network (client thread). */
    @JvmStatic
    fun onChat(content: Component) {
        if (!AdsWorlds.active) return
        EngineerClient.safely("ads splits chat") {
            val message = content.string.replace(CODES, "")
            if (message == "Starting in 1 second.") { if (AdsWorlds.engine) startRun() } else onSplitMessage(message)
        }
    }

    private fun startRun() {
        tickCounter = 0L
        finishIn = -1
        group = if (sidebarSays("(M7)")) "M7" else "F7"
        splits = buildFloor7()
    }

    private fun buildFloor7(): List<AdsSplit> =
        (listOf(
            AdsSplit(MORT_REGEX, BLOOD_OPEN),
            AdsSplit(BLOOD_OPEN_REGEX, BLOOD_CLEAR),
            AdsSplit(PORTAL_ENTRY_REGEX, PORTAL_ENTRY),
        ) + FLOOR7 + AdsSplit(DUNGEON_CLEARED_REGEX, TOTAL)).map { it.copy() }

    private fun onSplitMessage(message: String) {
        val split = splits.find { it.regex.matches(message) } ?: return
        if (split.time != 0L) return

        split.time = System.currentTimeMillis()
        split.ticks = tickCounter

        val index = splits.indexOf(split)
        if (index == 0) return

        // The split before that came (on F7 the Wither King never speaks: Necron's runs to the end, not Cleared's).
        val previous = splits.subList(0, index).lastOrNull { it.time != 0L } ?: return
        val segmentTime = (split.time - previous.time) / 1000f

        if (index == splits.lastIndex) finishRun(previous, segmentTime)
        else if (previous.real) AdsPersonalBests.time(group, previous.name, segmentTime, "§6${previous.name} §7took §6", AdsSplits.messages)
    }

    private fun finishRun(last: AdsSplit, lastSegmentTime: Float) {
        val rows = currentRows()
        val run = splits
        val g = group
        finishIn = 10
        finish = {
            if (rows.isNotEmpty()) {
                val say = AdsSplits.messages
                if (last.real) AdsPersonalBests.time(g, last.name, lastSegmentTime, "§6${last.name} §7took §6", say)
                // The total only when the whole run was played (never in the sim, which fills its start in).
                if (run.all { it.real }) AdsPersonalBests.time(g, run.last().name, rows.last().time / 1000f, "§6Total time §7took §6", say)
                if (say) rows.forEachIndexed { i, row ->
                    if (!run[i].real || row.time == 0L) return@forEachIndexed
                    EngineerClient.msg("§6${if (i == rows.lastIndex) "Total" else row.name} §7took §6${formatTime(row.time)}§7.")
                }
            }
        }
    }

    /**
     * The P3 Sim starting a fight at [label]'s phase (SplitTracker's labels, client thread, before any of its lines):
     * a fresh run whose starting split takes its moment now, [termsDone] sections into the terminals if it starts
     * partway through them. A P3 / S1 start plays Storm's end first (StormEnd's lead-in to Goldor's line), so Storm's
     * split is started that far before its end, as on Hypixel's it runs through it.
     */
    fun simStart(label: String, termsDone: Int) {
        reset()
        group = "Sim"
        val run = buildFloor7()
        val now = System.currentTimeMillis()
        val (name, head) = if (label == SplitTracker.TERMS && termsDone == 0) {
            STORM to (target(STORM) - com.engineerclient.p3sim.StormEnd.LEAD * 50L).coerceAtLeast(0L)
        } else {
            val name = OURS.entries.firstOrNull { it.value == label }?.key ?: return
            name to (1..termsDone).mapNotNull { SplitPace.subRef("terms.s$it")?.ms }.sum()
        }
        val k = run.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return
        run[k].time = now - head
        run[k].ticks = -head / 50
        run[k].real = head == 0L
        for (j in k - 1 downTo 0) {
            val ms = target(run[j].name)
            run[j].time = run[j + 1].time - ms
            run[j].ticks = run[j + 1].ticks - ms / 50
            run[j].real = false
        }
        splits = run
    }

    /** The sim's Stop, or a practice starting: no run, as a world change leaves it. */
    fun simStop() = reset()

    /** How long [name] counts as before a sim start: your F7 PB for it, else its dark green time. */
    private fun target(name: String): Long =
        AdsPersonalBests.get("F7", name)?.let { (it * 1000).toLong() } ?: OURS[name]?.let { SplitPace.ref(it)?.ms } ?: 0L

    fun currentRows(): List<AdsSplitRow> {
        val splits = splits
        if (splits.isEmpty() || !AdsWorlds.active) return emptyList()
        if (splits[0].time == 0L) return splits.map { AdsSplitRow(it.name, 0L, 0L, isCurrent = false) }

        val last = splits.last()
        val latestTime = if (last.time != 0L) last.time else System.currentTimeMillis()
        val latestTicks = if (last.time != 0L) last.ticks else tickCounter

        val times = LongArray(splits.size).apply { this[splits.lastIndex] = latestTime - splits.first().time }
        val ticks = LongArray(splits.size).apply { this[splits.lastIndex] = latestTicks - splits.first().ticks }

        var currentIndex = -1
        for (i in 0 until splits.lastIndex) {
            val next = splits[i + 1]
            if (next.time != 0L) {
                times[i] = next.time - splits[i].time
                ticks[i] = next.ticks - splits[i].ticks
            } else {
                times[i] = latestTime - splits[i].time
                ticks[i] = latestTicks - splits[i].ticks
                currentIndex = i
                break
            }
        }
        return splits.indices.map { i -> AdsSplitRow(splits[i].name, times[i], ticks[i], isCurrent = i == currentIndex) }
    }

    /** The sidebar (its title or a line) has [text]: Rustclear names the floor there, "(F7)" or "(M7)". */
    private fun sidebarSays(text: String): Boolean {
        val board = mc.level?.scoreboard ?: return false
        val objective = board.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return false
        if (objective.displayName.string.contains(text)) return true
        return board.listPlayerScores(objective).any { e ->
            PlayerTeam.formatNameForTeam(board.getPlayersTeam(e.owner()), e.ownerName()).string.contains(text)
        }
    }

    /** "1m 2.34s", as Odin's formatTime. */
    fun formatTime(time: Long, decimals: Int = 2): String {
        if (time == 0L) return "0s"
        var remaining = time
        val hours = (remaining / 3_600_000).also { remaining -= it * 3_600_000 }
        val minutes = (remaining / 60_000).also { remaining -= it * 60_000 }
        return (if (hours > 0) "${hours}h " else "") + (if (minutes > 0) "${minutes}m " else "") +
            "%.${decimals}f".format(java.util.Locale.US, remaining / 1000f) + "s"
    }

    private val CODES = Regex("§.")

    // Odin's split names, colour codes and all.
    const val BLOOD_OPEN = "§2Blood Open"
    const val BLOOD_CLEAR = "§bBlood Clear"
    const val PORTAL_ENTRY = "§dPortal Entry"
    const val MAXOR = "§5Maxor"
    const val STORM = "§3Storm"
    const val TERMINALS = "§6Terminals"
    const val GOLDOR = "§7Goldor"
    const val NECRON = "§cNecron"
    const val CLEARED = "§4Cleared"
    const val TOTAL = "§1Total"

    /** Which of SplitTracker's labels (what the sim's fight starts at, and SplitPace's dark greens) each split is. */
    private val OURS = mapOf(
        BLOOD_OPEN to SplitTracker.OPEN, BLOOD_CLEAR to SplitTracker.BLOOD, PORTAL_ENTRY to SplitTracker.PORTAL,
        MAXOR to SplitTracker.MAXOR, STORM to SplitTracker.STORM, TERMINALS to SplitTracker.TERMS,
        GOLDOR to SplitTracker.GOLDOR, NECRON to SplitTracker.NECRON,
    )

    /** Odin's floor7SplitGroup: the boss, Maxor to the Wither King. */
    val FLOOR7 = listOf(
        AdsSplit(Regex("^\\[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$"), MAXOR),
        AdsSplit(Regex("\\[BOSS] Storm: Pathetic Maxor, just like expected\\."), STORM),
        AdsSplit(Regex("\\[BOSS] Goldor: Who dares trespass into my domain\\?"), TERMINALS),
        AdsSplit(Regex("The Core entrance is opening!"), GOLDOR),
        AdsSplit(Regex("\\[BOSS] Necron: You went further than any human before, congratulations\\."), NECRON),
        AdsSplit(Regex("^\\[BOSS] Wither King: You\\.\\.\\. again\\?$"), CLEARED),
    )

    private val PORTAL_ENTRY_REGEX = Regex("\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.")
    private val DUNGEON_CLEARED_REGEX = Regex("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$")

    // https://regex101.com/r/BXKhOI/1
    private val BLOOD_OPEN_REGEX = Regex("^\\[BOSS] The Watcher: (Congratulations, you made it through the Entrance\\.|Ah, you've finally arrived\\.|Ah, we meet again\\.\\.\\.|So you made it this far\\.\\.\\. interesting\\.|You've managed to scratch and claw your way here, eh\\?|I'm starting to get tired of seeing you around here\\.\\.\\.|Oh\\.\\. hello\\?|Things feel a little more roomy now, eh\\?)$|^The BLOOD DOOR has been opened!$")
    private val MORT_REGEX = Regex("\\[NPC] Mort: Here, I found this map when I first entered the dungeon\\.|\\[NPC] Mort: Right-click the Orb for spells, and Left-click \\(or Drop\\) to use your Ultimate!")
}
