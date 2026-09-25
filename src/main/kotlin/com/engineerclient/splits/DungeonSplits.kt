package com.engineerclient.splits

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket

/**
 * Devonian's dungeon splits, the whole run on one HUD: the clear (blood door, the Watcher, boss
 * entry) and then the boss's phases, each counting up live and freezing when it ends.
 *
 * Alongside it, one HUD per split — "Blood Sub Splits", "Terminals Sub Splits" and so on — listing
 * what happened inside that split and how far into it. Those are a data-gathering exercise: we do
 * not yet know which moments are worth timing, so [SplitEvents] recognises everything the dungeon
 * announces and the useful ones get picked out once there is a run's worth to look at. They are all
 * off by default; turn on the one you are studying.
 *
 * Both clocks are shown by default, the way Devonian's "Both" format does it: real time, then the
 * server's own tick time in brackets. They come apart when the server lags — the tick time is what
 * the run is actually judged on, and the gap between them is how much lag you ate.
 *
 * [SplitTracker] holds the timing; this module only supplies it with chat, the clocks and the
 * Watcher's first movement, and draws the result.
 */
object DungeonSplits : Module(
    name = "Sub Splits",
    category = Category.custom("Engineer Client"),
    description = "Devonian's run, boss and Watcher splits: each section timed on the real clock and the server's tick clock.",
) {
    private val clockMode by SelectorSetting("Clock", "Both", listOf("Real", "Ticks", "Both"), desc = "Real time, the server's tick time, or both (Devonian's default: real, then ticks in brackets).")
    private val subSplitLines by NumberSetting("Sub Split Lines", 12, 3, 40, 1, desc = "How many of a split's most recent events each sub-split HUD shows.")

    private val CONTROL_CODES = Regex("\u00a7.")
    private val tracker = SplitTracker()
    private val subs = SubSplitTracker()
    private var serverTicks = 0
    private val COLOUR_CODE = Regex("&.")

    private val clock get() = when (clockMode) {
        0 -> SplitClock.REAL
        1 -> SplitClock.TICKS
        else -> SplitClock.BOTH
    }

    private fun now() = Stamp(System.currentTimeMillis(), serverTicks)

    private val splitsHud by HUD("Splits", "The whole run: the clear, then the boss's phases.") { example ->
        if (example) return@HUD draw(this, listOf("§4Blood§r§f: §a31.24s §7(§b31.05s§7)", "§9Boss Entry§r§f: §a1m 12.30s §7(§b1m 12.05s§7)", "§5Maxor§r§f: §a26.10s §7(§b26.10s§7)", "§6Terminals§r§f: §a1m 09.40s §7(§b1m 09.40s§7)"))
        draw(this, tracker.splits().map { SplitFormat.line(it, now(), clock) })
    }

    /**
     * One HUD per split, made up front from every label a run can produce — a HUD has to exist
     * before the run that would fill it, and Odin registers a setting the same way whether it came
     * from a `by` delegate or from here.
     */
    private val subHuds = SplitTracker.ALL_LABELS.associateWith { label ->
        val plain = label.replace(COLOUR_CODE, "")
        registerSetting(
            HUD("$plain Sub Splits", "What happened inside the $plain split, and how far into it.", false, 0, 0, 1f) { example ->
                if (example) return@HUD draw(this, listOf("§6Move§r§f: §a8.12s §7(§b8.00s§7)", "§5Stun§r§f: §a2.28s §7(§b2.25s§7)", "§cDps§r§f: §a11.52s §7(§b11.25s§7)"))
                draw(this, subLines(label))
            }
        )
    }

    init {
        on<LevelEvent.Load> { tracker.reset(); subs.reset(); serverTicks = 0 }

        // Odin's server tick: the server's own clock, which falls behind the client's 20 a second
        // when it lags. That is the clock a run is judged on.
        on<TickEvent.Server> { serverTicks++; subs.onServerTick() }

        // Chat straight off the network, before any mod can hide it: chat cleaners drop exactly the
        // terminal, device and gate lines the sections are timed from. Handed to the client thread,
        // where the tick count is read.
        onReceive<ClientboundSystemChatPacket>(priority = 1000, ignoreCancelled = true) {
            if (overlay) return@onReceive
            val text = content.string.replace(CONTROL_CODES, "")
            val at = now()
            EngineerClient.mc.execute {
                EngineerClient.safely("splits chat") {
                    if (!DungeonUtils.inDungeons) return@safely
                    tracker.onChat(text, at)
                    subs.onChat(text, at)
                }
            }
        }

        // Goldor's leap ends when the last teammate is inside the core. Only looked for while the
        // sequence says the team is on its way there, so it costs nothing the rest of the run.
        on<TickEvent.End> {
            if (!subs.watchingCore) return@on
            val alive = DungeonUtils.dungeonTeammates.filter { !it.isDead }
            if (alive.isEmpty()) return@on
            val inCore = alive.count { mate ->
                val p = mate.entity ?: level.players().firstOrNull { it.name.string == mate.name }
                p != null && p.x >= 39 && p.x < 71 && p.y >= 112 && p.y < 155.5 && p.z >= 54 && p.z < 118
            }
            if (inCore >= alive.size) subs.onEveryoneInCore(now())
        }
    }

    private const val LINE_HEIGHT = 10

    /**
     * A split's events as "+<time into the split> <what happened>". The offset is what makes these
     * comparable between runs, so it leads.
     */
    private fun subLines(label: String): List<String> {
        val split = tracker.splits().firstOrNull { it.label == label } ?: return emptyList()
        // The boss phases have a real breakdown, ported from the team's own module. The clear's
        // splits have none, so those HUDs keep listing whatever the dungeon announced.
        val steps = subs.forSplit(label)
        if (steps.isNotEmpty()) return steps.map { SplitFormat.line(it, now(), clock) }
        val events = tracker.subSplits(label)
        return events.takeLast(subSplitLines).map { event ->
            val real = SplitFormat.time(event.at.realMs - split.start.realMs, true)
            val ticks = SplitFormat.time((event.at.tick - split.start.tick) * 50L, true)
            val time = when (clock) {
                SplitClock.REAL -> "§a$real"
                SplitClock.TICKS -> "§b$ticks"
                SplitClock.BOTH -> "§a$real §7(§b$ticks§7)"
            }
            "§7+$time §f" + event.label
        }
    }

    private fun draw(gfx: GuiGraphicsExtractor, lines: List<String>): Pair<Int, Int> {
        if (lines.isEmpty()) return 0 to 0
        var width = 0
        lines.forEachIndexed { i, line ->
            gfx.text(line, 0, i * LINE_HEIGHT, Colors.WHITE)
            width = maxOf(width, mc.font.width(line))
        }
        return width to lines.size * LINE_HEIGHT
    }
}
