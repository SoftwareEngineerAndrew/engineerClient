package com.engineerclient.p3sim.clear.hud

import com.engineerclient.p3sim.run.PlayerRecord
import com.engineerclient.p3sim.run.RunStage
import com.engineerclient.p3sim.run.RunState
import java.util.UUID

/** The player-side numbers the action bar and the sidebar print (SkyBlock stats). From the run-state pack's preset; see [HudModule]. */
data class HudStats(
    val maxHp: Int,
    val defense: Int,
    val maxMana: Int,
    /** Maximum vitality; null: the vitality segment is not shown. */
    val vitality: Int?,
    val sbLevel: Int = HudConstants.DEFAULT_SB_LEVEL,
    val classLevel: Int = HudConstants.DEFAULT_CLASS_LEVEL,
) {
    companion object {
        /** The "typical gear" preset of the pack, used when no pack or preset is there. */
        val DEFAULT = HudStats(maxHp = 11351, defense = 3681, maxMana = 13444, vitality = HudConstants.DEFAULT_VITALITY)
    }
}

/**
 * Everything the pure channel formatters read, gathered once per tick by the HUD module. [state] is the run's hub; the rest is
 * the clock and the few values that are not in the hub. Immutable per use; tests build one directly.
 */
class HudContext(
    val state: RunState,
    val stage: RunStage,
    /** `F7`. */
    val floor: String,
    /** The run clock (`Run.t`): negative in the countdown. */
    val t: Int,
    /** Whether T0 is planned yet (before that `t` reads 0 and the countdown has no end). */
    val t0Known: Boolean,
    /** Server ticks since the run began (drives the sidebar's SkyBlock clock). */
    val sinceBegin: Int,
    val stats: HudStats = HudStats.DEFAULT,
    val teamDamage: Long = 0,
    val teamHealing: Long = 0,
    /** `MM/dd/yy` of the pre-start sidebar. */
    val dateText: String = "01/01/26",
    val clearedGoldFrom: Int = HudConstants.CLEARED_GOLD_FROM,
    val clearedGreenFrom: Int = HudConstants.CLEARED_GREEN_FROM,
    /** Lines other modules add to the sidebar below the teammates (rarely used) and tab rows to the dungeon stats block. */
    val extraSidebar: List<String> = emptyList(),
    val extraTab: List<String> = emptyList(),
    /** The Revive Stones each human carries (counted from the inventory); a member not in here uses the count on its record. */
    val reviveStones: Map<UUID, Int> = emptyMap(),
    /** The name of the player who failed each puzzle, by its index in [RunState.puzzles]; shown on the tab row while the puzzle stays failed. */
    val failedBy: Map<Int, String> = emptyMap(),
) {
    /** The time the sidebar and tab print: zero before T0, frozen once the end screen printed or the run ended. */
    val elapsedTicks: Int
        get() {
            val m = state.milestones
            val cap = m.endT
            return Math.max(0, if (cap != null) Math.min(t, cap) else t)
        }

    /** Whether the run has started (Mort has spoken): the in-run layout is shown instead of the lobby layout. */
    val started: Boolean get() = stage != RunStage.COUNTDOWN || (t0Known && t >= 0)

    /** Ticks until T0 in the countdown (never negative); 100 while T0 is not planned (a placeholder, see [SidebarModel]). */
    val ticksToStart: Int get() = if (t0Known) Math.max(0, -t) else 100

    /** The members in the order the tab lists them: [viewer] first when they are a member, then the others in join order. */
    fun membersFor(viewer: UUID?): List<PlayerRecord> {
        val all = state.players.values.toList()
        val me = all.firstOrNull { it.uuid == viewer } ?: return all
        return listOf(me) + all.filter { it !== me }
    }

    /** The member's health as the sidebar prints it: a bot carries its own maximum (`extras["maxHp"]`), everyone else the viewer's. */
    fun hpOf(p: PlayerRecord): Int {
        val max = (p.extras["maxHp"] as? Number)?.toDouble() ?: stats.maxHp.toDouble()
        return Math.round(max * Math.max(0.0, p.hpPercent) / 100.0).toInt()
    }
}

/** The sidebar of Hypixel's dungeon, line by line (FORMAT-runstate section 8, scout-client-state 3.2). Pure. */
object SidebarModel {
    /** The objective Odin recognises as SkyBlock (`isInSkyblock` is set from this name). */
    const val OBJECTIVE = "SBScoreboard"

    /** A sidebar shows at most 15 lines. */
    const val MAX_LINES = 15

    /** The team a line of score [score] belongs to: Devonian reads only teams named `team_<n>`. */
    fun teamName(score: Int) = "team_$score"

