package com.engineerclient.dungeonpack.pack

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/*
 * Null-tolerant JSON reading for the data packs. The pack specs say readers ignore unknown fields and tolerate `null`
 * anywhere a field is documented as nullable, so nothing here throws on a missing or null key: it answers null.
 */

internal fun JsonElement?.asObj(): JsonObject? = if (this != null && isJsonObject) asJsonObject else null
internal fun JsonElement?.asArr(): JsonArray? = if (this != null && isJsonArray) asJsonArray else null
internal fun JsonElement?.asStr(): String? = if (this != null && isJsonPrimitive) asString else null
internal fun JsonElement?.asIntOrNull(): Int? = if (this != null && isJsonPrimitive && asJsonPrimitive.isNumber) asDouble.toInt() else null
internal fun JsonElement?.asLongOrNull(): Long? = if (this != null && isJsonPrimitive && asJsonPrimitive.isNumber) asLong else null
internal fun JsonElement?.asDoubleOrNull(): Double? = if (this != null && isJsonPrimitive && asJsonPrimitive.isNumber) asDouble else null
internal fun JsonElement?.asBoolOrNull(): Boolean? = if (this != null && isJsonPrimitive && asJsonPrimitive.isBoolean) asBoolean else null

internal fun JsonObject.obj(k: String): JsonObject? = get(k).asObj()
internal fun JsonObject.arr(k: String): JsonArray? = get(k).asArr()
internal fun JsonObject.str(k: String): String? = get(k).asStr()
internal fun JsonObject.int(k: String): Int? = get(k).asIntOrNull()
internal fun JsonObject.long(k: String): Long? = get(k).asLongOrNull()
internal fun JsonObject.dbl(k: String): Double? = get(k).asDoubleOrNull()
internal fun JsonObject.bool(k: String): Boolean? = get(k).asBoolOrNull()

/** Every element of an array that is an object (anything else is skipped). */
internal fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it.asObj() }.orEmpty()
internal fun JsonArray?.strings(): List<String> = this?.mapNotNull { it.asStr() }.orEmpty()
internal fun JsonArray?.doubles(): DoubleArray = this?.mapNotNull { it.asDoubleOrNull() }?.toDoubleArray() ?: DoubleArray(0)
internal fun JsonArray?.ints(): IntArray = this?.mapNotNull { it.asIntOrNull() }?.toIntArray() ?: IntArray(0)

/** `[a, b]` as a [Tile]; null when it is not two numbers. */
internal fun JsonElement?.asTile(): Tile? {
    val a = asArr() ?: return null
    if (a.size() < 2) return null
    return Tile(a[0].asIntOrNull() ?: return null, a[1].asIntOrNull() ?: return null)
}

/** `[x, y, z]` as a [Pos]; null when it is not three numbers. */
internal fun JsonElement?.asPos(): Pos? {
    val a = asArr() ?: return null
    if (a.size() < 3) return null
    return Pos(a[0].asIntOrNull() ?: return null, a[1].asIntOrNull() ?: return null, a[2].asIntOrNull() ?: return null)
}

internal fun JsonElement?.asTiles(): List<Tile> = asArr()?.mapNotNull { it.asTile() }.orEmpty()
internal fun JsonElement?.asPosList(): List<Pos> = asArr()?.mapNotNull { it.asPos() }.orEmpty()

/** Reads gzip-compressed UTF-8 JSON (every pack's index file) into its top-level object; throws on bad gzip or JSON. */
internal fun readGzipJson(stream: InputStream): JsonObject =
    GZIPInputStream(stream.buffered(), 1 shl 16).use { gz ->
        JsonParser.parseReader(gz.reader(StandardCharsets.UTF_8)).asObj() ?: error("the top level is not a JSON object")
    }

/** Reads plain UTF-8 JSON (used by tests and by dev tools). */
internal fun parseJson(text: String): JsonObject = JsonParser.parseString(text).asObj() ?: error("the top level is not a JSON object")
