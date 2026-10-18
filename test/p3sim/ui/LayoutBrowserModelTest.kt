package com.engineerclient.p3sim.clear.ui

import com.engineerclient.p3sim.pack.Fixtures
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LayoutBrowserModelTest {
    /** Invented layouts: [extra] is raw JSON fields of the layout. */
    private fun layout(id: String, extra: String, room: String = "Wide Hall") = """
      {"id":"$id","floor":"F7","doorsComplete":true,"path":[0,1],"party":[],$extra
       "rooms":[
        {"i":0,"name":"Entrance","family":"Entrance","type":"entrance","shape":"1x1","tiles":[[3,0]],"rot":"NORTH","secretsMax":0},
        {"i":1,"name":"$room","family":"Wide_Hall","type":"normal","shape":"1x1","tiles":[[3,1]],"rot":"WEST","secretsMax":3}
       ],
       "doors":[{"axis":"S","a":[3,0],"b":[3,1],"ra":0,"rb":1,"kind":"entrance"}],"keys":[]}"""

    private val pack = LayoutPack.fromJson(Fixtures.json("""{"format":"ads-layouts","version":1,"layouts":[
      ${layout("2026-09-01_10-00-00_F7", """"playable":true,"endTick":6000,"puzzles":["Boulder"],"secretsTotal":50,""")},
      ${layout("2026-09-02_11-30-00_F7", """"playable":true,"endTick":4800,"puzzles":["Boulder","Teleport Maze","Ice Fill"],"secretsTotal":70,""", "Dino Site")},
      ${layout("2026-09-03_12-00-00_F7", """"playable":true,"puzzles":[],"secretsTotal":60,""")},
      ${layout("2026-09-04_13-00-00_F7", """"playable":false,"endTick":3000,"puzzles":["Blaze"],"secretsTotal":90,""")}
    ]}"""))!!

    private val rows = LayoutBrowserModel.rows(pack)

    private fun ids(v: List<LayoutRow>) = v.map { it.id.substring(0, 10) }

    @Test fun rowsSummariseEachLayout() {
        val r = rows.first { it.id.startsWith("2026-09-01") }
        assertEquals(2, r.rooms); assertEquals(listOf("Boulder"), r.puzzles); assertEquals(300, r.seconds); assertEquals("5:00", r.time)
        assertEquals("2026-09-01 10:00", r.title)
        assertTrue(r.line().contains("1 puzzles"), r.line())
        assertEquals(null, rows.first { it.id.startsWith("2026-09-03") }.seconds)
    }

    @Test fun poolOnlyHidesIncompleteLayouts() {
        assertEquals(3, LayoutBrowserModel.view(rows, "", LayoutBrowserModel.Sort.NEWEST, true).size)
        assertEquals(4, LayoutBrowserModel.view(rows, "", LayoutBrowserModel.Sort.NEWEST, false).size)
        assertTrue(rows.first { it.id.startsWith("2026-09-04") }.line().contains("not complete"))
    }

    @Test fun sortsPutTheBestFirstAndUnknownTimesLast() {
        assertEquals(listOf("2026-09-03", "2026-09-02", "2026-09-01"), ids(LayoutBrowserModel.view(rows, "", LayoutBrowserModel.Sort.NEWEST, true)))
        assertEquals(listOf("2026-09-02", "2026-09-01", "2026-09-03"), ids(LayoutBrowserModel.view(rows, "", LayoutBrowserModel.Sort.FASTEST, true)))
        assertEquals("2026-09-02", ids(LayoutBrowserModel.view(rows, "", LayoutBrowserModel.Sort.PUZZLES, true)).first())
        assertEquals("2026-09-04", ids(LayoutBrowserModel.view(rows, "", LayoutBrowserModel.Sort.SECRETS, false)).first())
        assertEquals("2026-09-02", ids(LayoutBrowserModel.view(rows, "", LayoutBrowserModel.Sort.SECRETS, true)).first())
    }

    @Test fun searchNeedsEveryWordInTheIdPuzzlesOrRooms() {
        val s = LayoutBrowserModel.Sort.NEWEST
        assertEquals(listOf("2026-09-02"), ids(LayoutBrowserModel.view(rows, "boulder dino", s, true)))
        assertEquals(2, LayoutBrowserModel.view(rows, "BOULDER", s, true).size)
        assertEquals(listOf("2026-09-03"), ids(LayoutBrowserModel.view(rows, "09-03", s, true)))
        assertEquals(0, LayoutBrowserModel.view(rows, "nothing like this", s, true).size)
    }

    @Test fun pagesCoverEveryRowOnce() {
        assertEquals(1, LayoutBrowserModel.pageCount(0, 5)); assertEquals(2, LayoutBrowserModel.pageCount(6, 5))
        assertEquals(listOf(rows[3]), LayoutBrowserModel.page(rows, 1, 3))
        assertEquals(LayoutBrowserModel.page(rows, 1, 3), LayoutBrowserModel.page(rows, 99, 3)) // clamped
        assertEquals(3, LayoutBrowserModel.page(rows, -4, 3).size)
    }

    @Test fun aTypedIdResolvesByExactStartOrContains() {
        val all = rows.map { it.id }
        assertEquals("2026-09-02_11-30-00_F7", (LayoutBrowserModel.resolve(all, "2026-09-02") as LayoutBrowserModel.Resolved.One).id)
        assertEquals("2026-09-03_12-00-00_F7", (LayoutBrowserModel.resolve(all, "12-00-00") as LayoutBrowserModel.Resolved.One).id)
        assertEquals("2026-09-01_10-00-00_F7", (LayoutBrowserModel.resolve(all, "2026-09-01_10-00-00_F7") as LayoutBrowserModel.Resolved.One).id)
        assertEquals(4, (LayoutBrowserModel.resolve(all, "2026-09") as LayoutBrowserModel.Resolved.Many).ids.size)
        assertIs<LayoutBrowserModel.Resolved.Nothing>(LayoutBrowserModel.resolve(all, "zzz"))
    }

    @Test fun clocksAndTitles() {
        assertEquals("0:59", LayoutBrowserModel.clock(59)); assertEquals("1:05:12", LayoutBrowserModel.clock(3912))
        assertEquals("odd-id", LayoutBrowserModel.title("odd-id"))
        assertNotNull(pack)
    }
}
