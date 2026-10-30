package com.devgineerclient.betterrecorder

import com.devgineerclient.recorder.RecConfig
import com.devgineerclient.recorder.RecorderFiles
import com.devgineerclient.recorder.RecorderSession
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.io.path.name
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrecWriterTest {
    /** Raw frames written through the session come back byte for byte, in order, with withheld ones as length only; lines land in the jsonl. */
    @Test fun rawFramesReadBack() {
        val root = Files.createTempDirectory("brec-test")
        val s = RecorderSession(root, "\"brec\":3,\"adr\":\"2026-10-29_12-00-00\"", { RecConfig(minFreeGb = 0.0) }) {}
        s.confirm("brec")
        val rnd = Random(7)
        val sent = (1..5000).map { i -> Triple(1000L + i, i % 2, ByteArray(rnd.nextInt(0, 3000)).also { rnd.nextBytes(it) }) }
        for ((seq, dir, bytes) in sent) {
            if (seq == 1500L) s.raw(seq, dir, 4, bytes, true, bytes.size) else s.raw(seq, dir, 4, bytes, false)
            if (seq % 20 == 0L) s.line(seq, "tk", System.currentTimeMillis(), RecorderFiles.envelope("tk", seq, 1, 2, 0, 0) + ",\"dt\":50000}", t = 1, n = 2)
        }
        assertTrue(s.closeAndWait(30_000))
        val dir = Files.list(root).use { st -> st.filter { it.name.contains("_brec_") }.toList() }.single()
        val back = BrecRaw.read(dir)
        assertEquals(sent.size, back.size)
        for ((i, r) in back.withIndex()) {
            val (seq, d, bytes) = sent[i]
            assertEquals(seq, r.seq); assertEquals(d, r.dir); assertEquals(4, r.phase); assertEquals(bytes.size, r.len)
            if (seq == 1500L) { assertTrue(r.withheld); assertNull(r.bytes) } else assertContentEquals(bytes, r.bytes)
        }
        val text = Files.list(dir).use { st -> st.filter { it.name.endsWith(".jsonl.gz") }.sorted().toList() }
            .joinToString("") { p -> GZIPInputStream(Files.newInputStream(p)).use { it.readBytes().toString(Charsets.UTF_8) } }
        assertTrue(text.contains("\"adr\":\"2026-10-29_12-00-00\""))
        assertEquals(sent.size / 20, Regex("\"k\":\"tk\"").findAll(text).count())
        root.toFile().deleteRecursively()
    }
}
