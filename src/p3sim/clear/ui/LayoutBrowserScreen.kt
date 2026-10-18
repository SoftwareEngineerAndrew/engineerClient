package com.engineerclient.p3sim.clear.ui

import com.engineerclient.EngineerClient.mc
import com.engineerclient.p3sim.pack.Packs
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The small browser of recorded layouts, opened from the start screen's "Recorded" row. One button per layout with its date,
 * rooms, puzzles, secrets and recorded time (hover for the puzzle names and the recorded party's classes); a search box (type
 * "boulder" or "2026-09" or a room name), a sort, and a switch for layouts that were not recorded completely. Clicking one
 * chooses it and goes back to [parent]. No data pack: the screen says where the packs go.
 */
class LayoutBrowserScreen(private val parent: RunStartScreen) : Screen(Component.literal("Recorded layouts")) {
    private var query = lastQuery
    private var sort = lastSort
    private var poolOnly = lastPoolOnly
    private var page = 0
    private var rows: List<LayoutRow>? = null
    private var message: String? = null

    override fun init() {
        super.init()
        val cx = width / 2
        val pageSize = ((height - 120) / ROW).coerceIn(3, 12)
        val w = minOf(width - 20, 380)
        var y = 28
        addRenderableWidget(StringWidget(title, font).also { it.setPosition(cx - font.width(title) / 2, 10) })

        val all = rows ?: loadRows()
        if (all == null) {
            val text = message ?: "Loading the layout pool..."
            var yy = height / 2 - 20
            for (line in wrap(text, w)) { addRenderableWidget(StringWidget(Component.literal("§c$line"), font).also { it.setPosition(cx - font.width(line) / 2, yy) }); yy += 11 }
            addRenderableWidget(Button.builder(Component.literal("Reload")) { rows = null; message = null; rebuildWidgets() }
                .tooltip(tip("Reads the data pack folder again.")).bounds(cx - 60, yy + 8, 56, 20).build())
            addRenderableWidget(Button.builder(Component.literal("Back")) { onClose() }.bounds(cx + 4, yy + 8, 56, 20).build())
            return
        }

        val box = EditBox(font, cx - w / 2, y, w - 188, 20, Component.literal("Search"))
        box.setMaxLength(60)
        box.setHint(Component.literal("§8Search: a puzzle, a room, a date"))
        box.setValue(query)
        box.setResponder { query = it; lastQuery = it; page = 0 }
        addRenderableWidget(box)
        // Typing filters on Enter or when a button is pressed (rebuilding on every key would drop the box's focus).
        addRenderableWidget(Button.builder(Component.literal("Find")) { rebuildWidgets() }.tooltip(tip("Applies the search.")).bounds(cx + w / 2 - 184, y, 40, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Sort: ${sort.label}")) { sort = sort.next(); lastSort = sort; page = 0; rebuildWidgets() }
            .tooltip(tip("${sort.about} Click: the next sort.")).bounds(cx + w / 2 - 142, y, 84, 20).build())
        addRenderableWidget(Button.builder(Component.literal(if (poolOnly) "§aComplete only" else "All")) { poolOnly = !poolOnly; lastPoolOnly = poolOnly; page = 0; rebuildWidgets() }
            .tooltip(tip("On: only layouts recorded completely (a random run can pick these). Off: every recorded layout, including ones that may not build or play well.")).bounds(cx + w / 2 - 56, y, 56, 20).build())
        y += ROW + 2

        val view = LayoutBrowserModel.view(all, query, sort, poolOnly)
        val pages = LayoutBrowserModel.pageCount(view.size, pageSize)
        page = page.coerceIn(0, pages - 1)
        if (view.isEmpty()) {
            addRenderableWidget(StringWidget(Component.literal("§7No layout matches."), font).also { it.setPosition(cx - font.width("No layout matches.") / 2, y + 20) })
        }
        for (r in LayoutBrowserModel.page(view, page, pageSize)) {
            val text = font.plainSubstrByWidth(r.line(), w - 12)
            addRenderableWidget(Button.builder(Component.literal(if (r.id == parentChoice()) "§a§n$text" else text)) { parent.chose(r.id); mc.setScreen(parent) }
                .tooltip(tip(detail(r))).bounds(cx - w / 2, y, w, 20).build())
            y += ROW
        }
        val by = height - 30
        addRenderableWidget(Button.builder(Component.literal("<")) { page--; rebuildWidgets() }.bounds(cx - w / 2, by, 24, 20).build().also { it.active = page > 0 })
        addRenderableWidget(StringWidget(Component.literal("§7Page ${page + 1} of $pages, ${view.size} layouts"), font).also { it.setPosition(cx - font.width("Page ${page + 1} of $pages, ${view.size} layouts") / 2, by + 6) })
        addRenderableWidget(Button.builder(Component.literal(">")) { page++; rebuildWidgets() }.bounds(cx + w / 2 - 24, by, 24, 20).build().also { it.active = page < pages - 1 })
        addRenderableWidget(Button.builder(Component.literal("Back")) { onClose() }.bounds(cx + w / 2 - 24 - 62, by, 56, 20).build())
    }

    private fun parentChoice(): String? = RunStartScreen.currentChoice()

    /** The hover text: everything the one line leaves out. */
    private fun detail(r: LayoutRow): String = buildList {
        add(r.id)
        add("Puzzles: " + (r.puzzles.joinToString(", ").ifEmpty { "none" }) + ".")
        if (r.party.isNotEmpty()) add("Recorded party: ${r.party.joinToString(", ")}.")
        add("Recorded time: ${r.time}.")
        if (!r.inPool) add("Not recorded completely: it may not build or may play oddly.")
    }.joinToString(" ")

    /** The rows once the layout pool is loaded; null while loading or when it cannot be (the reason is in [message]). */
    private fun loadRows(): List<LayoutRow>? {
        val set = Packs.current
        if (set == null) {
            message = null
            Packs.loadAsync().thenAccept { mc.execute { if (mc.screen === this) rebuildWidgets() } }
            return null
        }
        val pack = set.layouts
        if (pack == null) {
            message = set.userLines().joinToString(" ").ifEmpty { "The layout pool is not there." }
            return null
        }
        return LayoutBrowserModel.rows(pack).also { rows = it }
    }

    override fun onClose() { mc.setScreen(parent) }

    override fun isPauseScreen(): Boolean = false

    private fun tip(text: String) = Tooltip.create(Component.literal(text))

    private fun wrap(text: String, maxWidth: Int): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (w in text.split(' ')) {
            val next = if (cur.isEmpty()) w else "$cur $w"
            if (cur.isNotEmpty() && font.width(next) > maxWidth) { out += cur; cur = w } else cur = next
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private companion object {
        const val ROW = 22
        var lastQuery = ""
        var lastSort = LayoutBrowserModel.Sort.NEWEST
        var lastPoolOnly = true
    }
}
