package com.engineerclient.recorder

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.name

/**
 * The on-disk side of a recording that is not the writing itself: names, the envelope every line
 * starts with, the raw sidecar's record layout, schema.json, atomic small-file writes, the disk
 * guard's folder sums and the startup recovery of recordings a crash left half-written.
 *
 * Everything here is free of Minecraft and Odin so it can be unit tested headlessly.
 */
object RecorderFiles {

    const val FORMAT = "recorder-2"
    const val GIB = 1024L * 1024 * 1024
    val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    // ------------------------------------------------------------------ names

    /** Six hex digits, so two recordings started in the same second never share a directory. */
    fun newHexId(): String = HexFormat.of().formatHex(ByteArray(3).also { ThreadLocalRandom.current().nextBytes(it) })

    /** What a label may look like in a directory name: letters and digits only, never empty. */
    fun sanitizeLabel(s: String?): String = (s ?: "").replace(Regex("[^A-Za-z0-9]+"), "").take(40).ifEmpty { "rec" }

    fun pendingName(id: String) = ".pending-$id"
    fun finalName(stamp: String, label: String, hex: String) = "${stamp}_${sanitizeLabel(label)}_$hex"

    /** `part0001.jsonl.gz`: zero-padded so the parts sort by name. */
    fun partName(part: Int, ext: String) = "part%04d.%s".format(part, ext)

    /** The one directory currently being written, or null (for side files such as thumbnails). */
    fun currentRecordingDir(): Path? = Rec.session?.dir

    // ------------------------------------------------------------------ the line contract

    /**
     * The start of every line, without its closing brace: kind, global sequence number, client
     * tick, server-ping count, wall clock and monotonic nanoseconds since the session started.
     */
    fun envelope(kind: String, seq: Long, t: Int, n: Int, ms: Long, ns: Long): String =
        StringBuilder(64 + kind.length).append("{\"k\":").append(q(kind)).append(",\"seq\":").append(seq)
            .append(",\"t\":").append(t).append(",\"n\":").append(n).append(",\"ms\":").append(ms).append(",\"ns\":").append(ns).toString()

    /** A JSON string literal. */
    fun q(s: String?): String = if (s == null) "null" else JsonPrimitive(s).toString()

    /** [members] may be a whole object (`{...}`) or bare members; returns bare members. */
    fun members(json: String): String {
        val t = json.trim()
        return if (t.startsWith("{") && t.endsWith("}")) t.substring(1, t.length - 1).trim() else t
    }

    // ------------------------------------------------------------------ raw sidecar

    /**
     * One raw-sidecar record: `[u32 len][varint seq][u8 dir][u8 phase][u8 flags][bytes]`. len is
     * the payload's original length; with flag bit0 (withheld) the payload is left out and only
     * its length says it was there.
     */
    fun rawRecord(out: ByteArrayOutputStream, seq: Long, dir: Int, phase: Int, bytes: ByteArray?, withheld: Boolean, originalLen: Int = bytes?.size ?: 0) {
        val len = if (withheld) originalLen else (bytes?.size ?: 0)
        out.write(len ushr 24); out.write(len ushr 16); out.write(len ushr 8); out.write(len)
        varLong(out, seq)
        out.write(dir); out.write(phase); out.write(if (withheld) 1 else 0)
        if (!withheld && bytes != null) out.write(bytes, 0, bytes.size)
    }

    fun varLong(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }

    // ------------------------------------------------------------------ schema.json

    private val classes = ConcurrentHashMap<String, List<String>>()
    private val enums = ConcurrentHashMap<String, List<String>>()
    private val schemaVersion = AtomicInteger()

    /** Called the first time PacketJson reflects a class: its field names in written order. */
    fun noteClass(cls: Class<*>, fieldNames: List<String>) {
        if (classes.putIfAbsent(cls.name, fieldNames.toList()) == null) schemaVersion.incrementAndGet()
    }

    /** An enum's constants in ordinal order, so ordinals seen in raw data can be named. */
    fun noteEnum(cls: Class<*>) {
        val constants = runCatching { cls.enumConstants }.getOrNull() ?: return
        if (enums.putIfAbsent(cls.name, constants.map { (it as Enum<*>).name }) == null) schemaVersion.incrementAndGet()
    }

    fun schemaVersion() = schemaVersion.get()

    fun schemaJson(): String {
        val o = JsonObject()
        o.addProperty("format", FORMAT)
        o.add("classes", JsonObject().also { c -> classes.toSortedMap().forEach { (k, v) -> c.add(k, com.google.gson.JsonArray().also { a -> v.forEach(a::add) }) } })
        o.add("enums", JsonObject().also { c -> enums.toSortedMap().forEach { (k, v) -> c.add(k, com.google.gson.JsonArray().also { a -> v.forEach(a::add) }) } })
        return GsonBuilder().setPrettyPrinting().create().toJson(o)
    }

    // ------------------------------------------------------------------ small files

    /** Writes [text] to a .tmp beside [path] and moves it over: a reader never sees half a file. */
    fun writeAtomically(path: Path, text: String) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(tmp, text.toByteArray(Charsets.UTF_8))
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun deleteRecursively(p: Path) {
        if (!Files.exists(p)) return
        Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
    }

    fun folderSize(p: Path): Long {
        if (!Files.isDirectory(p)) return 0
        var total = 0L
        Files.walk(p).use { s -> s.forEach { f -> if (Files.isRegularFile(f)) total += runCatching { Files.size(f) }.getOrDefault(0L) } }
        return total
    }

