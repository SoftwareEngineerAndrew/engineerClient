package com.engineerclient.p3sim.pack

import com.engineerclient.p3sim.pack.rooms.BytesBlobSource
import com.engineerclient.p3sim.pack.rooms.PlacementException
import com.engineerclient.p3sim.pack.rooms.RoomBlobs
import com.engineerclient.p3sim.pack.rooms.RoomPack
import com.engineerclient.p3sim.pack.rooms.RoomVolume
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The room library loader and the blob decoders, against a synthetic pack written by [Fixtures] (an independent encoder of FORMAT-rooms 5). */
class RoomPackTest {
    private val pal = listOf("minecraft:air", "minecraft:stone", "minecraft:oak_stairs[facing=east,half=bottom]")

    /** A 31x31 room, 3 layers from y 69: the bottom layer stone, one stairs block at (2, 70, 3). */
    private fun volumeCells(): IntArray {
        val w = 31; val d = 31; val h = 3
        val cells = IntArray(w * d * h)
        for (i in 0 until w * d) cells[i] = 1
        cells[((70 - 69) * d + 3) * w + 2] = 2
        return cells
    }

    private class Built(val index: String, val blocks: ByteArray)

    private fun build(version: Int = 1, format: String = "ads-rooms", blocksCrcOk: Boolean = true): Built {
        val vol = Fixtures.volume(31, 31, 3, 69, pal, volumeCells())
        // The variant clears the stairs and sets (0,71,0) to stone.
        val patchPal = listOf("minecraft:air", "minecraft:stone")
        val patch = Fixtures.patch(31, 31, 3, 69, patchPal, listOf(((70 - 69) * 31 + 3) * 31 + 2 to 0, ((71 - 69) * 31 + 0) * 31 + 0 to 1))
        val blocks = vol + patch
        val crc = if (blocksCrcOk) Fixtures.crcHex(blocks) else "00000000"
        val index = """
        {"format":"$format","version":$version,"mc":"26.3","generated":"2000-01-01T00:00:00Z","blocksFile":"rooms.blocks.bin",
         "blocksFileBytes":${blocks.size},"blocksFileCrc32":"$crc","textures":[{"id":"aa","value":"dg==","profileId":null,"role":"decor"}],
         "designs":[
          {"id":"Alpha","name":"Alpha","family":"Alpha","buildKey":null,"type":"normal","shape":"1x1","tiles":[[0,0]],"W":31,"D":31,"H":3,"y0":69,"roofY":71,
           "rotations":["SOUTH","WEST","NORTH","EAST"],"doors":"0101","source":{"blocks":"ps-capture","file":"Alpha.json","captureRotation":1},
           "vol":{"kind":"volume","off":0,"len":${vol.size},"crc32":"${Fixtures.crcHex(vol)}","nonAir":962},
           "slots":[{"tile":[0,0],"side":"E","open":true,"seal":null},{"tile":[0,0],"side":"N","open":false,"seal":{"src":"derived","incomplete":false,"cells":[[14,69,0,"minecraft:stone"]]}}],
           "blockEntities":[{"x":2,"y":70,"z":3,"type":"skull","tex":0},{"x":4,"y":70,"z":4,"type":"sign","tex":null,"text":["a","b","",""],"marker":true}],
           "secrets":[{"id":"s1","type":"chest","bucket":"rchest","x":5,"y":70,"z":5,"facing":"north","baked":false}],
           "levers":[{"pos":[1,70,1],"opens":[[2,70,2]]}],"crypts":[[[1,70,1],[1,70,2]]],"superboomWalls":[],"fallingBlocks":[[3,70,3]],
           "crushers":[{"id":"c1","slab":[1,2,3,4,5,6],"axis":"x","sign":1,"sweep":15,"material":"minecraft:stone"}],
           "tripwires":[[1,70,1,"east"]],"puzzle":{"kind":"quiz","anchors":{"chests":[[1,70,2],[3,70,4]],"chest":[15,70,20]}},
           "odin":{"name":"Alpha","type":"NORMAL","secrets":4,"cores":[123]},
           "variants":[{"id":"v1","kind":"state","core":456,"srcFile":"x","changedCells":2,"vol":{"kind":"patch","off":${vol.size},"len":${patch.size},"crc32":"${Fixtures.crcHex(patch)}","nonAir":1}}],
           "gaps":["a gap"]},
          {"id":"Fairy","name":"Fairy","family":"Fairy","type":"fairy","shape":"1x1","tiles":[[0,0]],"W":31,"D":31,"H":3,"y0":69,"rotations":["SOUTH"],
           "vol":{"kind":"volume","off":0,"len":${vol.size},"crc32":"${Fixtures.crcHex(vol)}","nonAir":962},"unknownFutureField":{"x":1}}
         ],
         "cores":[{"core":123,"design":"Alpha","variant":null},{"core":456,"design":"Alpha","variant":"v1"}],
         "aliases":{"Silver Sword":"Silvers_Sword","Criss-Cross":"Criss_Cross"}}
        """.trimIndent()
        return Built(index, blocks)
    }

