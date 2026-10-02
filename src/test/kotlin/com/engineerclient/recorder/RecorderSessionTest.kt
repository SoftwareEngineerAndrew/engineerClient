package com.engineerclient.recorder

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.GZIPInputStream
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The writer core end to end, on a temp directory: members, index, sidecars, manifest, recovery. */
class RecorderSessionTest {

    private val root: Path = Files.createTempDirectory("ecrec")

    @AfterTest
    fun cleanup() {
        Rec.end()
        RecorderFiles.deleteRecursively(root)
    }

    private fun gunzip(b: ByteArray) = GZIPInputStream(ByteArrayInputStream(b)).readBytes().toString(Charsets.UTF_8)
    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject
    private fun only(dir: Path, pred: (Path) -> Boolean) = Files.list(dir).use { it.filter(pred).toList() }

    @Test
    fun `the envelope comes first on every line`() {
        val e = RecorderFiles.envelope("in", 7, 3, 2, 1000, 55) + "}"
        assertEquals("""{"k":"in","seq":7,"t":3,"n":2,"ms":1000,"ns":55}""", e)
        assertEquals(listOf("k", "seq", "t", "n", "ms", "ns"), json(e).keySet().toList())
        assertEquals("\"a\":1", RecorderFiles.members(" {\"a\":1} "))
        assertEquals("\"a\":1", RecorderFiles.members("\"a\":1"))
    }

    @Test
    fun `names are padded and labels safe`() {
        assertEquals("part0007.jsonl.gz", RecorderFiles.partName(7, "jsonl.gz"))
        assertEquals("F7", RecorderFiles.sanitizeLabel("F7"))
        assertEquals("rec", RecorderFiles.sanitizeLabel("  /\\ "))
        assertEquals("CatacombsEntrance", RecorderFiles.sanitizeLabel("Catacombs: Entrance"))
        assertTrue(RecorderFiles.newHexId().matches(Regex("[0-9a-f]{6}")))
    }

    @Test
    fun `raw records are length, varint seq, dir, phase, flags, bytes`() {
        val out = ByteArrayOutputStream()
        RecorderFiles.rawRecord(out, 300, 1, 2, byteArrayOf(9, 8, 7), false)
        RecorderFiles.rawRecord(out, 5, 0, 3, null, true, originalLen = 40)
        val b = out.toByteArray()
        assertContentEquals(byteArrayOf(0, 0, 0, 3, 0xAC.toByte(), 0x02, 1, 2, 0, 9, 8, 7, 0, 0, 0, 40, 5, 0, 3, 1), b)
    }

    @Test
    fun `changed and private chat`() {
        Rec.clearChanged()
        assertTrue(Rec.changed("x", "1"))
        assertFalse(Rec.changed("x", "1"))
        assertTrue(Rec.changed("x", "2"))
        assertTrue(Rec.privateText("§dFrom §7[MVP+] Someone§7: hi"))
        assertTrue(Rec.privateText("Guild > [VIP] a: b"))
        assertFalse(Rec.privateText("[BOSS] Maxor: hi"))
    }

