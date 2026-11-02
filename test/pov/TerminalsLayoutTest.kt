package com.engineerclient.pov

import com.engineerclient.pov.TerminalsLayout.Rect
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Terminals Status' layout: leap menu order, even spacing, every terminal's window and text fitting its panel, and the mouse. */
class TerminalsLayoutTest {
    /** Vanilla's font (textures/font/ascii.png, 26.3): each character's advance from space to '~', the glyph's width plus one. */
    private val ADVANCE = "42466662444626266666666666225656766666666466666666666666666464663666665662653666666646666664247"
    private val width = { s: String -> s.sumOf { c -> if (c in ' '..'~') ADVANCE[c - ' '] - '0' else 6 } }

    /**
     * Every terminal: its chest's slots (Odin's TerminalTypes.windowSize) and its clickable grid (rows, cols, first row, first
     * column), as PoseShare.GRIDS has them.
     */
    private class Term(val name: String, val slots: Int, val rows: Int, val cols: Int, val row0: Int, val col0: Int) {
        fun inGrid(s: Int) = s / 9 - row0 in 0 until rows && s % 9 - col0 in 0 until cols
    }
    private val TERMS = listOf(
        Term("Panes", 45, 3, 5, 1, 2), Term("Rubix", 45, 3, 3, 1, 3), Term("Numbers", 36, 2, 7, 1, 1),
        Term("Starts With", 45, 3, 7, 1, 1), Term("Select", 54, 4, 7, 1, 1), Term("Melody", 54, 5, 9, 0, 0),
    )
    private val DYES = listOf("White", "Orange", "Magenta", "Light blue", "Yellow", "Lime", "Pink", "Gray", "Light gray", "Cyan", "Purple", "Blue", "Brown", "Green", "Red", "Black")
    /** Every title the HUD can write (TerminalsStatus.title). */
    private val TITLES = listOf("Panes", "Rubix", "Numbers", "Melody") + ('A'..'Z').map { "Starts With '$it'" } + DYES.map { "Select $it" }
    private val panels = (0..3).map(TerminalsLayout::panel)

    @Test fun panelsAreInLeapMenuOrder() {
        // Odin's leap menu: slot 0 top left, 1 top right, 2 bottom left, 3 bottom right.
        val (tl, tr, bl, br) = panels
        assertEquals(tl.y, tr.y); assertEquals(bl.y, br.y)
        assertEquals(tl.x, bl.x); assertEquals(tr.x, br.x)
        assertTrue(tl.x < tr.x && tl.y < bl.y)
        assertEquals(0, tl.x); assertEquals(0, tl.y)
    }

    @Test fun panelsAreTheSameSizeEvenlySpacedAndInsideTheHud() {
        val hud = Rect(0, 0, TerminalsLayout.TOTAL_W, TerminalsLayout.TOTAL_H)
        for (p in panels) {
            assertEquals(TerminalsLayout.PANEL_W, p.w); assertEquals(TerminalsLayout.PANEL_H, p.h)
            assertTrue(hud.contains(p), "$p outside $hud")
        }
        for (a in panels) for (b in panels) if (a !== b) assertFalse(a.overlaps(b), "$a overlaps $b")
        assertEquals(TerminalsLayout.SPACE, panels[1].x - panels[0].right)
        assertEquals(TerminalsLayout.SPACE, panels[2].y - panels[0].bottom)
        // The HUD's reported size is exactly the panels' bounds (no dead edge for Odin's HUD editor box).
        assertEquals(panels[3].right, hud.right); assertEquals(panels[3].bottom, hud.bottom)
    }

    @Test fun badSlotsAndChestsAreRefused() {
        for (i in listOf(-1, 4)) assertTrue(runCatching { TerminalsLayout.panel(i) }.isFailure)
        for (r in listOf(0, 7)) assertTrue(runCatching { TerminalsLayout.chest(panels[0], r) }.isFailure)
    }

    @Test fun chestRows() {
        assertEquals(4, TerminalsLayout.rows(36)); assertEquals(5, TerminalsLayout.rows(45)); assertEquals(6, TerminalsLayout.rows(54))
        // Never past what a panel keeps room for, never none.
        assertEquals(6, TerminalsLayout.rows(90)); assertEquals(1, TerminalsLayout.rows(0))
    }