    /** A unique, invisible score holder for each score (Hypixel uses one colour-code pair per line). */
    fun holder(score: Int): String = "§" + HOLDER_CHARS[(score - 1).coerceIn(0, HOLDER_CHARS.length - 1)]

    private const val HOLDER_CHARS = "ghijpqstuvwxyz!"

    /** The lines, top to bottom, with `§` codes, as the whole line (before the prefix/suffix cut). At most [MAX_LINES]. */
    fun lines(ctx: HudContext, viewer: UUID?): List<String> {
        val out = if (ctx.started) running(ctx, viewer) else lobby(ctx, viewer)
        // Recorded: with the keys line and four teammates the sidebar is 16 lines and Hypixel drops the top (date) line.
        return if (out.size > MAX_LINES) out.drop(out.size - MAX_LINES) else out
    }

    private fun floorLine(ctx: HudContext) = " §7${HudGlyphs.LOCATION} §cThe Catacombs §7(${ctx.floor})"

    /** The SkyBlock clock line: starts at 6:10pm and moves 10 minutes every [HudConstants.CLOCK_TICKS_PER_STEP] ticks. */
    fun clockLine(sinceBegin: Int): String {
        val minutes = HudConstants.CLOCK_START_MINUTES + 10 * (Math.max(0, sinceBegin) / HudConstants.CLOCK_TICKS_PER_STEP)
        val day = minutes % (24 * 60)
        val h24 = day / 60
        val m = day % 60
        val ampm = if (h24 >= 12) "pm" else "am"
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return " §7$h12:${HudFormat.two(m)}$ampm"
    }

    /** Seconds the pre-start counter shows: the whole seconds left, rounded UP, never below 1 (the recorded sidebar never shows 0:00). */
    fun startingSeconds(ticksToStart: Int): Int = Math.max(1, (Math.max(0, ticksToStart) + 19) / 20)

    /** Ticks before T0 at which `Auto-closing in` gives way to `Starting in` (the "Starting in 4 seconds." chat line). */
    const val STARTING_FROM_TICKS = 80

    /** The first value of the `Auto-closing in` counter (1:56; recordings start between 1:52 and 1:57), falling one per second. */
    const val AUTO_CLOSING_SECONDS = 116

    /**
     * Before Mort speaks: the lobby layout, the members with their level, then `Auto-closing in` (red) until everyone is ready and
     * `Starting in` (green, with every class bracket green) for the last four seconds.
     */
    private fun lobby(ctx: HudContext, viewer: UUID?): List<String> {
        val out = ArrayList<String>()
        val starting = ctx.t0Known && ctx.ticksToStart <= STARTING_FROM_TICKS
        out += "§7${ctx.dateText} §8${HudConstants.LOBBY_SERVER}"
        out += "  "
        out += HudConstants.SEASON
        out += clockLine(ctx.sinceBegin)
        out += floorLine(ctx)
        out += "      "
        for (p in ctx.membersFor(viewer).take(5)) {
            val ready = starting || p.extras["ready"] == true
            out += "${if (ready) "§a" else "§c"}[${HudFormat.classLetter(p.dungeonClass)}] ${HudConstants.NAME_COLOUR}${HudFormat.sidebarName(p.name)} §7[Lv${ctx.stats.classLevel}]"
        }
        out += "       "
        if (starting) {
            val secs = startingSeconds(ctx.ticksToStart)
            out += "Starting in: §a${secs / 60}§a:${HudFormat.two(secs % 60)}"
        } else {
            val secs = Math.max(1, AUTO_CLOSING_SECONDS - Math.max(0, ctx.sinceBegin) / 20)
            out += "Auto-closing in: §c${secs / 60}:${HudFormat.two(secs % 60)}"
        }
        out += "        "
        out += HudConstants.FOOTER
        return out
    }

    private fun running(ctx: HudContext, viewer: UUID?): List<String> {
        val st = ctx.state
        val out = ArrayList<String>()
        // The date and server id stay the top line once the run starts, with the floor appended (mods read the lobby id from it). With the keys line and
        // four teammates the sidebar is 15 lines, exactly the cap.
        out += "§7${ctx.dateText} §8${HudConstants.LOBBY_SERVER} ${ctx.floor.lowercase()}"
        out += "  "
        out += HudConstants.SEASON
        out += clockLine(ctx.sinceBegin)
        out += floorLine(ctx)
        out += "      "
        if (keysShown(ctx)) out += keysLine(ctx)
        out += "Time Elapsed: §a${HudFormat.elapsed(ctx.elapsedTicks)}"
        out += clearedLine(ctx)
        out += "          "
        val others = ctx.membersFor(viewer).filter { it.uuid != viewer }.take(4)
        if (others.isEmpty()) out += "§3§lSolo" else for (p in others) out += memberLine(ctx, p)
        out += ctx.extraSidebar
        out += "               "
        out += HudConstants.FOOTER
        return out
    }

