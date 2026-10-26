package com.engineerclient.adr

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.tukaani.xz.XZInputStream
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.ref.SoftReference
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The site's template catalogues ([Templates]): <section>-<version>.json.xz at /adr/cat/, built from
 * its own runs and never changed once out. The versions this build sends numbers from are fixed
 * here ([BLOCKS]); the viewer has every version. Each is fetched once into a folder and read from
 * there; one that can't be had (offline, a bad file) is left out, and the run goes up without it.
 */
class Catalogue(val blocks: Blocks?, val values: Values? = null, val skulls: Skulls? = null) {

    /** Block change pieces ([Templates.pieces]): their keys, which ones each usually goes on to (most often first), the boss floors that go at once. */
    class Blocks(val version: Int, val pieces: List<String>, val next: List<IntArray>, val ends: List<Templates.End>) {
        val id = HashMap<String, Int>(pieces.size * 2).apply { pieces.forEachIndexed { i, k -> putIfAbsent(k, i) } }
    }

    /** Values that come over and over (gear, head skins, names, poses, types), per field: each one's number, by [canon]. */
    class Values(val version: Int, val fields: Map<String, Map<String, Int>>, val lists: Map<String, List<com.google.gson.JsonElement>> = emptyMap())

    /** Heads placed in the world: each spot's number by "space|a,y,b|skin" (in its frame, [Templates.Frame]). */
    class Skulls(val version: Int, val id: Map<String, Int>)

