package com.engineerclient.recorder

import com.engineerclient.splits.Scorecard
import com.engineerclient.splits.SplitTracker
import com.engineerclient.splits.Stamp
import com.engineerclient.splits.SubSplitTracker
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** EC's recorder lines: the member builder, and the splits classes writing through a live session. */
class EcRecTest {

    private val root: Path = Files.createTempDirectory("ecrec-ec")

    @AfterTest
    fun cleanup() {
        Rec.end()?.abandon()
        RecorderFiles.deleteRecursively(root)
    }

    private fun obj(members: String): JsonObject = JsonParser.parseString("{$members}").asJsonObject

    @Test
    fun `members are valid JSON with the recorder's number rules`() {
        val o = EcRec.Obj().str("s", "a\"b").num("i", 3).num("d", 0.1).num("nan", Double.NaN).num("big", 1L shl 60)
            .bool("b", null).at(Stamp(1000, 20)).stamp("none", null).strs("l", listOf("x", null)).nums("n", listOf(1, 2.5))
            .obj("o") { it.num("k", 1) }.objs("os", listOf(1, 2)) { m, v -> m.num("v", v) }
        val j = obj(o.toString())
        assertEquals("a\"b", j["s"].asString)
        assertEquals(0.1, j["d"].asDouble)
        assertEquals("NaN", j["nan"].asString)
        assertEquals((1L shl 60).toString(), j["big"].asString)
        assertTrue(j["b"].isJsonNull && j["none"].isJsonNull)
        assertEquals(1000L, j["at"].asJsonObject["ms"].asLong)
        assertEquals(20, j["at"].asJsonObject["tick"].asInt)
        assertEquals(2, j["os"].asJsonArray[1].asJsonObject["v"].asInt)
        assertEquals("", EcRec.Obj().toString())
    }

    @Test
    fun `nothing is built when not recording`() {
        var built = false
        EcRec.line("ec.test") { built = true }
        EcRec.changed("ec.test", "k", { built = true })
        assertTrue(!built)
    }

    @Test
    fun `splits, sub splits and the scorecard write their moments with EC's stamp`() {
        val s = RecorderSession(root, notify = {})
        Rec.begin(s)
        val lines = mutableListOf<String>()
        val tracker = SplitTracker()
        val subs = SubSplitTracker()
        val card = Scorecard()
        tracker.onChat(SplitTracker.MORT, Stamp(1000, 10))
        subs.onChat("[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", Stamp(2000, 30))
        card.onChat("1/2 Energy Crystals are now active!", Stamp(3000, 50))
        // A failing body becomes an error line, never an exception at the call site.
        EcRec.line("ec.test") { throw IllegalStateException("boom") }
        s.confirm("F7")
        Rec.end(s)
        assertTrue(s.closeAndWait(10_000))
        val dir = Files.list(root).use { it.filter { p -> Files.isDirectory(p) }.toList() }.single()
        val text = java.util.zip.GZIPInputStream(Files.newInputStream(dir.resolve("part0001.jsonl.gz"))).readBytes().toString(Charsets.UTF_8)
        text.lineSequence().filter { it.isNotBlank() }.forEach { lines += it }
        val byKind = lines.map { JsonParser.parseString(it).asJsonObject }.groupBy { it["k"].asString }

        val split = byKind.getValue("ec.split").single()
        assertEquals(0, split["idx"].asInt)
        assertEquals(10, split["at"].asJsonObject["tick"].asInt)

        val sub = byKind.getValue("ec.sub").single()
        assertEquals("maxor.crystals", sub["id"].asString)
        assertEquals(-1, sub["current"].asInt)
        assertEquals(2000L, sub["at"].asJsonObject["ms"].asLong)

        val c = byKind.getValue("ec.card").single()
        assertEquals("crystal active 1", c["what"].asString)
        assertEquals(1, c["crystals"].asJsonArray.size())

        assertTrue(byKind.getValue("error").any { it["p"].asString == "ec.test" })
    }
}