    @Test
    fun `a confirmed session writes indexed members, sidecars and a complete manifest`() {
        val s = RecorderSession(root, "\"mod\":\"test\",\"self\":\"me\"", notify = {})
        Rec.begin(s)
        var kfSeen = ""
        Rec.onKeyframe("test") { reason -> kfSeen = reason; Rec.emit("kftest", "\"kf\":${Rec.keyframeId}") }
        Rec.emit("hello", "\"x\":1")
        Rec.emit("empty", "")
        Rec.emitLazy("lazy", 10, "lazy") { "\"y\":2" }
        Rec.emitLazy("boom", 10, "boom") { error("bad") }
        val seq = Rec.nextSeq()
        Rec.raw(seq, 0, 3, byteArrayOf(1, 2, 3), null)
        Rec.raw(Rec.nextSeq(), 1, 3, byteArrayOf(4, 5), "typed_chat")
        Rec.emitLine(seq, 10, "minecraft:test", System.currentTimeMillis()) { Rec.envelope("in", seq) + ",\"p\":\"minecraft:test\",\"f\":{}}" }
        Rec.index("entities", "{\"id\":5}")
        Rec.mark("note")
        Rec.requestKeyframe("confirm")
        Rec.onTickEnd()
        // The part's own request (sent when the writer opened part 1) may have got there first.
        assertTrue(kfSeen == "confirm" || kfSeen == "part", kfSeen)
        s.confirm("F7")
        Rec.end(s)
        assertTrue(s.closeAndWait(10_000))
        assertFalse(s.running)

        val dirs = only(root) { Files.isDirectory(it) }
        assertEquals(1, dirs.size)
        val d = dirs[0]
        assertTrue(d.name.matches(Regex("""\d{4}-\d\d-\d\d_\d\d-\d\d-\d\d_F7_[0-9a-f]{6}""")), d.name)
        assertTrue(only(d) { it.name.endsWith(".part") }.isEmpty())

        // Every indexed member decompresses on its own and holds exactly its lines.
        val data = Files.readAllBytes(d.resolve("part0001.jsonl.gz"))
        val idx = Files.readAllLines(d.resolve("part0001.idx.jsonl")).map(::json)
        val lines = ArrayList<JsonObject>()
        var end = 0L
        for (m in idx) {
            val off = m["off"].asInt; val len = m["len"].asInt
            if (len == 0) continue
            val text = gunzip(data.copyOfRange(off, off + len))
            val ls = text.trimEnd('\n').split('\n').map(::json)
            assertEquals(m["lines"].asInt, ls.size)
            assertEquals(m["raw"].asInt, text.toByteArray().size)
            lines += ls
            end = maxOf(end, (off + len).toLong())
        }
        assertEquals(data.size.toLong(), end)
        for (l in lines) assertEquals(listOf("k", "seq", "t", "n", "ms", "ns"), l.keySet().take(6), l.toString())
        assertEquals(lines.size, lines.map { it["seq"].asLong }.toSet().size, "seq is unique")
        val kinds = lines.map { it["k"].asString }
        assertEquals("meta", kinds.first())
        val meta = lines.first()
        assertEquals("recorder-2", meta["format"].asString)
        assertEquals(1, meta["part"].asInt)
        assertEquals("test", meta["mod"].asString)
        for (k in listOf("hello", "empty", "lazy", "error", "in", "mark", "keyframe", "kftest")) assertTrue(k in kinds, k)
        assertEquals("boom", lines.first { it["k"].asString == "error" }["p"].asString)
        val kf = lines.first { it["k"].asString == "keyframe" }
        assertEquals(kfSeen, kf["reason"].asString)
        assertTrue(idx.any { !it["kf"].isJsonNull && it["kf"].asLong == kf["kf"].asLong })

        // Raw sidecar: the two records, the withheld one with only its length.
        val raw = DataInputStream(GZIPInputStream(Files.newInputStream(d.resolve("part0001.raw.gz"))))
        assertEquals(3, raw.readInt()); raw.readUnsignedByte(); assertEquals(0, raw.readUnsignedByte()); assertEquals(3, raw.readUnsignedByte())
        assertEquals(0, raw.readUnsignedByte()); assertContentEquals(byteArrayOf(1, 2, 3), raw.readNBytes(3))
        assertEquals(2, raw.readInt()); raw.readUnsignedByte(); assertEquals(1, raw.readUnsignedByte()); raw.readUnsignedByte()
        assertEquals(1, raw.readUnsignedByte()); assertEquals(-1, raw.read())

        val ents = Files.readAllLines(d.resolve("entities.jsonl")).map(::json)
        assertEquals(5, ents.single()["id"].asInt)

        val manifest = json(Files.readString(d.resolve("manifest.json")))
        assertTrue(manifest["complete"].asBoolean)
        assertFalse(manifest["crashed"].asBoolean)
        assertEquals("me", manifest["self"].asString)
        assertEquals(1, manifest["parts"].asJsonArray.size())
        assertEquals("note", manifest["marks"].asJsonArray[0].asJsonObject["note"].asString)
        assertEquals(1, manifest["errors"].asJsonObject["boom"].asInt)
        assertNotNull(manifest["counts"].asJsonObject["hello"])
        assertTrue(Files.exists(d.resolve("schema.json")))
    }

