package com.ascent.waypoints

import com.ascent.AscentMod
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.odtheking.odin.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.WaypointType
import com.odtheking.odin.utils.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import java.io.File
import java.lang.reflect.Type

/**
 * VENDORED from Odin `config/WaypointPackFileUtils.kt` + the serializers from
 * `config/DungeonWaypointConfig.kt` (upstream 0.3.1 — see VENDORED.md).
 *
 * Differences from upstream, kept deliberate and small:
 * - packs live in `config/ascent/waypoints/` instead of `config/odin/dungeon-waypoints/`
 * - `createPack` is SILENT when the pack already exists (Ascent ensures 40 packs on
 *   every launch; upstream chats an error per duplicate)
 * - no legacy-config migration (Ascent has no legacy format)
 * - reuses Odin's public `DungeonWaypoints.DungeonWaypoint` type, and the on-disk JSON
 *   shape matches Odin's pack files exactly, so packs can be copied between systems.
 */
object AscentPackFiles {

    data class WaypointPack(
        val name: String,
        val file: File,
        val waypoints: MutableMap<String, MutableList<DungeonWaypoint>>,
    )

    private val gson = GsonBuilder()
        .registerTypeAdapter(AABB::class.java, AabbAdapter())
        .registerTypeAdapter(BlockPos::class.java, BlockPosAdapter())
        .registerTypeAdapter(DungeonWaypoint::class.java, WaypointDeserializer())
        .setPrettyPrinting()
        .create()

    private val packType =
        object : TypeToken<MutableMap<String, MutableList<DungeonWaypoint>>>() {}.type

    val packsFolder = File(Minecraft.getInstance().gameDirectory, "config/ascent/waypoints").apply { mkdirs() }

    private fun packFile(name: String) = File(packsFolder, "$name.json")
    private fun emptyPack() = mutableMapOf<String, MutableList<DungeonWaypoint>>()

    suspend fun loadPack(packName: String): MutableMap<String, MutableList<DungeonWaypoint>> = withContext(Dispatchers.IO) {
        runCatching {
            packFile(packName)
                .takeIf(File::exists)
                ?.readText()
                ?.let { gson.fromJson<MutableMap<String, MutableList<DungeonWaypoint>>>(it, packType) }
                ?: emptyPack()
        }.getOrElse {
            AscentMod.logger.error("[ascent] failed to load pack '$packName'", it)
            emptyPack()
        }
    }

    suspend fun savePack(packName: String, waypoints: MutableMap<String, MutableList<DungeonWaypoint>>) =
        withContext(Dispatchers.IO) {
            runCatching {
                packFile(packName).writeText(gson.toJson(waypoints))
            }.onFailure { AscentMod.logger.error("[ascent] failed to save pack '$packName'", it) }
        }

    /** Returns true only when a new pack file was actually created; silent when it already exists. */
    suspend fun createPack(packName: String): Boolean = withContext(Dispatchers.IO) {
        when {
            !isValidPackName(packName) -> {
                AscentMod.chat("§8[§6Ascent§8]§c invalid pack name '$packName' — letters, numbers, spaces, hyphens, underscores only")
                false
            }
            packFile(packName).exists() -> false
            else -> runCatching {
                packFile(packName).writeText(gson.toJson(emptyPack()))
                true
            }.getOrElse {
                AscentMod.logger.error("[ascent] failed to create pack '$packName'", it)
                false
            }
        }
    }

    suspend fun deletePack(packName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { packFile(packName).takeIf(File::exists)?.delete() == true }
            .getOrElse {
                AscentMod.logger.error("[ascent] failed to delete pack '$packName'", it)
                false
            }
    }

    suspend fun getAllPacks(): List<WaypointPack> = withContext(Dispatchers.IO) {
        packsFolder.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    WaypointPack(file.nameWithoutExtension, file, gson.fromJson(file.readText(), packType))
                }.getOrElse {
                    AscentMod.logger.error("[ascent] failed to read pack file ${file.name}", it)
                    null
                }
            } ?: emptyList()
    }

    /** Synchronous name listing for GUI population (no waypoint parsing). */
    fun listPackNames(): List<String> =
        packsFolder.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    private fun isValidPackName(name: String) =
        name.isNotBlank() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }

    // --- serializers: byte-for-byte the same JSON shape as Odin's pack files ---

    private class AabbAdapter : JsonSerializer<AABB>, JsonDeserializer<AABB> {
        override fun serialize(src: AABB, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement =
            JsonObject().apply {
                addProperty("minX", src.minX); addProperty("minY", src.minY); addProperty("minZ", src.minZ)
                addProperty("maxX", src.maxX); addProperty("maxY", src.maxY); addProperty("maxZ", src.maxZ)
            }

        override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): AABB {
            val o = json.asJsonObject
            if (o.has("minX")) return AABB(
                o["minX"].asDouble, o["minY"].asDouble, o["minZ"].asDouble,
                o["maxX"].asDouble, o["maxY"].asDouble, o["maxZ"].asDouble
            )
            return AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    private class BlockPosAdapter : JsonSerializer<BlockPos>, JsonDeserializer<BlockPos> {
        override fun serialize(src: BlockPos, typeOfSrc: Type, context: JsonSerializationContext): JsonElement =
            JsonObject().apply { addProperty("x", src.x); addProperty("y", src.y); addProperty("z", src.z) }

        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): BlockPos {
            val o = json.asJsonObject
            return BlockPos(o["x"].asInt, o["y"].asInt, o["z"].asInt)
        }
    }

    private class WaypointDeserializer : JsonDeserializer<DungeonWaypoint> {
        override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): DungeonWaypoint {
            val o = json.asJsonObject
            val blockPos = if (o.has("blockPos")) context.deserialize(o["blockPos"], BlockPos::class.java)
            else BlockPos(o["x"].asInt, o["y"].asInt, o["z"].asInt)
            val color = context.deserialize<Color>(o["color"], Color::class.java)
            val filled = o["filled"].asBoolean
            val depth = o["depth"].asBoolean
            val aabb = context.deserialize<AABB>(o["aabb"], AABB::class.java)
            val title = o["title"]?.asString?.ifBlank { null }
            val waypointType = o["type"]?.asString?.let { runCatching { WaypointType.valueOf(it) }.getOrNull() }

            val waypoint = DungeonWaypoint(blockPos, color, filled, depth, aabb, title, type = waypointType)
            if (waypointType == null && o.has("secret") && o["secret"]?.asBoolean == true)
                waypoint.type = WaypointType.SECRET
            return waypoint
        }
    }
}