    @Test fun linesAndWindowDontTouch() {
        val L = TerminalsLayout
        assertTrue(L.NAME_Y >= L.PAD)
        assertTrue(L.NAME_Y + L.TEXT_H < L.TITLE_Y, "name runs into the title")
        assertTrue(L.TITLE_Y + L.TEXT_H + L.LINE_GAP <= L.CHEST_Y - 1, "title runs into the window's frame")
        // Breathing room under the biggest window matches the panel's edge padding.
        assertEquals(L.PAD, L.PANEL_H - (L.CHEST_Y + L.CHEST_AREA_H))
        assertTrue(L.CHEST_W <= L.INNER_W)
    }

    @Test fun everyTerminalsWindowFitsCentredAcrossJustUnderTheText() {
        for (p in panels) for (t in TERMS) {
            val c = TerminalsLayout.chest(p, TerminalsLayout.rows(t.slots))
            val inner = Rect(p.x + TerminalsLayout.PAD, p.y + TerminalsLayout.CHEST_Y, TerminalsLayout.INNER_W, TerminalsLayout.CHEST_AREA_H)
            assertTrue(inner.contains(c), "${t.name} window $c outside $inner")
            // Its 1-pixel frame inside the panel too.
            assertTrue(p.contains(Rect(c.x - 1, c.y - 1, c.w + 2, c.h + 2)))
            assertTrue(abs((c.x - inner.x) - (inner.right - c.right)) <= 1, "${t.name} not centred across: ${c.x - inner.x} vs ${inner.right - c.right}")
            assertEquals(p.y + TerminalsLayout.CHEST_Y, c.y, "${t.name} window not just under the title")
            assertEquals(TerminalsLayout.rows(t.slots) * TerminalsLayout.CELL - TerminalsLayout.GAP, c.h)
        }
    }

    @Test fun slotsTileTheWindowWithEvenGaps() {
        for (t in TERMS) {
            val c = TerminalsLayout.chest(panels[3], TerminalsLayout.rows(t.slots))
            val cells = (0 until t.slots).map { TerminalsLayout.slot(c, it) }
            for (s in cells) assertTrue(c.contains(s), "${t.name} slot $s outside $c")
            for (i in cells.indices) for (j in i + 1 until cells.size) assertFalse(cells[i].overlaps(cells[j]), "${t.name} slots overlap")
            // First and last slots reach the window's corners: no lopsided margin.
            assertEquals(c.x, cells.first().x); assertEquals(c.y, cells.first().y)
            assertEquals(c.right, cells.last().right); assertEquals(c.bottom, cells.last().bottom)
            assertEquals(TerminalsLayout.GAP, TerminalsLayout.slot(c, 1).x - TerminalsLayout.slot(c, 0).right)
            assertEquals(TerminalsLayout.GAP, TerminalsLayout.slot(c, 9).y - TerminalsLayout.slot(c, 0).bottom)
        }
    }

    @Test fun everyClickableSlotIsInsideItsChest() {
        // Each terminal's grid is within its window's slots, so every cell the leap menu lights is drawn on the window here.
        for (t in TERMS) {
            val inGrid = (0 until 54).filter(t::inGrid)
            assertEquals(t.rows * t.cols, inGrid.size, t.name)
            assertTrue(inGrid.all { it < t.slots }, "${t.name} grid past its ${t.slots} slots")
        }
    }

    @Test fun idleTeammateIsCentredInTheirPanel() {
        for (p in panels) for (name in listOf("a", "Steve", "WWWWWWWWWWWWWWWW")) {
            val nameInk = width(name) - 1; val noteInk = width(TerminalsLayout.IDLE) - 1
            val (nx, ny) = TerminalsLayout.idleName(p, nameInk)
            val (ox, oy) = TerminalsLayout.idleNote(p, noteInk)
            val nameBox = Rect(nx, ny, nameInk, TerminalsLayout.TEXT_H); val noteBox = Rect(ox, oy, noteInk, TerminalsLayout.TEXT_H)
            for (b in listOf(nameBox, noteBox)) {
                assertTrue(p.contains(b), "$b outside $p")
                assertTrue(abs((b.x - p.x) - (p.right - b.right)) <= 1, "$b not centred across $p")
            }
            assertFalse(nameBox.overlaps(noteBox))
            assertEquals(TerminalsLayout.LINE_GAP, noteBox.y - nameBox.bottom)
            // The two lines together sit in the middle, top to bottom.
            assertTrue(abs((nameBox.y - p.y) - (p.bottom - noteBox.bottom)) <= 1, "idle text not centred down: ${nameBox.y - p.y} vs ${p.bottom - noteBox.bottom}")
        }
    }

