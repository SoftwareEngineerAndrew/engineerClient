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
        assertFalse(Files.exists(d.resolve(".lock")), "the owner's lock goes with it")

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

        val report = RecorderFiles.recover(root, idleMs = 0)
        assertTrue(report.isNotEmpty())
        assertFalse(Files.exists(d.resolve("part0001.jsonl.gz.part")))
        assertContentEquals(m1, Files.readAllBytes(d.resolve("part0001.jsonl.gz")))
        val manifest = json(Files.readString(d.resolve("manifest.json")))
        assertTrue(manifest["crashed"].asBoolean)
        assertEquals(1, manifest["lostAfterSeq"].asLong)
        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(fresh))
    }

    @Test
    fun `a stream not written since the confirm still loses its part suffix`() {
        val s = RecorderSession(root, notify = {})
        Rec.begin(s)
        // A raw frame written before the confirm, then nothing more in the raw sidecar.
        Rec.raw(Rec.nextSeq(), 0, 4, byteArrayOf(1, 2, 3), null)
        Rec.emit("hello", "")
        Thread.sleep(1500) // the member holding the raw frame goes to disk before the confirm
        s.confirm("F7")
        Rec.emit("after", "")
        Rec.end(s)
        assertTrue(s.closeAndWait(10_000))
        val d = only(root) { Files.isDirectory(it) }.single()
        assertTrue(only(d) { it.name.endsWith(".part") || it.name == ".lock" }.isEmpty(), only(d) { true }.toString())
        assertTrue(Files.exists(d.resolve("part0001.raw.gz")))
        val manifest = json(Files.readString(d.resolve("manifest.json")))
        assertTrue(manifest["complete"].asBoolean)
        assertEquals(d.name, manifest["finalName"].asString)
        // Recovery at the next start leaves it alone.
        RecorderFiles.recover(root, idleMs = 0)
        assertFalse(json(Files.readString(d.resolve("manifest.json"))).has("lostAfterSeq"))
    }

    @Test
    fun `a failed build is counted as an error line`() {
        val s = RecorderSession(root, notify = {})
        Rec.begin(s)
        Rec.emitLazy("boom", 10, "minecraft:x") { error("bad") }
        s.confirm("F7")
        Rec.end(s)
        assertTrue(s.closeAndWait(10_000))
        val d = only(root) { Files.isDirectory(it) }.single()
        val manifest = json(Files.readString(d.resolve("manifest.json")))
        assertEquals(1, manifest["counts"].asJsonObject["error"].asInt)
        assertFalse(manifest["counts"].asJsonObject.has("minecraft:x"))
        assertEquals(1, manifest["errors"].asJsonObject["minecraft:x"].asInt)
        val idx = Files.readAllLines(d.resolve("part0001.idx.jsonl")).map(::json)
        assertTrue(idx.any { it["types"].asJsonObject.has("error") })
    }

    @Test
    fun `a folder that cannot be made stops the session instead of buffering`() {
        val notes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val file = root.resolve("not-a-folder")
        Files.writeString(file, "x")
        val s = RecorderSession(file, notify = { notes += it })
        Rec.begin(s)
        val deadline = System.currentTimeMillis() + 10_000
        while (s.running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(s.running, "a dead IO thread must not leave the session running")
        assertEquals("io_error", s.stoppedReason)
        assertTrue(notes.any { it.contains("could not write") }, notes.toString())
        assertFalse(s.line(Rec.nextSeq(), "x", 0, "{}"))
        Rec.end(s)
        assertTrue(s.closeAndWait(10_000))
    }

    @Test
    fun `the queue caps scale with the heap`() {
        assertEquals(RecorderSession.QUEUE_BYTES, RecorderSession.queueCapFor(64L shl 30))
        assertEquals(256L shl 20, RecorderSession.queueCapFor(2L shl 30))
        assertEquals(128L shl 20, RecorderSession.ioCapFor(2L shl 30))
    }

    @Test
    fun `gap history merges an overload into one entry and stays capped`() {
        val h = GapHistory()
        for (i in 0 until 5000) h.add("queue_full", i.toLong(), i.toLong(), 1000L + i, 1000L + i, 1, mapOf("in" to 1L))
        assertEquals(1, h.entries.size)
        assertEquals(5000, h.entries.single().lines)
        assertEquals(4999, h.entries.single().seqB)
        // Episodes far apart stay apart; past the cap the oldest fold into totals.
        for (i in 0 until GapHistory.CAP + 10) h.add("after_close", i.toLong(), i.toLong(), 100_000L * (i + 1), 100_000L * (i + 1), 2, emptyMap())
        assertEquals(GapHistory.CAP, h.entries.size)
        val m = JsonObject()
        h.toJson(m)
        val of = m["gapsOverflow"].asJsonObject
        val folded = of.entrySet().sumOf { it.value.asJsonObject["lines"].asLong }
        assertEquals(5000L + 2 * (GapHistory.CAP + 10), folded + m["gaps"].asJsonArray.sumOf { it.asJsonObject["lines"].asLong })
    }

    @Test
    fun `the folder cap only deletes finished recordings`() {
        fun rec(name: String, manifest: String?, part: Boolean = false): Path {
            val d = Files.createDirectories(root.resolve(name))
            Files.write(d.resolve("part0001.jsonl.gz"), ByteArray(1000))
            if (manifest != null) Files.writeString(d.resolve("manifest.json"), manifest)
            if (part) Files.write(d.resolve("part0002.jsonl.gz.part"), ByteArray(10))
            return d
        }
        val done = "{\"format\":\"recorder-2\",\"closed\":true}"
        val old = rec("2020-01-01_00-00-00_F7_aaaaaa", done)
        val mine = Files.createDirectories(root.resolve("0-keep")).also { Files.write(it.resolve("x"), ByteArray(1000)) }
        val live = rec("2020-01-02_00-00-00_F7_bbbbbb", done)
        val writing = rec("2020-01-03_00-00-00_F7_cccccc", "{\"format\":\"recorder-2\",\"closed\":false}", part = true)
        val freed = RecorderFiles.deleteOldest(root, setOf(live), Long.MAX_VALUE)
        assertTrue(freed > 0)
        assertFalse(Files.exists(old))
        assertTrue(Files.exists(mine) && Files.exists(live) && Files.exists(writing))
    }

    @Test
    fun `recovery leaves a recording another process holds alone`() {
        val d = Files.createDirectories(root.resolve("2026-01-01_00-00-00_F7_abcdef"))
        Files.write(d.resolve("part0001.jsonl.gz.part"), gz("{\"k\":\"a\",\"seq\":1}\n"))
        Files.writeString(d.resolve("manifest.json"), "{\"format\":\"recorder-2\",\"complete\":false}")
        val lock = RecorderFiles.lockDir(d, create = true)!!
        try {
            val report = RecorderFiles.recover(root, idleMs = 0)
            assertTrue(report.any { it.startsWith("in use") }, report.toString())
            assertTrue(Files.exists(d.resolve("part0001.jsonl.gz.part")))
            // And one written to a moment ago, even without a lock.
            lock.release(delete = true)
            val report2 = RecorderFiles.recover(root)
            assertTrue(report2.any { it.startsWith("skipped recent") }, report2.toString())
            assertTrue(Files.exists(d.resolve("part0001.jsonl.gz.part")))
        } finally { lock.release() }
    }

    @Test
    fun `a confirmed pending directory gets a usable name`() {
        val d = root.resolve(".pending-2026-01-01_00-00-00_abcdef")
        assertEquals("good", RecorderFiles.usableName(d, json("{\"finalName\":\"good\"}")))
        assertEquals("2026-01-01_00-00-00_F7_abcdef", RecorderFiles.usableName(d, json("{\"finalName\":\".pending-2026-01-01_00-00-00_abcdef\",\"label\":\"F7\"}")))
        assertEquals("2026-01-01_00-00-00_rec_abcdef", RecorderFiles.usableName(d, json("{}")))
    }

    private fun gz(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(s.toByteArray()) }
        return out.toByteArray()
    }
}
