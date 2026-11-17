package com.adm.config

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.adm.AdmMod
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.WaypointType
import com.adm.utils.Color
import com.adm.utils.network.WebUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import java.io.ByteArrayOutputStream
import java.lang.reflect.Type
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64

object DungeonWaypointConfig {
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(AABB::class.java, BoxAdapter)
        .registerTypeAdapter(DungeonWaypoint::class.java, WaypointReader)
        .registerTypeAdapter(BlockPos::class.java, PosAdapter)
        .setPrettyPrinting().create()

    private val packType = object : TypeToken<MutableMap<String, MutableList<DungeonWaypoint>>>() {}.type

    fun encodeWaypoints(waypoints: Map<String, List<DungeonWaypoint>>): String? = try {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(gson.toJson(waypoints).toByteArray()) }
        Base64.encode(bytes.toByteArray())
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    /** [source] is a pack string, a link to one, or null for [clipboard]; a link that can't be fetched also falls back to [clipboard]. */
    suspend fun decodeWaypoints(source: String?, clipboard: String): MutableMap<String, MutableList<DungeonWaypoint>>? {
        val raw = (if (source == null) null else if (source.startsWith("https://")) WebUtils.fetchString(source).getOrNull() else source) ?: clipboard
        val input = raw.trim().trim { it == '\n' }
        return try {
            val json = if (input.startsWith("{")) input
            else GZIPInputStream(Base64.decode(input).inputStream()).bufferedReader().use { it.readText() }
            gson.fromJson(json, packType)
        } catch (_: Exception) {
            null
        }
    }

    private object WaypointReader : JsonDeserializer<DungeonWaypoint> {
        override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): DungeonWaypoint {
            val o = json.asJsonObject
            val pos = if (o.has("blockPos")) context.deserialize(o["blockPos"], BlockPos::class.java)
            else BlockPos(o["x"].asInt, o["y"].asInt, o["z"].asInt)
            val kind = o["type"]?.asString?.let { name -> WaypointType.entries.firstOrNull { it.name == name } }
                ?: WaypointType.SECRET.takeIf { o["secret"]?.asBoolean == true }
            return DungeonWaypoint(
                pos,
                context.deserialize(o["color"], Color::class.java),
                o["filled"].asBoolean,
                o["depth"].asBoolean,
                context.deserialize(o["aabb"], AABB::class.java),
                o["title"]?.asString?.ifBlank { null },
                kind,
            )
        }
    }

    // Older saves used intermediary and legacy field names for the box corners.
    private val boxKeys = arrayOf(
        arrayOf("minX", "minY", "minZ", "maxX", "maxY", "maxZ"),
        arrayOf("field_1323", "field_1322", "field_1321", "field_1320", "field_1325", "field_1324"),
        arrayOf("field_72340_a", "field_72338_b", "field_72339_c", "field_72336_d", "field_72337_e", "field_72334_f"),
    )

    private object BoxAdapter : JsonSerializer<AABB>, JsonDeserializer<AABB> {
        override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): AABB {
            val o = json.asJsonObject
            val k = boxKeys.firstOrNull { o.has(it[0]) } ?: return AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            return AABB(o[k[0]].asDouble, o[k[1]].asDouble, o[k[2]].asDouble, o[k[3]].asDouble, o[k[4]].asDouble, o[k[5]].asDouble)
        }

        override fun serialize(src: AABB, type: Type?, context: JsonSerializationContext?): JsonElement = JsonObject().apply {
            addProperty("minX", src.minX)
            addProperty("minY", src.minY)
            addProperty("minZ", src.minZ)
            addProperty("maxX", src.maxX)
            addProperty("maxY", src.maxY)
            addProperty("maxZ", src.maxZ)
        }
    }

    private object PosAdapter : JsonSerializer<BlockPos>, JsonDeserializer<BlockPos> {
        override fun serialize(src: BlockPos, type: Type, context: JsonSerializationContext): JsonElement = JsonObject().apply {
            addProperty("x", src.x)
            addProperty("y", src.y)
            addProperty("z", src.z)
        }

        override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): BlockPos {
            val o = json.asJsonObject
            return when {
                o.has("field_11173") -> BlockPos(o["field_11175"].asInt, o["field_11174"].asInt, o["field_11173"].asInt)
                o.has("x") -> BlockPos(o["x"].asInt, o["y"].asInt, o["z"].asInt)
                else -> {
                    AdmMod.logger.debug("Failed to deserialize BlockPos\n$json")
                    BlockPos(0, 0, 0)
                }
            }
        }
    }
}