    companion object {
        const val BLOCKS = 1
        const val VALUES = 1
        const val SKULLS = 1
        private const val MAX_BYTES = 16 shl 20 // (packed; a catalogue is under a MB)
        private const val MAX_TEXT = 256L shl 20
        private val log = LoggerFactory.getLogger("engineerclient")
        private var cached: SoftReference<Catalogue>? = null

        /**
         * The catalogues, from [dir] (fetching any it hasn't: [fetch] gets a file's bytes from the
         * site, or null). Kept while memory allows: an upload after a run reads it again otherwise.
         */
        @Synchronized fun load(dir: Path, fetch: (String) -> ByteArray?): Catalogue {
            cached?.get()?.let { if (it.blocks != null && it.values != null && it.skulls != null) return it }
            val blocks = section(dir, "blocks-$BLOCKS.json.xz", fetch) { readBlocks(it, BLOCKS) }
            val values = section(dir, "values-$VALUES.json.xz", fetch) { readValues(it, VALUES) }
            val skulls = section(dir, "skulls-$SKULLS.json.xz", fetch) { readSkulls(it, SKULLS) }
            return Catalogue(blocks, values, skulls).also { cached = SoftReference(it) }
        }

        private fun <T> section(dir: Path, name: String, fetch: (String) -> ByteArray?, read: (InputStream) -> T): T? {
            val file = dir.resolve(name)
            try {
                if (!Files.isRegularFile(file)) {
                    val bytes = fetch(name)?.takeIf { it.size in 1..MAX_BYTES } ?: return null
                    Files.createDirectories(dir)
                    val part = dir.resolve("$name.part")
                    Files.write(part, bytes)
                    Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
                return Files.newInputStream(file).use { read(XZInputStream(it.buffered(), 32768)) }
            } catch (e: Exception) {
                // (a file that doesn't read is fetched again next time)
                log.warn("[ec] adr: catalogue $name couldn't be had, uploading without it: $e")
                runCatching { Files.deleteIfExists(file) }
                return null
            }
        }

        /** A blocks catalogue (tools/catalogue.mjs on the site): {"pieces": [key...], "next": [[id...]...], "ends": [{boxes, min, near, rule, cells}...]}. */
        fun readBlocks(input: InputStream, version: Int): Blocks {
            val pieces = ArrayList<String>()
            val next = ArrayList<IntArray>()
            val ends = ArrayList<Templates.End>()
            JsonReader(InputStreamReader(Capped(input, MAX_TEXT), Charsets.UTF_8)).use { r ->
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "pieces" -> { r.beginArray(); while (r.hasNext()) pieces += r.nextString(); r.endArray() }
                    "next" -> { r.beginArray(); while (r.hasNext()) { r.beginArray(); val l = ArrayList<Int>(); while (r.hasNext()) l += r.nextInt(); r.endArray(); next += l.toIntArray() }; r.endArray() }
                    "ends" -> { r.beginArray(); while (r.hasNext()) ends += readEnd(r); r.endArray() }
                    else -> r.skipValue()
                }
                r.endObject()
            }
            if (pieces.isEmpty()) throw IllegalStateException("no pieces")
            for (l in next) for (n in l) if (n !in pieces.indices) throw IllegalStateException("next $n of ${pieces.size}")
            while (next.size < pieces.size) next += IntArray(0)
            return Blocks(version, pieces, next, ends)
        }

        /** A values catalogue: {"fields": {field: [value...]}}. */
        fun readValues(input: InputStream, version: Int): Values {
            val o = com.google.gson.JsonParser.parseReader(InputStreamReader(Capped(input, MAX_TEXT), Charsets.UTF_8)).asJsonObject
            val fields = HashMap<String, Map<String, Int>>()
            val lists = HashMap<String, List<com.google.gson.JsonElement>>()
            for ((f, list) in o.getAsJsonObject("fields").entrySet()) {
                val m = HashMap<String, Int>()
                list.asJsonArray.forEachIndexed { i, v -> m.putIfAbsent(canon(v), i) }
                fields[f] = m; lists[f] = list.asJsonArray.toList()
            }
            if (fields.isEmpty()) throw IllegalStateException("no fields")
            return Values(version, fields, lists)
        }

        /** A skulls catalogue: {"tex": [skin...], "spots": [[space, a, y, b, skin number]...]}. */
        fun readSkulls(input: InputStream, version: Int): Skulls {
            val o = com.google.gson.JsonParser.parseReader(InputStreamReader(Capped(input, MAX_TEXT), Charsets.UTF_8)).asJsonObject
            val tex = o.getAsJsonArray("tex").map { it.asString }
            val id = HashMap<String, Int>()
            o.getAsJsonArray("spots").forEachIndexed { i, e ->
                val a = e.asJsonArray
                id.putIfAbsent(skullKey(a[0].asString, a[1].asInt, a[2].asInt, a[3].asInt, tex[a[4].asInt]), i)
            }
            if (id.isEmpty()) throw IllegalStateException("no spots")
            return Skulls(version, id)
        }
        fun skullKey(space: String, a: Int, y: Int, b: Int, tex: String) = "$space|$a,$y,$b|$tex"

        /** A value as the same text whichever way it was written (-10.0 and -10 alike, as the viewer has them). */
        fun canon(e: com.google.gson.JsonElement): String = when {
            e is com.google.gson.JsonArray -> e.joinToString(",", "[", "]") { canon(it) }
            e is com.google.gson.JsonPrimitive && e.isNumber -> runCatching { java.math.BigDecimal(e.asString).stripTrailingZeros().toPlainString() }.getOrDefault(e.asString).let { if (it == "-0") "0" else it }
            e is com.google.gson.JsonObject -> e.entrySet().joinToString(",", "{", "}") { com.google.gson.JsonPrimitive(it.key).toString() + ":" + canon(it.value) }
            else -> e.toString()
        }

        private fun readEnd(r: JsonReader): Templates.End {
            val boxes = ArrayList<IntArray>(); val cells = ArrayList<IntArray>()
            var min = Int.MAX_VALUE; var near = 0; var all = false
            fun ints(): IntArray { r.beginArray(); val l = ArrayList<Int>(); while (r.hasNext()) l += r.nextInt(); r.endArray(); return l.toIntArray() }
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "boxes" -> { r.beginArray(); while (r.hasNext()) boxes += ints().also { require(it.size == 6) }; r.endArray() }
                "cells" -> { r.beginArray(); while (r.hasNext()) cells += ints().also { require(it.size == 3) }; r.endArray() }
                "min" -> min = r.nextInt()
                "near" -> near = r.nextInt()
                "rule" -> all = if (r.peek() == JsonToken.STRING) r.nextString() == "all" else { r.skipValue(); false }
                else -> r.skipValue()
            }
            r.endObject()
            return Templates.End(boxes, min, near, all, cells)
        }
    }

    /** At most [max] bytes of [input]; past that it fails. */
    private class Capped(private val input: InputStream, private var max: Long) : InputStream() {
        override fun read(): Int = if (max-- <= 0) throw java.io.IOException("too big") else input.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (max <= 0) throw java.io.IOException("too big")
            val n = input.read(b, off, minOf(len.toLong(), max).toInt())
            if (n > 0) max -= n
            return n
        }
        override fun close() = input.close()
    }
}
