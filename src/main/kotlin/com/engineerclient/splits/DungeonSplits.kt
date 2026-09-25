package com.engineerclient.splits

import com.engineerclient.EngineerClient
import com.odtheking.odin.clickgui.settings.Setting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
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
 * Devonian's dungeon splits: the clear (blood door, the Watcher, boss entry), the boss's phases
 * with the F7 terminal sections inside them, and the Watcher fight on its own. Three HUDs, each
 * counting its section up live and freezing it when the section ends.
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
    private val showSections by BooleanSetting("Terminal Sections", true, desc = "Times S1-S4 inside the F7/M7 Terminals split.")
    private val indentSections by BooleanSetting("Indent Sections", true, desc = "Indents S1-S4 under Terminals.").withDependency { showSections }

    private val CONTROL_CODES = Regex("\u00a7.")
    private val tracker = SplitTracker()
    private var serverTicks = 0

    /** The Watcher's last seen position, for spotting the tick it starts moving. */
    private var watcherAt: Triple<Double, Double, Double>? = null

    private val clock get() = when (clockMode) {
        0 -> SplitClock.REAL
        1 -> SplitClock.TICKS
        else -> SplitClock.BOTH
    }

    private fun now() = Stamp(System.currentTimeMillis(), serverTicks)

    private val runHud by HUD("Run Splits", "The clear: blood door, the Watcher, boss entry.") { example ->
        if (example) return@HUD draw(this, listOf("§4Blood§r§f: §a31.24s §7(§b31.05s§7)", "§cWatcher§r§f: §a18.52s §7(§b18.40s§7)", "§9Boss Entry§r§f: §a1m 12.30s §7(§b1m 12.05s§7)"))
        draw(this, lines(tracker.runSplits()))
    }

    private val bossHud by HUD("Boss Splits", "The boss's phases, with the terminal sections inside Terminals.") { example ->
        if (example) return@HUD draw(this, listOf("§5Maxor§r§f: §a26.10s §7(§b26.10s§7)", "§9Storm§r§f: §a45.90s §7(§b45.90s§7)", "§6Terminals§r§f: §a1m 09.40s §7(§b1m 09.40s§7)", " §eS1§r§f: §a17.35s §7(§b17.35s§7)"))
        draw(this, lines(tracker.bossSplits()))
    }

    private val watcherHud by HUD("Watcher Splits", "The Watcher fight: its dialogue, when it moved, and the whole fight.") { example ->
        if (example) return@HUD draw(this, listOf("§cWatcher Dialog§r§f: §a12.00s §7(§b12.00s§7)", "§cWatcher Move§r§f: §a4.55s §7(§b4.50s§7)", "§cWatcher§r§f: §a38.10s §7(§b38.05s§7)"))
        draw(this, lines(tracker.watcherSplits()))
    }

    init {
        on<LevelEvent.Load> { tracker.reset(); serverTicks = 0; watcherAt = null }

        // Odin's server tick: the server's own clock, which falls behind the client's 20 a second
        // when it lags. That is the clock a run is judged on.
        on<TickEvent.Server> { serverTicks++ }

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
                    tracker.master = DungeonUtils.floor?.isMM == true
                    tracker.onChat(text, at)
                }
            }
        }

        // The Watcher starting to move ends the "Watcher Move" split. It is only worth looking for
        // in the seconds between its last line and its blessing, so the search costs nothing for
        // the rest of the run.
        on<TickEvent.End> {
            val dialogue = tracker.dialogueEnd ?: return@on
            if (!DungeonUtils.inDungeons || tracker.watcherMoved) return@on
            val me = mc.player ?: return@on
            val watcher = level.entitiesForRendering()
                .filter { it.name.string.contains("The Watcher") }
                .minByOrNull { it.distanceToSqr(me) } ?: return@on
            val at = Triple(watcher.x, watcher.y, watcher.z)
            val was = watcherAt
            watcherAt = at
            // Its dialogue plays while it stands still; only movement a couple of seconds after the
            // last line is the Watcher actually coming for you.
            if (was != null && was != at && serverTicks >= dialogue.tick + WATCHER_SETTLE) tracker.onWatcherMove(now())
        }
    }

    private const val WATCHER_SETTLE = 45
    private const val LINE_HEIGHT = 10

    private fun lines(splits: List<Split>): List<String> {
        val now = now()
        return splits.mapNotNull { split ->
            if (split.sub && !showSections) return@mapNotNull null
            val indent = if (split.sub && indentSections) " " else ""
            indent + SplitFormat.line(split, now, clock)
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
