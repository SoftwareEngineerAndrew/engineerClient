package com.engineerclient.betterpf

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * What a saved run (.jsonl.gz, as written while recording) is turned into to upload:
 *
 *  - xz (LZMA2) instead of gzip: about half the size (2.95 MB -> 1.43 MB on a 7-minute run).
 *    The site stores it as it comes; the viewer tells the two apart by their first bytes.
 *
 *  - When someone else in the party already uploaded their recording of the same run ([sibling]),
 *    the mobs that recording has at least as much of are left out. The viewer takes each mob
 *    (entity id - the server's, the same on every client) from the one recording with the most
 *    data for it, never mixing two, so this changes nothing it shows: only mobs this recording
 *    saw more of are kept. Players are always kept (the viewer fills gaps in one recording's view
 *    of a player from another's).
 *
 * Runs on an upload thread, never the game's. The local file is left as it is.
 */
object UploadPacker {

    /** Line kinds that are about one mob, by its "id" (the "e" lines hold many, in "d"). */
    private val ENTITY_KINDS = setOf("spawn", "gone", "name", "stand", "frame")
    private val KIND = Regex(""""k":"(\w+)"""")

    /** The run as an xz temp file to send, and how many mobs were left out as already uploaded. */
    fun pack(file: Path, sibling: ByteArray?): Pair<Path, Int> {
        val drop = sibling?.let { dropped(file, it) } ?: emptySet()
        val out = Files.createTempFile("betterpf-upload-", ".jsonl.xz")
        BufferedWriter(OutputStreamWriter(XZOutputStream(Files.newOutputStream(out), LZMA2Options(6)), Charsets.UTF_8), 1 shl 16).use { w ->
            lines(Files.newInputStream(file)).use { r ->
                for (line in r.lineSequence()) {
                    val kept = if (drop.isEmpty()) line else keep(line, drop) ?: continue
                    w.write(kept); w.newLine()
                }
            }
        }
        return out to drop.size
    }

    /** A line with the dropped mobs taken out, or null if nothing of it is left. */
    private fun keep(line: String, drop: Set<Int>): String? {
        val kind = KIND.find(line.take(24))?.groupValues?.get(1) ?: return line
        if (kind == "e") {
            val l = JsonParser.parseString(line).asJsonObject
            val d = l.getAsJsonArray("d")
            val left = JsonArray()
            for (e in d) if (e.asJsonArray[0].asInt !in drop) left.add(e)
            if (left.size() == 0) return null
            if (left.size() == d.size()) return line
            l.add("d", left)
            return l.toString()
        }
        if (kind in ENTITY_KINDS || (kind == "eq" && line.contains("\"id\":"))) {
            val id = JsonParser.parseString(line).asJsonObject["id"]?.asInt ?: return line
            return if (id in drop) null else line
        }
        return line
    }

    /** Mobs the sibling recording has as many events for as this one (or more): left out. */
    private fun dropped(file: Path, sibling: ByteArray): Set<Int> {
        val mine = counts(Files.newInputStream(file))
        val theirs = counts(sibling.inputStream())
        return mine.filter { (id, n) -> (theirs[id] ?: 0) >= n }.keys
    }

    /** How many events each mob has in a recording (the viewer's measure of which saw it best). */
    private fun counts(input: InputStream): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        lines(input).use { r ->
            for (line in r.lineSequence()) {
                val kind = KIND.find(line.take(24))?.groupValues?.get(1) ?: continue
                if (kind == "e") {
                    for (e in JsonParser.parseString(line).asJsonObject.getAsJsonArray("d")) out.merge(e.asJsonArray[0].asInt, 1, Int::plus)
                } else if (kind in ENTITY_KINDS || (kind == "eq" && line.contains("\"id\":"))) {
                    JsonParser.parseString(line).asJsonObject["id"]?.asInt?.let { out.merge(it, 1, Int::plus) }
                }
            }
        }
        return out
    }

    /** A recording's lines, gzip or xz (told apart by their first bytes, as the site and viewer do). */
    private fun lines(raw: InputStream): BufferedReader {
        val input = BufferedInputStream(raw, 1 shl 16)
        input.mark(6)
        val head = ByteArray(6).also { input.read(it) }
        input.reset()
        val xz = head[0] == 0xFD.toByte() && head[1] == '7'.code.toByte() && head[2] == 'z'.code.toByte()
        return BufferedReader(InputStreamReader(if (xz) XZInputStream(input) else GZIPInputStream(input), Charsets.UTF_8), 1 shl 16)
    }
}
