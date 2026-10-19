package com.engineerclient.p3sim.clear.ui

import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.pack.Packs
import com.engineerclient.p3sim.run.LayoutSource
import com.engineerclient.p3sim.run.PracticePlan
import com.engineerclient.p3sim.run.RoomType
import com.engineerclient.p3sim.run.RunConfig
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Practice: every puzzle and every room of the recorded runs, one button each. A click builds that room alone in the middle of the map
 * (as a recorded run had it) and puts you in its doorway, the way in behind you bedrock and every other side a wall; no countdown, no bots.
 * Tabs: Puzzles, Rooms (normal, rare, yellow), Other (traps, fairy). A search box filters by name.
 */
class PracticeScreen(private val parent: Screen?) : Screen(Component.literal("Practice")) {
    private enum class Tab(val label: String, val about: String, val types: Set<RoomType>) {
        PUZZLES("Puzzles", "The ten puzzles, each in a state a recorded run had.", setOf(RoomType.PUZZLE)),
        ROOMS("Rooms", "The normal rooms (and the rare and yellow ones): mobs and secrets, as recorded.", setOf(RoomType.NORMAL, RoomType.RARE, RoomType.YELLOW)),
        OTHER("Trap & Fairy", "The trap room and the fairy room.", setOf(RoomType.TRAP, RoomType.FAIRY)),
    }

    private var tab = lastTab
    private var query = ""
    private var page = 0
    private var choices: List<PracticePlan.Choice>? = null
    private var message: String? = null
    /** The last thing the launch said, shown above the page buttons. */
    private var said: String? = null

    override fun init() {
        super.init()
        val cx = width / 2
        val w = minOf(width - 20, 440)
        var y = 28
        addRenderableWidget(StringWidget(title, font).also { it.setPosition(cx - font.width(title) / 2, 10) })

        val all = choices ?: load()
        if (all == null) {
            val text = message ?: "Loading the data packs..."
            addRenderableWidget(StringWidget(Component.literal("§7$text"), font).also { it.setPosition(cx - font.width(text) / 2, height / 2 - 20) })
            addRenderableWidget(Button.builder(Component.literal("Back")) { onClose() }.bounds(cx - 28, height / 2, 56, 20).build())
            return
        }

        val tw = (w - 8) / Tab.entries.size
        Tab.entries.forEachIndexed { i, t ->
            val n = all.count { it.type in t.types }
            addRenderableWidget(Button.builder(Component.literal(if (t == tab) "§e§n${t.label} ($n)" else "${t.label} ($n)")) { tab = t; lastTab = t; page = 0; rebuildWidgets() }
                .tooltip(Tooltip.create(Component.literal(t.about))).bounds(cx - w / 2 + i * (tw + 4), y, tw, 20).build())
        }
        y += 24
        val box = EditBox(font, cx - w / 2, y, w - 44, 20, Component.literal("Search"))
        box.setMaxLength(40)
        box.setHint(Component.literal("§8Search a room"))
        box.setValue(query)
        box.setResponder { query = it; page = 0 }
        addRenderableWidget(box)
        addRenderableWidget(Button.builder(Component.literal("Find")) { rebuildWidgets() }.bounds(cx + w / 2 - 40, y, 40, 20).build())
        y += 26

        val view = all.filter { it.type in tab.types && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
        val cols = if (w >= 400) 4 else 3
        val rowsFit = ((height - y - 40) / ROW).coerceAtLeast(2)
        val pageSize = cols * rowsFit
        val pages = maxOf(1, (view.size + pageSize - 1) / pageSize)
        page = page.coerceIn(0, pages - 1)
        if (view.isEmpty()) addRenderableWidget(StringWidget(Component.literal("§7Nothing matches."), font).also { it.setPosition(cx - font.width("Nothing matches.") / 2, y + 20) })
        val bw = (w - (cols - 1) * 4) / cols
        view.drop(page * pageSize).take(pageSize).forEachIndexed { i, c ->
            val bx = cx - w / 2 + (i % cols) * (bw + 4)
            val by = y + (i / cols) * ROW
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(c.name, bw - 8))) { start(c) }
                .tooltip(Tooltip.create(Component.literal("${c.name}: ${c.shape} ${c.type.name.lowercase()} room, in ${c.runs} recorded run${if (c.runs == 1) "" else "s"}. Click to practise it (a recorded run's build and state, drawn at random).")))
                .bounds(bx, by, bw, 20).build())
        }
        val by = height - 30
        said?.let { s -> val t = font.plainSubstrByWidth(s, w); addRenderableWidget(StringWidget(Component.literal("§e$t"), font).also { it.setPosition(cx - font.width(t) / 2, by - 12) }) }
        addRenderableWidget(Button.builder(Component.literal("<")) { page--; rebuildWidgets() }.bounds(cx - w / 2, by, 24, 20).build().also { it.active = page > 0 })
        val label = "Page ${page + 1} of $pages, ${view.size} rooms"
        addRenderableWidget(StringWidget(Component.literal("§7$label"), font).also { it.setPosition(cx - font.width(label) / 2, by + 6) })
        addRenderableWidget(Button.builder(Component.literal(">")) { page++; rebuildWidgets() }.bounds(cx + w / 2 - 24, by, 24, 20).build().also { it.active = page < pages - 1 })
        addRenderableWidget(Button.builder(Component.literal("Back")) { onClose() }.bounds(cx + w / 2 - 24 - 62, by, 56, 20).build())
    }

    private fun start(c: PracticePlan.Choice) {
        val config = RunConfig(layout = LayoutSource.Practice(c.name), seed = System.nanoTime(), bots = false, partySize = 1)
        // The reply lines go to chat and the last one is shown here too (on the title screen there is no chat yet).
        RunStarter.start(config) { line ->
            mc.player?.sendSystemMessage(Component.literal("§8[§6ADS§8] §7$line"))
            mc.execute { if (mc.gui.screen() === this) { said = line; rebuildWidgets() } }
        }
    }

    private fun load(): List<PracticePlan.Choice>? {
        val set = Packs.current
        if (set == null) {
            Packs.loadAsync().thenAccept { mc.execute { if (mc.gui.screen() === this) rebuildWidgets() } }
            return null
        }
        val pack = set.layouts ?: run { message = set.userLines().joinToString(" ").ifEmpty { "The layout pool is not there." }; return null }
        return PracticePlan.choices(pack).also { choices = it }
    }

    override fun onClose() { mc.gui.setScreen(parent) }

    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val ROW = 22
        var lastTab = Tab.PUZZLES
    }
}