    @Test fun countSitsAsFarFromTheRightAsTheNameFromTheLeft() {
        for (p in panels) for (count in listOf("15 left", "1 left", "9 left")) {
            val ink = width(count) - 1
            val x = TerminalsLayout.rightAligned(p, ink)
            assertEquals(TerminalsLayout.PAD, p.right - (x + ink))
        }
    }

    @Test fun everyTitleFitsWhole() {
        for (t in TITLES) {
            assertTrue(width(t) <= TerminalsLayout.INNER_W, "\"$t\" is ${width(t)} px, the line is ${TerminalsLayout.INNER_W}")
            assertEquals(t, TerminalsLayout.fit(t, TerminalsLayout.INNER_W, width))
        }
    }

    @Test fun nameAndCountShareTheLineWithoutTouching() {
        val names = listOf("a", "Steve", "undonecoffee", "xX_Dungeon_Xx", "WWWWWWWWWWWWWWWW", "mmmmmmmmmmmmmmmm", "iiiiiiiiiiiiiiii", "________________")
        // The widest count a terminal can have (Panes' 15, Select's 14...) and none (Melody).
        for (name in names) for (count in listOf("15 left", "1 left", "")) {
            val shown = TerminalsLayout.nameBeside(name, count, width)
            val countX = TerminalsLayout.INNER_W - width(count)
            if (count.isNotEmpty()) assertTrue(width(shown) + TerminalsLayout.SPACE <= countX, "\"$shown\" runs into \"$count\"")
            assertTrue(width(shown) <= TerminalsLayout.INNER_W)
            if (shown != name) assertTrue(shown.endsWith("..") && name.startsWith(shown.removeSuffix("..")), "\"$name\" cut to \"$shown\"")
        }
        // Ordinary names are never cut, even beside the widest count.
        for (name in listOf("Steve", "undonecoffee", "xX_Dungeon_Xx", "Technoblade", "Dream_123")) assertEquals(name, TerminalsLayout.nameBeside(name, "15 left", width))
    }

    @Test fun fitCutsOnlyWhatItMust() {
        assertEquals("", TerminalsLayout.fit("", 10, width))
        assertEquals("abc", TerminalsLayout.fit("abc", width("abc"), width))
        val cut = TerminalsLayout.fit("abcd", width("abcd") - 1, width)
        assertTrue(cut.endsWith("..") && width(cut) <= width("abcd") - 1)
        // As much kept as fits: one more character wouldn't.
        val long = "abcdefghijklmnopqrstuvwxyz"
        val f = TerminalsLayout.fit(long, 60, width)
        val kept = f.removeSuffix("..")
        assertTrue(width(f) <= 60 && width(long.substring(0, kept.length + 1) + "..") > 60)
        assertEquals("..", TerminalsLayout.fit(long, 1, width))
    }

    @Test fun leftCount() {
        assertEquals("15 left", TerminalsLayout.left(15, false))
        assertEquals("", TerminalsLayout.left(3, true))
    }

    @Test fun slotNumbersAreCentred() {
        val cell = TerminalsLayout.slot(TerminalsLayout.chest(panels[0], 4), 10)
        // The glyph's ink is its advance less one (the gap after it): that ink is centred, within a pixel.
        for (d in 1..9) {
            val ink = width("$d") - 1
            val x = TerminalsLayout.centred(cell, ink)
            assertTrue(abs((x - cell.x) - (cell.right - (x + ink))) <= 1, "$d at $x in $cell")
        }
        // A slot is tall enough for the font's 7-pixel digits with a pixel above.
        assertTrue(TerminalsLayout.CELL - TerminalsLayout.GAP >= 7 + 2)
    }

