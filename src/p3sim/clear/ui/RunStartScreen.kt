package com.engineerclient.p3sim.clear.ui

import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.P3Sim
import com.engineerclient.p3sim.clear.bots.BotStrategies
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.run.LagPreset
import com.engineerclient.p3sim.run.LayoutSource
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The full run's start screen, in the look of the ADS menu ([com.engineerclient.p3sim.SimRestartScreen]): a centred column of
 * rows, the chosen button green and underlined, every button says what it does when hovered, changes are saved at once.
 *
 * Rows: the floor (F7 only), where the layout comes from (random, a seed, or a recorded run from the browser), the party (size and
 * bots), the party strategy (only when a plug-in offers presets; auto is the default), the lag, and the two run rules (no deaths, mob
 * HP). Start launches the run ([RunStarter]); a missing data pack is written on the screen with its folder instead of starting.
 */
class RunStartScreen : Screen(Component.literal("${P3Sim.FULL_NAME}: full run")) {
    private var defaults = UiSettings.defaults()
    private lateinit var seedBox: EditBox
    private val problems = ArrayList<String>()

    override fun init() {
        super.init()
        val cx = width / 2
        val offered = BotStrategies.available()
        val showStrategy = StrategyChoice.hasPresets(offered)
        val rows = if (showStrategy) 9 else 8
        var y = maxOf(24, height / 2 - (rows * ROW) / 2 - 10)
        addRenderableWidget(StringWidget(title, font).also { it.setPosition(cx - font.width(title) / 2, y - 18) })

        // Floor
        label("Floor", y)
        addRenderableWidget(Button.builder(Component.literal("§a§nF7")) {}.tooltip(tip("Floor 7. Floor 7 only for now; the master mode floor is not built."))
            .bounds(cx - 20, y, 40, 20).build())
        y += ROW

        // Layout source
        label("Layout", y)
        val x0 = cx - 3 * 52 / 2 - 4
        source("Random", Mode.RANDOM, x0, y, "A random recorded layout. The seed is shown when the run starts, so the same run can be repeated.")
        source("Seed", Mode.SEED, x0 + 52, y, "The layout a seed number picks; the same seed gives the same layout and the same run.")
        source("Recorded", Mode.RECORDED, x0 + 104, y, "A layout from a run that was recorded: pick it from the browser by its rooms, puzzles and recorded time.")
        y += ROW
        when (mode) {
            Mode.SEED -> {
                seedBox = EditBox(font, cx - 60, y, 80, 20, Component.literal("Seed"))
                seedBox.setMaxLength(18)
                seedBox.setHint(Component.literal("§8Seed"))
                seedBox.setValue(seedText)
                seedBox.setResponder { seedText = it }
                addRenderableWidget(seedBox)
                addRenderableWidget(Button.builder(Component.literal("New")) { seedText = UiModule.drawSeed().toString(); rebuildWidgets() }
                    .tooltip(tip("A fresh random seed.")).bounds(cx + 24, y, 36, 20).build())
            }
            Mode.RECORDED -> {
                val chosen = chosenId
                addRenderableWidget(Button.builder(Component.literal(if (chosen == null) "Choose a layout..." else "§a${LayoutBrowserModel.title(chosen)}")) { mc.gui.setScreen(LayoutBrowserScreen(this)) }
                    .tooltip(tip(if (chosen == null) "Opens the browser of recorded layouts." else "$chosen. Click: opens the browser to choose another."))
                    .bounds(cx - 90, y, 180, 20).build())
            }
            Mode.RANDOM -> {}
        }
        y += ROW

        // Party
        label("Party", y)
        val px = cx - 5 * 22 / 2 - 30
        for (n in 1..5) {
            val on = (if (defaults.bots) defaults.partySize else 1) == n
            addRenderableWidget(Button.builder(Component.literal(pick("$n", on))) {
                defaults = defaults.copy(partySize = n, bots = n > 1); save(); rebuildWidgets()
            }.tooltip(tip(if (n == 1) "You alone: no bots, the party list is just you." else "$n players: you and ${n - 1} bot${if (n > 2) "s" else ""} (the classes nobody plays).")).bounds(px + (n - 1) * 22, y, 20, 20).build())
        }
        addRenderableWidget(toggle("Bots", defaults.bots, 52, cx + 32, y, "Teammate bots walk the dungeon and their rooms check off on the measured schedule. Off is the same as a party of 1.") {
            val on = !defaults.bots
            defaults = defaults.copy(bots = on, partySize = if (on && defaults.partySize < 2) 5 else defaults.partySize)
        })
        y += ROW

        // Party strategy: one button that cycles auto, neutral and the plug-in presets (hidden when there is nothing to choose).
        if (showStrategy) {
            label("Strategy", y)
            val choice = defaults.strategy
            addRenderableWidget(Button.builder(Component.literal("§a" + StrategyChoice.label(choice, offered))) {
                defaults = defaults.copy(strategy = StrategyChoice.next(choice, offered)); save(); rebuildWidgets()
            }.tooltip(tip(StrategyChoice.about(choice, offered))).bounds(cx - 90, y, 180, 20).build().also { it.active = defaults.bots })
            y += ROW
        }

        // Lag
        label("Lag", y)
        val lagW = 50
        val lx = cx - LagPreset.entries.size * (lagW + 2) / 2
        LagPreset.entries.forEachIndexed { i, p ->
            addRenderableWidget(Button.builder(Component.literal(pick(p.label, defaults.lag == p))) { defaults = defaults.copy(lag = p); save(); rebuildWidgets() }
                .tooltip(tip(if (p == LagPreset.OFF) "No lag layer: the server never stalls." else "${p.label}: about ${p.lossPerMinute} ticks lost per minute, as the server stalls and catches up.${if (p == LagPreset.HIGH_SLOT) " That is an instance slot above 200." else ""}"))
                .bounds(lx + i * (lagW + 2), y, lagW, 20).build())
        }
        y += ROW

        // Rules
        label("Rules", y)
        addRenderableWidget(toggle("No deaths", defaults.noDeaths, 90, cx - 92, y, "You cannot die in the clear: your health stops at its last point instead of the death and the revive.") {
            defaults = defaults.copy(noDeaths = !defaults.noDeaths)
        })
        addRenderableWidget(toggle("Mob HP per run", defaults.mobHpFromPack, 90, cx + 2, y, "On: each run draws its mob HP scale from the recorded per-run table. Off: every mob has its base HP.") {
            defaults = defaults.copy(mobHpFromPack = !defaults.mobHpFromPack)
        })
        y += ROW + 4

        // Start
        addRenderableWidget(Button.builder(Component.literal("§aStart run")) { start() }
            .tooltip(tip("Builds the dungeon and starts the countdown. This opens a fresh ADS world, which takes a few seconds."))
            .bounds(cx - 60, y, 120, 20).build())
        y += ROW
        addRenderableWidget(Button.builder(Component.literal("Close")) { onClose() }.bounds(cx - 30, y, 60, 20).build())
        y += ROW + 4

        // Problems: written out in full.
        for (p in problems) for (line in wrap(p, minOf(width - 40, 360))) {
            addRenderableWidget(StringWidget(Component.literal("§c$line"), font).also { it.setPosition(cx - font.width(line) / 2, y) })
            y += 11
        }
    }