    @Test fun parsesDesignFields() {
        val b = build()
        val pack = assertNotNull(RoomPack.fromIndex(Fixtures.json(b.index), BytesBlobSource(b.blocks)))
        val a = assertNotNull(pack.design("Alpha"))
        assertEquals(31, a.w); assertEquals(69, a.y0); assertEquals(71, a.roofY)
        assertEquals(listOf(Rot.SOUTH, Rot.WEST, Rot.NORTH, Rot.EAST), a.rotations)
        assertEquals("0101", a.doors)
        assertEquals(2, a.slots.size)
        assertTrue(a.slots[0].open); assertNull(a.slots[0].seal)
        assertEquals(Pos(14, 69, 0), a.slots[1].seal!!.cells[0].pos)
        assertEquals("minecraft:stone", a.slots[1].seal!!.cells[0].state)
        assertEquals(2, a.blockEntities.size); assertTrue(a.blockEntities[1].marker); assertEquals(listOf("a", "b", "", ""), a.blockEntities[1].text)
        assertEquals(0, a.blockEntities[0].tex)
        assertEquals(Pos(1, 70, 1), a.levers[0].pos); assertEquals(listOf(Pos(2, 70, 2)), a.levers[0].opens)
        assertEquals(listOf(listOf(Pos(1, 70, 1), Pos(1, 70, 2))), a.crypts)
        assertEquals(15, a.crushers[0].sweep); assertEquals(6, a.crushers[0].slab[5])
        assertEquals("east", a.tripwires[0].facing)
        assertEquals(2, a.puzzle!!.anchors["chests"]!!.size); assertEquals(listOf(Pos(15, 70, 20)), a.puzzle!!.anchors["chest"])
        assertEquals(4, a.odinSecrets)
        assertEquals(listOf("a gap"), a.gaps)
        assertEquals("c1", a.crushers[0].id)
    }

    @Test fun cachesAndDecodesVolumes() {
        val b = build()
        val pack = RoomPack.fromIndex(Fixtures.json(b.index), BytesBlobSource(b.blocks))!!
        val a = pack.design("Alpha")!!
        val v = assertNotNull(pack.volume(a))
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", v.stateAt(2, 70, 3))
        assertEquals("minecraft:stone", v.stateAt(0, 69, 0))
        assertEquals("minecraft:air", v.stateAt(0, 71, 0))
        assertEquals("minecraft:air", v.stateAt(40, 70, 0))   // outside the box
        assertEquals(962, v.nonAirCount())
        assertTrue(pack.volume(a) === v, "decoded once")
    }

    @Test fun appliesVariantPatches() {
        val b = build()
        val pack = RoomPack.fromIndex(Fixtures.json(b.index), BytesBlobSource(b.blocks))!!
        val a = pack.design("Alpha")!!
        val v = pack.variantVolume(a, a.variants[0])!!
        assertEquals("minecraft:air", v.stateAt(2, 70, 3), "the stairs were cleared")
        assertEquals("minecraft:stone", v.stateAt(0, 71, 0), "a new block was set")
        assertEquals(962 - 1 + 1, v.nonAirCount())
        // The base volume is untouched.
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", pack.volume(a)!!.stateAt(2, 70, 3))
    }

    @Test fun coresAliasesAndFamilies() {
        val b = build()
        val pack = RoomPack.fromIndex(Fixtures.json(b.index), BytesBlobSource(b.blocks))!!
        assertEquals("v1", pack.designByCore(456)!!.variant)
        assertNull(pack.designByCore(999))
        assertEquals("Silvers_Sword", pack.familyOf("Silver Sword"))
        assertEquals("Blue_Skulls", pack.familyOf("Blue Skulls"))
        assertEquals("Criss_Cross", pack.familyOf("Criss​-Cross"))
        assertEquals(listOf("Alpha"), pack.designsOfFamily("Alpha").map { it.id })
        assertEquals(listOf("Alpha"), pack.designsByName("Alpha").map { it.id })
        assertEquals("aa", pack.texture(0)!!.id)
    }

    @Test fun placementChecksTheDesignsOwnRotations() {
        val b = build()
        val pack = RoomPack.fromIndex(Fixtures.json(b.index), BytesBlobSource(b.blocks))!!
        val fairy = pack.design("Fairy")!!
        assertEquals(Rot.SOUTH, fairy.place(Rot.NORTH, listOf(Tile(1, 1))).rot)   // Fairy ignores the layout's word
        val a = pack.design("Alpha")!!
        assertEquals(Rot.WEST, a.place(Rot.WEST, listOf(Tile(2, 2))).rot)
        assertFailsWith<PlacementException> { a.place(Rot.WEST, listOf(Tile(2, 2), Tile(3, 2))) }
    }