    @Test fun mouseRoundTripsToASixteenthOfASlot() {
        var u = -1.0
        while (u < 14.9) {
            val back = TerminalsLayout.decodeMouse(TerminalsLayout.encodeMouse(u))!!
            assertTrue(abs(back - u) <= 1.0 / 32 + 1e-9, "$u came back $back")
            u += 0.013
        }
        // Off the ends: held at the last byte that means a place, never the "not known" one.
        assertEquals(0, TerminalsLayout.encodeMouse(-5.0))
        assertEquals(TerminalsLayout.NO_MOUSE - 1, TerminalsLayout.encodeMouse(99.0))
        assertEquals(TerminalsLayout.NO_MOUSE, TerminalsLayout.encodeMouse(Double.NaN))
        assertNull(TerminalsLayout.decodeMouse(TerminalsLayout.NO_MOUSE))
        // Every slot's middle of the biggest chest is a place.
        for (s in 0 until 54) {
            assertTrue(TerminalsLayout.encodeMouse(s % 9 + 0.5) < TerminalsLayout.NO_MOUSE)
            assertTrue(TerminalsLayout.encodeMouse(s / 9 + 0.5) < TerminalsLayout.NO_MOUSE)
        }
    }

    @Test fun mouseOverASlotPointsIntoThatSlot() {
        for (p in panels) for (t in TERMS) {
            val c = TerminalsLayout.chest(p, TerminalsLayout.rows(t.slots))
            for (s in 0 until t.slots) {
                // The middle of the slot, as sent from the vanilla chest or Odin's custom GUI.
                val (x, y) = TerminalsLayout.cursor(p, c, s % 9 + 0.5, s / 9 + 0.5)
                val cell = TerminalsLayout.slot(c, s)
                assertTrue(x >= cell.x && x < cell.right && y >= cell.y && y < cell.bottom, "${t.name} slot $s: tip at $x,$y not in $cell")
            }
        }
    }

    @Test fun mouseOffTheWindowStaysInThePanel() {
        for (p in panels) {
            val c = TerminalsLayout.chest(p, 6)
            for ((u, v) in listOf(-1.0 to -1.0, 14.9 to 14.9, -1.0 to 14.9, 14.9 to -1.0, 4.5 to 20.0)) {
                val (x, y) = TerminalsLayout.cursor(p, c, u, v)
                assertTrue(p.contains(Rect(x, y, TerminalsLayout.CURSOR_W, TerminalsLayout.CURSOR_H)), "cursor at $x,$y leaves $p")
            }
        }
    }

