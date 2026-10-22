package com.engineerclient.ads

import com.engineerclient.p3sim.clear.ui.LayoutBrowserModel
import com.engineerclient.p3sim.clear.ui.LayoutRow
import com.engineerclient.p3sim.pack.Packs
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.rustclear.mod.ui.RustclearScreen
import net.rustclear.mod.ui.Theme

/**
 * The recorded layouts for Practice > Full clear on Engineer Client's dungeon, in the ADS menu's look: a search box, the
 * sorts and the Complete only switch along the top, then every layout as a row of a table (date, rooms, puzzles, secrets,
 * recorded time), scrolled with the wheel. Hovering a row puts its puzzles and recorded party in the footer; a click picks
 * it ([onChoose]) and goes back. The rows and their search come from Engineer Client's [LayoutBrowserModel].
 */
class LayoutBrowserScreen(
    parent: Screen,
    /** The layout chosen now (gold in the table), or null. */
    private val current: () -> String?,
    private val onChoose: (String) -> Unit,
) : RustclearScreen(Component.literal("Recorded layouts"), parent) {

    private var searchBox: EditBox? = null
    private var rows: List<LayoutRow>? = null
    /** Why there are no rows (no data pack), or null while they load. */
    private var problem: String? = null
    private var scroll = 0
    private var maxScroll = 0
    /** The table's window in the last frame (x, y, w, h), for the mouse wheel. */
    private var tableView: IntArray? = null

    override fun init() {
        layoutPanel(Int.MAX_VALUE, Int.MAX_VALUE)
        searchBox = input(contentX() + 14, py + 35, 170, "Search: a puzzle, a room, a date", 60).also { box ->
            box.value = query
            box.setResponder { query = it; scroll = 0 }
            addRenderableWidget(box)
            setInitialFocus(box)
        }
        if (rows == null && problem == null) load()
    }

    private fun contentX() = px + 12
    private fun contentW() = pw - 24

    private fun load() {
        val set = Packs.current
        if (set == null) {
            Packs.loadAsync().thenAccept { Minecraft.getInstance().execute { if (Minecraft.getInstance().gui.screen() === this) load() } }
            return
        }
        val pack = set.layouts
        if (pack == null) problem = set.userLines().joinToString(" ").ifEmpty { "The layout pool is not there." }
        else rows = LayoutBrowserModel.rows(pack)
    }

    override fun extractFrame(g: GuiGraphicsExtractor, mx: Int, my: Int, delta: Float) {
        panel(g)
        val all = rows
        val view = all?.let { LayoutBrowserModel.view(it, query, sort, completeOnly) }
        header(g, "Recorded layouts", if (view == null) "LOADING" else "${view.size} LAYOUTS", Theme.GOLD)

        val x = contentX()
        val w = contentW()
        val by = py + ph - 23
        var hover: String? = null

        // The search box, then the sorts and the Complete only switch.
        val box = searchBox
        inputFrame(g, x, py + 32, 182, box?.isFocused == true, true)
        var cx = x + 192
        for (s in LayoutBrowserModel.Sort.entries) {
            if (over(mx, my, cx, py + 33, choiceWidth(s.label), 13)) hover = s.about
            cx += choice(g, mx, my, s.label, cx, py + 33, sort == s) { sort = s; scroll = 0 } + 4
        }
        cx += 8
        if (over(mx, my, cx, py + 33, choiceWidth("Complete only"), 13)) hover = "On: only layouts recorded completely (a random run can pick these). Off: every one, including ones that may not build or play well."
        choice(g, mx, my, "Complete only", cx, py + 33, completeOnly) { completeOnly = !completeOnly; scroll = 0 }

        val top = py + 54
        if (view == null) {
            val message = problem ?: "Loading the layout pool..."
            var ly = top + 10
            for (line in wrap(message, w)) { text(g, line, x, ly, if (problem != null) Theme.BAD else Theme.MUTED); ly += 10 }
        } else {
            hover = table(g, mx, my, view, x, top, w, by - 10 - top) ?: hover
        }

        rect(g, x, by - 5, w, 1, Theme.PANEL_EDGE)
        button(g, mx, my, "Back", px + pw - 8, by, Style.SECONDARY) { onClose() }
        val hint = hover ?: current()?.let { "Chosen: ${LayoutBrowserModel.title(it)}. Click another to change it." } ?: "Click a layout to run it."
        text(g, fit(hint, w - 70), x, by + 5, Theme.DIM)
    }

    /** The table of [view]; returns the hovered row's details for the footer. */
    private fun table(g: GuiGraphicsExtractor, mx: Int, my: Int, view: List<LayoutRow>, x: Int, top: Int, w: Int, h: Int): String? {
        // Column starts, as fractions of the width: date, rooms, puzzles, secrets, time.
        val cols = intArrayOf(0, (w * 0.30).toInt(), (w * 0.45).toInt(), (w * 0.62).toInt(), (w * 0.79).toInt())
        val heads = listOf("Recorded", "Rooms", "Puzzles", "Secrets", "Time")
        heads.forEachIndexed { i, t -> text(g, t, x + 6 + cols[i], top, Theme.DIM) }
        val listTop = top + 13
        val viewH = h - 13
        val rowH = 15
        maxScroll = maxOf(0, view.size * rowH - viewH)
        scroll = scroll.coerceIn(0, maxScroll)
        tableView = intArrayOf(x, listTop, w, viewH)
        if (view.isEmpty()) {
            text(g, "No layout matches.", x + 6, listTop + 4, Theme.DIM)
            return null
        }
        var hovered: String? = null
        val chosen = current()
        val inView = over(mx, my, x, listTop, w, viewH)
        beginClip(g, x, listTop, w, viewH)
        // Only the rows in the window are drawn: there are hundreds.
        val first = scroll / rowH
        val last = minOf(view.size - 1, (scroll + viewH) / rowH)
        for (i in first..last) {
            val r = view[i]
            val ry = listTop + i * rowH - scroll
            val on = r.id == chosen
            val hover = inView && over(mx, my, x, ry, w - 4, rowH - 2)
            round(g, x, ry, w - 4, rowH - 2, if (on) Theme.alpha(Theme.GOLD, 0x30) else if (hover) Theme.ROW_HOVER else if (i % 2 == 0) Theme.RAIL else 0)
            val c = if (on) Theme.GOLD else if (hover) Theme.TEXT else Theme.MUTED
            val cells = listOf(r.title + if (r.inPool) "" else "  (not complete)", "${r.rooms}", "${r.puzzles.size}", "${r.secrets}", r.time)
            cells.forEachIndexed { k, t -> text(g, fit(t, (if (k + 1 < cols.size) cols[k + 1] else w) - cols[k] - 8), x + 6 + cols[k], ry + 3, c) }
            if (hover) hovered = detail(r)
            click(x, ry, w - 4, rowH - 2) { onChoose(r.id); back() }
        }
        endClip(g)
        scrollbar(g, x + w + 5, listTop, viewH, viewH, view.size * rowH, scroll, maxScroll)
        return hovered
    }

    /** Everything the row leaves out: its puzzles and the recorded party. */
    private fun detail(r: LayoutRow): String = buildList {
        add("Puzzles: " + r.puzzles.joinToString(", ").ifEmpty { "none" } + ".")
        if (r.party.isNotEmpty()) add("Party: ${r.party.joinToString(", ")}.")
        if (!r.inPool) add("Not recorded completely: it may not build or may play oddly.")
    }.joinToString(" ")

    override fun mouseScrolled(mx: Double, my: Double, scrollX: Double, scrollY: Double): Boolean {
        val v = tableView
        if (v != null && mx >= v[0] && mx < v[0] + v[2] && my >= v[1] && my < v[1] + v[3]) {
            scroll = (scroll - Math.signum(scrollY).toInt() * 30).coerceIn(0, maxScroll)
            return true
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY)
    }

    override fun onClose() = back()

    private companion object {
        // The browser opens as it was left, for the rest of the game.
        var query = ""
        var sort = LayoutBrowserModel.Sort.NEWEST
        var completeOnly = true
    }
}