    @Test fun aCorruptBlobMakesOnlyThatDesignUnavailable() {
        val b = build()
        val broken = b.blocks.copyOf(); broken[10] = (broken[10] + 1).toByte()
        val report = PackReport()
        val pack = RoomPack.fromIndex(Fixtures.json(b.index), BytesBlobSource(broken), report)!!
        assertNull(pack.volume(pack.design("Alpha")!!))
        assertTrue(report.warnings().any { it.contains("Alpha") && it.contains("checksum") })
        assertNotNull(pack.design("Alpha"), "the design record itself is still there")
    }

    @Test fun refusesWrongFormatAndNewerVersions() {
        val b = build(version = 2)
        val r = PackReport()
        assertNull(RoomPack.fromIndex(Fixtures.json(b.index), BytesBlobSource(b.blocks), r))
        assertTrue(r.errors().single().contains("version 2") && r.errors().single().contains("version 1"), r.errors().toString())
        val r2 = PackReport()
        assertNull(RoomPack.fromIndex(Fixtures.json(build(format = "ads-layouts").index), null, r2))
        assertTrue(r2.hasErrors())
    }

    @Test fun loadsFromAFolder(@TempDir dir: Path) {
        val b = build()
        Files.write(dir.resolve("rooms.index.json.gz"), Fixtures.gzip(b.index))
        Files.write(dir.resolve("rooms.blocks.bin"), b.blocks)
        val pack = assertNotNull(RoomPack.load(dir))
        assertNotNull(pack.volume(pack.design("Alpha")!!))
        pack.close()
    }

    @Test fun missingFilesAreMessagesNotCrashes(@TempDir dir: Path) {
        val r = PackReport()
        assertNull(RoomPack.load(dir, r))
        assertTrue(r.errors().single().contains("missing"), r.errors().toString())
        // An index without its blocks file.
        Files.write(dir.resolve("rooms.index.json.gz"), Fixtures.gzip(build().index))
        val r2 = PackReport()
        assertNull(RoomPack.load(dir, r2))
        assertTrue(r2.errors().single().contains("rooms.blocks.bin"), r2.errors().toString())
    }

    @Test fun aBlocksFileThatDoesNotMatchTheIndexIsRefused(@TempDir dir: Path) {
        val b = build(blocksCrcOk = false)
        Files.write(dir.resolve("rooms.index.json.gz"), Fixtures.gzip(b.index))
        Files.write(dir.resolve("rooms.blocks.bin"), b.blocks)
        val r = PackReport()
        assertNull(RoomPack.load(dir, r))
        assertTrue(r.errors().single().contains("checksum"), r.errors().toString())
    }

    @Test fun garbageIndexIsAMessage(@TempDir dir: Path) {
        Files.write(dir.resolve("rooms.index.json.gz"), byteArrayOf(1, 2, 3))
        val r = PackReport()
        assertNull(RoomPack.load(dir, r))
        assertTrue(r.errors().single().contains("unreadable"))
    }

    @Test fun volumeDecoderRejectsBadInput() {
        assertFailsWith<PackFormatException> { RoomBlobs.decodeVolume(Fixtures.zlib("XXXX".toByteArray())) }
        assertFailsWith<PackFormatException> { RoomBlobs.decodeVolume(byteArrayOf(1, 2, 3)) }
        // Runs that cover more cells than the box.
        val out = java.io.ByteArrayOutputStream()
        out.write("RVL1".toByteArray()); for (v in intArrayOf(2, 2, 1, 0)) { out.write(v); out.write(0) }
        Fixtures.varint(out, 1); Fixtures.varint(out, 13); out.write("minecraft:air".toByteArray())
        Fixtures.varint(out, 99); Fixtures.varint(out, 0)
        assertFailsWith<PackFormatException> { RoomBlobs.decodeVolume(Fixtures.zlib(out.toByteArray())) }
    }

    @Test fun runLengthsMayCrossRowsAndLayers() {
        val cells = IntArray(2 * 2 * 2) { if (it in 1..5) 1 else 0 }
        val v = RoomBlobs.decodeVolume(Fixtures.volume(2, 2, 2, 10, listOf("minecraft:air", "minecraft:dirt"), cells))
        assertEquals("minecraft:dirt", v.stateAt(1, 10, 0))
        assertEquals("minecraft:dirt", v.stateAt(0, 11, 0))
        assertEquals("minecraft:air", v.stateAt(0, 11, 1))
        assertEquals(5, v.nonAirCount())
    }

    @Test fun volumeRejectsAPatchOfADifferentBox() {
        val v = RoomVolume(2, 2, 2, 0, listOf("minecraft:air"), ShortArray(8))
        val p = RoomBlobs.decodePatch(Fixtures.patch(3, 2, 2, 0, listOf("minecraft:air"), emptyList()))
        assertFailsWith<PackFormatException> { v.withPatch(p) }
        assertFalse(v.contains(2, 0, 0))
    }
}