    /** The `Keys:` line stays from the start until the boss entry: the recorded sidebar keeps it through the blood door and the Watcher. */
    fun keysShown(ctx: HudContext): Boolean = ctx.stage == RunStage.CLEAR || ctx.stage == RunStage.COUNTDOWN || ctx.stage == RunStage.BLOOD

    /** The blood slot shows the green tick only while the key is held; once it is used the slot goes back to the red cross. */
    fun keysLine(ctx: HudContext): String {
        val k = ctx.state.keys
        val blood = if (k.bloodHeld) "§c${HudGlyphs.BLOCK} §a${HudGlyphs.TICK}" else "§c${HudGlyphs.BLOCK} §c${HudGlyphs.CROSS}"
        return "Keys: $blood §8§8${HudGlyphs.BLOCK} §a${k.witherHeld}x"
    }

    fun clearedColour(ctx: HudContext, pct: Int): String = when {
        pct >= ctx.clearedGreenFrom -> "§a"
        pct >= ctx.clearedGoldFrom -> "§6"
        else -> "§c"
    }

    /** `Cleared: 31% (53)`: the percent of the 36 tile units done and the live score estimate. Odin reads `^Cleared: (\d+)% \(\d+\)$`. */
    fun clearedLine(ctx: HudContext): String {
        val pct = ctx.state.counters.clearedPct.coerceIn(0, 100)
        return "Cleared: ${clearedColour(ctx, pct)}$pct% §8(${Math.max(0, ctx.state.score.score)})"
    }

    private fun hpColour(ctx: HudContext, p: PlayerRecord): String = when {
        p.hpPercent >= HudConstants.HP_GREEN_FROM -> "§a"
        p.hpPercent >= HudConstants.HP_YELLOW_FROM -> "§e"
        else -> "§c"
    }

    /**
     * The recorded sidebar changes a key count only when it refreshes (every 20 ticks or so), not in the tick the key arrives, so a key
     * used within a few ticks is never seen. Returns [next] with its `Keys:` line kept as in [previous] unless [refresh].
     */
    fun holdKeys(previous: Map<Int, String>?, next: Map<Int, String>, refresh: Boolean): Map<Int, String> {
        if (previous == null || refresh) return next
        val at = next.entries.firstOrNull { it.value.startsWith("Keys: ") } ?: return next
        val before = previous[at.key] ?: return next
        if (!before.startsWith("Keys: ") || before == at.value) return next
        return next + (at.key to before)
    }

    /** `[H] Name 13,879❤`, or `[M] Name DEAD` for a ghost. */
    fun memberLine(ctx: HudContext, p: PlayerRecord): String {
        val head = "§e[${HudFormat.classLetter(p.dungeonClass)}] ${HudConstants.NAME_COLOUR}${HudFormat.sidebarName(p.name)} "
        return if (p.ghost) head + "§cDEAD" else head + "${hpColour(ctx, p)}${HudFormat.commas(ctx.hpOf(p))}§c${HudGlyphs.HEART}"
    }
}

/** What changed between two sidebars, in the order the packets must go. Pure and tested. */
class SidebarOps(
    /** Scores whose team and score holder are gone. */
    val remove: List<Int>,
    /** Scores that appear (team + holder + score) with their full line. */
    val add: List<Pair<Int, String>>,
    /** Scores whose line text changed (a team update only). */
    val change: List<Pair<Int, String>>,
) {
    val isEmpty: Boolean get() = remove.isEmpty() && add.isEmpty() && change.isEmpty()
}

object SidebarDiff {
    /** The map the sidebar currently shows for [lines] (top to bottom): the top line has the highest score. */
    fun scored(lines: List<String>): Map<Int, String> {
        val m = LinkedHashMap<Int, String>()
        for ((i, l) in lines.withIndex()) m[lines.size - i] = l
        return m
    }

    fun diff(previous: Map<Int, String>, next: Map<Int, String>): SidebarOps {
        val remove = previous.keys.filter { it !in next }.sortedDescending()
        val add = next.filter { it.key !in previous }.map { it.key to it.value }.sortedByDescending { it.first }
        val change = next.filter { (k, v) -> previous[k] != null && previous[k] != v }.map { it.key to it.value }.sortedByDescending { it.first }
        return SidebarOps(remove, add, change)
    }
}