    @Test
    fun `entity loads and the big moments also go to the side indexes`() {
        val s = RecorderSession(root, notify = {})
        Rec.begin(s)
        Rec.emit("espawn", "\"id\":7,\"type\":\"minecraft:zombie\",\"at\":\"load\"")
        Rec.emit("espawn", "\"id\":7,\"type\":\"minecraft:zombie\",\"at\":\"tick\"")
        Rec.emit("world", "\"via\":\"login\"")
        Rec.emit("end", "")
        Rec.emit("hello", "\"x\":1")
        Rec.mark("here")
        s.confirm("F7")
        Rec.end(s)
        assertTrue(s.closeAndWait(10_000))
        val d = only(root) { Files.isDirectory(it) }.single()
        val ents = Files.readAllLines(d.resolve("entities.jsonl")).map(::json)
        assertEquals(1, ents.size, "only the load, not the tick-end resnap")
        assertEquals(7, ents[0]["id"].asInt)
        assertTrue(ents[0]["of"].asLong > 0)
        val events = Files.readAllLines(d.resolve("events.jsonl")).map(::json)
        assertEquals(listOf("world", "end", "mark"), events.map { it["kind"].asString })
        assertEquals("here", events.last()["note"].asString)
        for (e in events) assertEquals(listOf("k", "seq", "t", "n", "ms", "ns"), e.keySet().take(6), e.toString())
    }

    @Test
    fun `an abandoned session leaves nothing behind`() {
        val s = RecorderSession(root, notify = {})
        Rec.begin(s)
        Rec.emit("hello", "")
        Rec.end(s)
        s.abandon()
        assertTrue(s.closeAndWait(10_000))
        assertTrue(only(root) { true }.isEmpty())
        assertFalse(s.line(1, "x", 0, "{}"), "nothing is accepted after closing")
    }

    @Test
    fun `compact entity rows go to an xz stream`() {
        val s = RecorderSession(root, config = { RecConfig(compactEntities = true) }, notify = {})
        for (i in 0 until 50) s.line(Rec.nextSeq(), "ent", 0, "{\"k\":\"ent\",\"i\":$i}")
        s.line(Rec.nextSeq(), "other", 0, "{\"k\":\"other\"}")
        s.confirm("x")
        assertTrue(s.closeAndWait(10_000))
        val d = only(root) { Files.isDirectory(it) }.single()
        val ent = XZInputStream(Files.newInputStream(d.resolve("part0001.ent.xz"))).readBytes().toString(Charsets.UTF_8).trimEnd().split('\n')
        assertEquals(50, ent.size)
        val main = gunzip(Files.readAllBytes(d.resolve("part0001.jsonl.gz")))
        assertFalse(main.contains("\"ent\""))
        assertTrue(main.contains("\"other\""))
    }

    @Test
    fun `recovery cuts a part back to its last indexed member`() {
        val d = Files.createDirectories(root.resolve("2026-01-01_00-00-00_F7_abcdef"))
        val m1 = gz("{\"k\":\"a\",\"seq\":1}\n")
        val torn = gz("{\"k\":\"b\",\"seq\":2}\n").copyOf(10)
        Files.write(d.resolve("part0001.jsonl.gz.part"), m1 + torn)
        Files.write(d.resolve("part0001.idx.jsonl"), listOf("{\"off\":0,\"len\":${m1.size},\"raw\":17,\"lines\":1,\"seq\":[1,1]}", "{\"off\":${m1.size},\"le"))
        Files.writeString(d.resolve("manifest.json"), "{\"format\":\"recorder-2\",\"complete\":false}")
        val stale = Files.createDirectories(root.resolve(".pending-old"))
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 2 * 3600_000L))
        val fresh = Files.createDirectories(root.resolve(".pending-new"))

        val report = RecorderFiles.recover(root)
        assertTrue(report.isNotEmpty())
        assertFalse(Files.exists(d.resolve("part0001.jsonl.gz.part")))
        assertContentEquals(m1, Files.readAllBytes(d.resolve("part0001.jsonl.gz")))
        val manifest = json(Files.readString(d.resolve("manifest.json")))
        assertTrue(manifest["crashed"].asBoolean)
        assertEquals(1, manifest["lostAfterSeq"].asLong)
        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(fresh))
    }

    private fun gz(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(s.toByteArray()) }
        return out.toByteArray()
    }
}