    /**
     * Deletes the oldest finished recordings under [root] until [bytesToFree] are gone. Never
     * touches [keep] (the recording being written) or any pending directory. Returns bytes freed.
     */
    fun deleteOldest(root: Path, keep: Path?, bytesToFree: Long): Long {
        if (!Files.isDirectory(root) || bytesToFree <= 0) return 0
        val dirs = Files.list(root).use { s -> s.filter { Files.isDirectory(it) && !it.name.startsWith(".") && it != keep }.toList() }
            .sortedBy { it.name } // names start with the start time
        var freed = 0L
        for (d in dirs) {
            if (freed >= bytesToFree) break
            val size = folderSize(d)
            deleteRecursively(d)
            freed += size
        }
        return freed
    }

    // ------------------------------------------------------------------ crash recovery

    /**
     * Run once at startup, before any recording opens. A recording the game did not get to close
     * still has `.part` files; each is cut back to the end of the last member its index lists (a
     * member the index names was written whole before its index line), renamed to its final name,
     * and the manifest is marked crashed with the last sequence number known to be on disk.
     * Pending directories (never confirmed) older than an hour are deleted; a pending directory
     * whose manifest says it was confirmed (its rename failed) is renamed instead.
     */
    fun recover(root: Path): List<String> {
        val report = ArrayList<String>()
        if (!Files.isDirectory(root)) return report
        val dirs = Files.list(root).use { s -> s.filter { Files.isDirectory(it) }.toList() }
        for (d0 in dirs) {
            try {
                var d = d0
                if (d.name.startsWith(".pending-")) {
                    val manifest = readManifest(d)
                    val confirmedName = manifest?.get("finalName")?.takeIf { it.isJsonPrimitive }?.asString
                    if (manifest?.get("confirmed")?.takeIf { it.isJsonPrimitive }?.asBoolean == true && confirmedName != null) {
                        val target = d.resolveSibling(confirmedName)
                        if (!Files.exists(target)) { Files.move(d, target); d = target; report += "renamed ${d0.name} -> $confirmedName" }
                    } else {
                        val age = System.currentTimeMillis() - Files.getLastModifiedTime(d).toMillis()
                        if (age > 60 * 60 * 1000L) { deleteRecursively(d); report += "deleted stale ${d.name}" }
                        continue
                    }
                }
                recoverDir(d)?.let { report += it }
            } catch (t: Throwable) {
                report += "failed ${d0.name}: $t"
            }
        }
        return report
    }

    /** Recovers one recording directory; returns a note when it had anything to recover. */
    fun recoverDir(d: Path): String? {
        val parts = Files.list(d).use { s -> s.filter { it.name.endsWith(".part") }.toList() }
        val manifest = readManifest(d)
        val unclosed = manifest != null && manifest.get("complete")?.asBoolean == false && manifest.get("crashed")?.asBoolean != true &&
            manifest.get("closed")?.asBoolean != true
        if (parts.isEmpty() && !unclosed) return null
        var lostAfter = -1L
        for (p in parts) {
            val name = p.name.removeSuffix(".part")              // part0001.jsonl.gz
            val partNo = Regex("""^part(\d+)\.""").find(name)?.groupValues?.get(1) ?: continue
            val idx = d.resolve("part$partNo.idx.jsonl")
            val ends = indexedEnds(idx)
            lostAfter = maxOf(lostAfter, ends.lastSeq)
            val end = when {
                name.endsWith(".jsonl.gz") -> ends.json
                name.endsWith(".raw.gz") -> ends.raw
                name.endsWith(".ent.xz") -> ends.ent
                else -> null
            }
            if (end != null) FileChannel.open(p, StandardOpenOption.WRITE).use { ch -> if (ch.size() > end) ch.truncate(end) }
            val target = p.resolveSibling(name)
            if (!Files.exists(target)) Files.move(p, target)
        }
        val m = manifest ?: JsonObject().also { it.addProperty("format", FORMAT); it.addProperty("id", d.name) }
        m.addProperty("complete", false)
        // A recording stopped for a disk error is not a crash; it just never got its files renamed.
        if (m.get("stoppedReason")?.takeIf { it.isJsonPrimitive } == null) m.addProperty("crashed", true)
        if (lostAfter < 0) lostAfter = m.get("lastGoodSeq")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L
        m.addProperty("lostAfterSeq", lostAfter)
        writeAtomically(d.resolve("manifest.json"), GsonBuilder().setPrettyPrinting().create().toJson(m))
        return "recovered ${d.name} (${parts.size} unfinished files, lost after seq $lostAfter)"
    }

    class Ends(val json: Long, val raw: Long, val ent: Long, val lastSeq: Long)

    /** Where the last indexed member of each stream ends; a torn last index line is ignored. */
    fun indexedEnds(idx: Path): Ends {
        var json = 0L; var raw = 0L; var ent = 0L; var last = -1L
        if (Files.exists(idx)) Files.readAllLines(idx).forEach { line ->
            val o = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return@forEach
            json = maxOf(json, o["off"].asLong + o["len"].asLong)
            o["rawOff"]?.let { raw = maxOf(raw, it.asLong + o["rawLen"].asLong) }
            o["ent"]?.asJsonArray?.let { ent = maxOf(ent, it[0].asLong + it[1].asLong) }
            o["seq"]?.asJsonArray?.let { last = maxOf(last, it[1].asLong) }
        }
        return Ends(json, raw, ent, last)
    }

    private fun readManifest(d: Path): JsonObject? = runCatching {
        JsonParser.parseString(Files.readString(d.resolve("manifest.json"))).asJsonObject
    }.getOrNull()
}