    private fun start() {
        problems.clear()
        val layout = when (mode) {
            Mode.RANDOM -> LayoutSource.Random
            Mode.SEED -> {
                val n = seedText.trim().toLongOrNull()
                if (n == null) { problems += "Type the seed as a number, or press New."; rebuildWidgets(); return }
                LayoutSource.Seed(n)
            }
            Mode.RECORDED -> {
                val id = chosenId
                if (id == null) { problems += "Choose a recorded layout first."; rebuildWidgets(); return }
                LayoutSource.Recorded(id)
            }
        }
        val seed = if (layout is LayoutSource.Seed) layout.seed else UiModule.drawSeed()
        save()
        val config = defaults.config(layout, seed)
        // The reply lines go to chat; a pack problem is also kept on this screen, which stays open.
        val said = ArrayList<String>()
        RunStarter.start(config) { line ->
            said += line
            mc.player?.sendSystemMessage(Component.literal("§8[§6ADS§8] §7$line"))
            mc.execute { if (mc.gui.screen() === this) { problems.clear(); problems += said; rebuildWidgets() } }
        }
        // A launch that is going to open the world closes this screen by itself; one that refused leaves it open with the reason.
    }

    /** Remembered between openings in this game session. */
    private enum class Mode { RANDOM, SEED, RECORDED }

    private fun source(name: String, m: Mode, x: Int, y: Int, about: String) {
        addRenderableWidget(Button.builder(Component.literal(pick(name, mode == m))) { mode = m; rebuildWidgets() }.tooltip(tip(about)).bounds(x, y, 50, 20).build())
    }

    /** A toggle button (green while on); [flip] changes [defaults], then it is saved and the screen rebuilt. */
    private fun toggle(name: String, on: Boolean, w: Int, x: Int, y: Int, about: String, flip: () -> Unit): Button =
        Button.builder(Component.literal(if (on) "§a$name" else name)) { flip(); save(); rebuildWidgets() }.tooltip(tip(about)).bounds(x, y, w, 20).build()

    private fun label(t: String, y: Int) {
        val c = Component.literal("§e$t")
        addRenderableWidget(StringWidget(c, font).also { it.setPosition(width / 2 - LABEL_X - font.width(c) / 2, y + 6) })
    }

    private fun save() { UiSettings.store(defaults) }

    /** Called when the browser chose a layout. */
    internal fun chose(id: String) { chosenId = id; mode = Mode.RECORDED }

    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        super.removed()
        save()
        com.engineerclient.ads.FullRunSettings.save()
    }

    override fun added() {
        super.added()
        // Load the packs now (once), so a missing pack shows up on opening rather than on Start.
        if (Packs.current == null) Packs.loadAsync().thenAccept { set ->
            mc.execute {
                if (mc.gui.screen() === this && !set.canBuildDungeon) { problems.clear(); problems += set.userLines(); rebuildWidgets() }
            }
        } else Packs.current?.let { if (!it.canBuildDungeon && problems.isEmpty()) { problems += it.userLines() } }
    }

    private fun tip(text: String) = Tooltip.create(Component.literal(text))
    private fun pick(name: String, on: Boolean) = if (on) "§a§n$name" else name

    private fun wrap(text: String, maxWidth: Int): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (w in text.split(' ')) {
            val next = if (cur.isEmpty()) w else "$cur $w"
            if (cur.isNotEmpty() && font.width(next) > maxWidth) { out += cur; cur = w } else cur = next
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    companion object {
        private const val ROW = 24
        private const val LABEL_X = 130
        // The screen's choices outlive one opening: the browser returns here, and the next opening starts where this one left off.
        private var mode = Mode.RANDOM
        private var seedText = ""
        private var chosenId: String? = null

        /** The recorded layout chosen in the browser (null when none). */
        fun currentChoice(): String? = chosenId
    }
}
