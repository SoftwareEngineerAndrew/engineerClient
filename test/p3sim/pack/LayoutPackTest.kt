package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.layouts.DoorKind
import com.engineerclient.p3sim.pack.layouts.LayoutPack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The layout pool loader (FORMAT-layouts.md), on a synthetic 3-room layout. Names, ids and ticks are made up. */
class LayoutPackTest {
    private fun layout(id: String, playable: Boolean?, floor: String = "F7", dupOf: String? = null) = """
        {"id":"$id","floor":"$floor","date":"2000-01-0${id.last()}","source":"adr","t0Basis":"mort","endTick":3000,"party":["MAGE","ARCHER"],
         "rooms":[
          {"i":0,"name":"Entrance","family":"Entrance","design":null,"core":77,"type":"entrance","shape":"1x1","tiles":[[3,0]],"rot":"NORTH","rotSource":"odin-key","rotOdin":"NORTH","secretsMax":0,"secretsFound":0,"tSeen":-100,"tNone":3,"tWhite":null,"tGreen":13,"tRed":null},
          {"i":1,"name":"Wide Hall","family":"Wide_Hall","design":"Wide_Hall","type":"normal","shape":"1x2","tiles":[[3,1],[3,2]],"rot":"WEST","rotSource":"tiles","secretsMax":2,"tNone":13,"tWhite":33},
          {"i":2,"name":"Blood","family":"Blood","design":null,"type":"blood","shape":"1x1","tiles":[[4,2]],"rot":null,"secretsMax":null}
         ],
         "doors":[
          {"axis":"S","a":[3,0],"b":[3,1],"ra":0,"rb":1,"read":"e","kind":"entrance","fix":false,"tOpen":3},
          {"axis":"E","a":[3,2],"b":[4,2],"ra":1,"rb":2,"read":"b","kind":"blood","fix":false,"tOpen":356},
          {"axis":"E","a":[3,1],"b":[4,1],"ra":1,"rb":9,"read":null,"kind":"weird","fix":true,"tOpen":null}
         ],
         "doorsComplete":true,"path":[0,1,2],
         "keys":[{"kind":"blood","tSpawn":301,"x":-23.0,"z":-14.0,"room":1,"tPickup":300,"tGone":303}],
         "puzzles":[],"secretsTotal":2,"buildable":null,"playable":${playable ?: "null"},"dupOf":${if (dupOf == null) "null" else "\"$dupOf\""},"rotSummary":{"tiles":1,"odin-key":1}}
    """.trimIndent()

    private fun pack(extra: String = ""): LayoutPack {
        val text = """{"format":"ads-layouts","version":1,"mc":"26.3","roomsPackVersion":1,"aliases":{"Wide Hall":"Wide_Hall"},"stats":{"n":3},
            "layouts":[${layout("c-3", true)},${layout("a-1", true)},${layout("b-2", false)},${layout("d-4", true, dupOf = "a-1")},${layout("e-5", true, floor = "M7")}$extra]}"""
        return assertNotNull(LayoutPack.fromJson(Fixtures.json(text)))
    }

    @Test fun parsesRoomsDoorsKeys() {
        val l = pack().layout("a-1")!!
        assertEquals(3, l.rooms.size)
        val e = l.rooms[0]
        assertEquals(Rot.NORTH, e.rot); assertEquals(77, e.core); assertEquals(listOf(Tile(3, 0)), e.tiles); assertEquals(3, e.tNone); assertEquals(13, e.tGreen)
        assertNull(e.design); assertEquals(0, e.secretsMax)
        assertEquals(Rot.WEST, l.rooms[1].rot); assertEquals("Wide_Hall", l.rooms[1].design)
        assertNull(l.rooms[2].rot, "unresolved rotation stays null")
        assertEquals(DoorKind.ENTRANCE, l.doors[0].kind); assertEquals(3, l.doors[0].tOpen)
        assertEquals(DoorKind.BLOOD, l.doors[1].kind)
        assertEquals(DoorKind.UNKNOWN, l.doors[2].kind, "an unknown kind word is UNKNOWN, not a crash")
        assertTrue(l.doors[0].kind.connected); assertTrue(!l.doors[2].kind.connected)
        assertEquals(listOf(0, 1, 2), l.path)
        assertEquals(1, l.keys.size); assertEquals(-23.0, l.keys[0].x); assertEquals("blood", l.keys[0].kind)
        assertEquals(listOf("MAGE", "ARCHER"), l.party)
        assertEquals(2, l.secretsTotal)
    }

    @Test fun lookups() {
        val l = pack().layout("a-1")!!
        assertEquals("Wide Hall", l.roomAt(3, 2)!!.name)
        assertNull(l.roomAt(0, 0))
        assertEquals(DoorKind.ENTRANCE, l.doorAt('S', 3, 0)!!.kind)
        assertNull(l.doorAt('S', 3, 1))
        assertEquals(-89 to -169, l.doorSpot('S', 3, 0), "S slot (3,0): x = -185 + 96, z = -169")
        assertEquals(-73 to -121, l.doors[1].spot, "E slot (3,2): x = -169 + 96, z = -185 + 64")
    }

    @Test fun defaultPoolAndDeterministicPick() {
        val p = pack()
        assertEquals(listOf("a-1", "c-3"), p.pool().map { it.id }, "playable, not a duplicate, F7")
        // Same seed, same pack -> same layout, and every seed lands in the pool.
        for (seed in 0L..20L) { assertEquals(p.pick(seed)!!.id, p.pick(seed)!!.id); assertTrue(p.pick(seed)!!.id in listOf("a-1", "c-3")) }
        assertEquals(setOf("a-1", "c-3"), (0L..40L).map { p.pick(it)!!.id }.toSet(), "both layouts are reachable")
        assertNull(p.pick(1) { false })
        assertEquals("b-2", p.layout("b-2")!!.id, "an explicit id bypasses the filter")
    }

    @Test fun aliasesAndOrdering() {
        val p = pack()
        assertEquals("Wide_Hall", p.familyOf("Wide Hall"))
        assertEquals(listOf("a-1", "b-2", "c-3", "d-4", "e-5"), p.layouts.map { it.id })
    }

    @Test fun aBrokenLayoutIsSkippedNotFatal() {
        val r = PackReport()
        val p = LayoutPack.fromJson(Fixtures.json("""{"format":"ads-layouts","version":1,"layouts":[{"floor":"F7"},{"id":"x-1","rooms":[{"name":"no index"}]}]}"""), null, r)!!
        assertEquals(0, p.layouts.size)
        assertEquals(2, r.warnings().count { it.contains("skipped") })
    }

    @Test fun refusesNewerVersion() {
        val r = PackReport()
        assertNull(LayoutPack.fromJson(Fixtures.json("""{"format":"ads-layouts","version":2,"layouts":[]}"""), null, r))
        assertTrue(r.errors().single().contains("version 2"))
    }

    @Test fun loadsFromAFolderAndMissingIsAMessage(@TempDir dir: Path) {
        val r = PackReport()
        assertNull(LayoutPack.load(dir, null, r)); assertTrue(r.errors().single().contains("missing"))
        Files.write(dir.resolve("layouts.json.gz"), Fixtures.gzip("""{"format":"ads-layouts","version":1,"layouts":[]}"""))
        assertNotNull(LayoutPack.load(dir))
    }
}