    @Test fun cursorBitmapIsAWellFormedArrow() {
        val bmp = TerminalsLayout.CURSOR
        assertEquals(TerminalsLayout.CURSOR_H, bmp.size)
        for (row in bmp) { assertEquals(TerminalsLayout.CURSOR_W, row.length); assertTrue(row.all { it in "EF." }) }
        // The tip is the top left pixel, and every white pixel is walled in by edge or more white (none touches empty space).
        assertEquals('E', bmp[0][0])
        for (y in bmp.indices) for (x in bmp[y].indices) if (bmp[y][x] == 'F')
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val n = bmp.getOrNull(y + dy)?.getOrNull(x + dx)
                assertTrue(n == 'E' || n == 'F', "white at $x,$y touches ${n ?: "the outside"}")
            }
    }

    /**
     * A picture of the example HUD, with vanilla's own font, for looking at by eye: TERMINALS_STATUS_PNG=<out.png> and
     * TERMINALS_STATUS_FONT=<ascii.png from the client jar>. Skipped otherwise. Colours as TerminalsStatus draws them.
     */
    @Test fun renderPicture() {
        val out = System.getenv("TERMINALS_STATUS_PNG"); val fontPath = System.getenv("TERMINALS_STATUS_FONT")
        assumeTrue(out != null && fontPath != null)
        val font = ImageIO.read(File(fontPath))
        val k = 4
        val img = BufferedImage((TerminalsLayout.TOTAL_W + 20) * k, (TerminalsLayout.TOTAL_H + 20) * k, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        g2.color = java.awt.Color(90, 110, 80); g2.fillRect(0, 0, img.width, img.height)
        fun fill(x: Int, y: Int, x1: Int, y1: Int, argb: Int) { g2.color = java.awt.Color(argb, true); g2.fillRect((x + 10) * k, (y + 10) * k, (x1 - x) * k, (y1 - y) * k) }
        fun text(s: String, x: Int, y: Int, rgb: Int) {
            for ((dx, dy, col) in listOf(Triple(1, 1, (rgb and 0xFCFCFC) shr 2), Triple(0, 0, rgb))) {
                var cx = x
                for (c in s) {
                    val code = c.code
                    if (code in 33..126) for (py in 0 until 8) for (px in 0 until 8) {
                        if ((font.getRGB((code % 16) * 8 + px, (code / 16) * 8 + py) ushr 24) != 0) fill(cx + px + dx, y + py + dy, cx + px + dx + 1, y + py + dy + 1, 0xFF000000.toInt() or col)
                    }
                    cx += width(c.toString())
                }
            }
        }
        // (name, terminal index or -1, title, solution, mouse)
        data class Ex(val name: String, val term: Int, val title: String, val sol: List<Int>, val mouse: Pair<Double, Double>)
        val examples = listOf(
            Ex("Archer", 2, "Numbers", listOf(14, 15, 16, 21, 22, 23), 5.4 to 1.6), Ex("Mage", -1, "", emptyList(), 0.0 to 0.0),
            Ex("Healer", 4, "Select Light gray", listOf(10, 13, 20, 24, 30, 41), 2.5 to 2.3), Ex("WWWWWWWWWWWWWWWW", 5, "Melody", listOf(3, 39, 43), 7.5 to 4.5),
        )
        val accents = listOf(0xFFFFAA00.toInt(), 0xFF55FFFF.toInt(), 0xFFFF55FF.toInt(), 0xFF00AA00.toInt())
        for ((i, e) in examples.withIndex()) {
            val p = panels[i]
            val active = e.term >= 0
            fill(p.x, p.y, p.right, p.bottom, if (active) 0xA0000000.toInt() else 0x50000000)
            fill(p.x, p.y, p.right, p.y + 1, if (active) accents[i] else (accents[i] and 0xFFFFFF) or 0x60000000)
            if (!active) {
                TerminalsLayout.idleName(p, width(e.name) - 1).let { (x, y) -> text(e.name, x, y, 0x969696) }
                TerminalsLayout.idleNote(p, width(TerminalsLayout.IDLE) - 1).let { (x, y) -> text(TerminalsLayout.IDLE, x, y, 0x787878) }
                continue
            }
            val t = TERMS[e.term]
            val count = TerminalsLayout.left(e.sol.size, t.name == "Melody")
            text(TerminalsLayout.nameBeside(e.name, count, width), p.x + TerminalsLayout.PAD, p.y + TerminalsLayout.NAME_Y, 0xFFFFFF)
            if (count.isNotEmpty()) text(count, TerminalsLayout.rightAligned(p, width(count) - 1), p.y + TerminalsLayout.NAME_Y, 0x969696)
            text(e.title, p.x + TerminalsLayout.PAD, p.y + TerminalsLayout.TITLE_Y, 0xFFD25A)
            val c = TerminalsLayout.chest(p, TerminalsLayout.rows(t.slots))
            fill(c.x - 1, c.y - 1, c.right + 1, c.bottom + 1, 0x40000000)
            for (s in 0 until t.slots) {
                val cell = TerminalsLayout.slot(c, s)
                val n = e.sol.indexOf(s).takeIf { it >= 0 }
                val color = when {
                    !t.inGrid(s) -> 0x10FFFFFF
                    n == null && t.name != "Panes" -> 0x30FFFFFF
                    t.name == "Numbers" -> when (n) { null -> 0x60303030; 0 -> 0xF040D040.toInt(); 1 -> 0xE0D0C030.toInt(); 2 -> 0xD0D07020.toInt(); else -> 0xC0A04040.toInt() }
                    t.name == "Melody" -> when (n) { 0 -> 0xF040D040.toInt(); 1 -> 0xF0D040D0.toInt(); 2 -> 0xF080FF80.toInt(); else -> 0x40303030 }
                    else -> if (n != null) 0xE040D040.toInt() else 0x60303030
                }
                fill(cell.x, cell.y, cell.right, cell.bottom, color)
                if (t.name == "Numbers" && n != null && n < 3) text("${n + 1}", TerminalsLayout.centred(cell, width("${n + 1}") - 1), cell.y + 1, 0xFFFFFF)
            }
            val (cx, cy) = TerminalsLayout.cursor(p, c, e.mouse.first, e.mouse.second)
            TerminalsLayout.CURSOR.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch != '.') fill(cx + x, cy + y, cx + x + 1, cy + y + 1, if (ch == 'E') 0xFF000000.toInt() else -1) } }
        }
        g2.dispose()
        ImageIO.write(img, "png", File(out))
    }
}
