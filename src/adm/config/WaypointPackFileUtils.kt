package com.adm.config

import com.google.gson.reflect.TypeToken
import com.adm.AdmMod
import com.adm.features.impl.dungeon.dungeonwaypoints.DungeonWaypoints.DungeonWaypoint
import com.adm.utils.modMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.minecraft.util.FileUtil
import java.io.File

object WaypointPackFileUtils {
    private val folder = File(AdmMod.configFile, "dungeon-waypoints").apply { mkdirs() }
    private val packType = object : TypeToken<MutableMap<String, MutableList<DungeonWaypoint>>>() {}.type

    // One thread for every pack file touch, so saves land in order and never race a rename or delete.
    internal val io = Dispatchers.IO.limitedParallelism(1)

    init {
        if (packNames().isEmpty()) runCatching {
            val legacy = File(AdmMod.configFile, "dungeon-waypoint-config.json")
            file("default").writeText(if (legacy.exists()) legacy.readText() else DungeonWaypointConfig.gson.toJson(LinkedHashMap<String, Any>()))
        }.onFailure { it.printStackTrace() }
    }

    private fun file(name: String) = File(folder, "$name.json")

    fun packNames(): List<String> {
        val names = ArrayList<String>()
        folder.listFiles()?.forEach { if (it.extension == "json") names.add(it.nameWithoutExtension) }
        names.sort()
        return names
    }

    internal fun stamp(selected: List<String>): List<Long> {
        val out = ArrayList<Long>()
        out.add(packNames().hashCode().toLong())
        for (id in selected) file(id).let { out.add(it.lastModified()); out.add(it.length()) }
        return out
    }

    internal fun read(name: String): MutableMap<String, MutableList<DungeonWaypoint>> = try {
        val f = file(name)
        (if (f.exists()) DungeonWaypointConfig.gson.fromJson<MutableMap<String, MutableList<DungeonWaypoint>>>(f.readText(), packType) else null)
            ?: LinkedHashMap()
    } catch (e: Exception) {
        e.printStackTrace()
        LinkedHashMap()
    }

    internal fun write(name: String, waypoints: Map<String, List<DungeonWaypoint>>) {
        try {
            file(name).writeText(DungeonWaypointConfig.gson.toJson(waypoints))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    internal fun saveLater(name: String, waypoints: Map<String, List<DungeonWaypoint>>) {
        AdmMod.scope.launch(io) { write(name, waypoints) }
    }

    suspend fun create(name: String): Boolean = withContext(io) {
        val f = file(name)
        when {
            !isValidName(name) -> refuse("Invalid pack name!")
            f.exists() -> refuse("Pack '$name' already exists!")
            else -> act("Created new pack: $name", "Failed to create pack!") {
                f.writeText(DungeonWaypointConfig.gson.toJson(LinkedHashMap<String, Any>()))
            }
        }
    }

    suspend fun delete(name: String): Boolean = withContext(io) {
        val f = file(name)
        if (!f.exists()) refuse("Pack '$name' does not exist!")
        else act("Deleted pack: $name", "Failed to delete pack!") { check(f.delete()) }
    }

    suspend fun rename(from: String, to: String): Boolean = withContext(io) {
        val source = file(from)
        val target = file(to)
        when {
            !isValidName(to) -> refuse("Invalid pack name!")
            !source.exists() -> refuse("Pack '$from' does not exist!")
            target.exists() -> refuse("Pack '$to' already exists!")
            else -> act("Renamed pack from '$from' to '$to'", "Failed to rename pack!") { check(source.renameTo(target)) }
        }
    }

    private fun refuse(message: String): Boolean {
        modMessage("§c$message")
        return false
    }

    private inline fun act(done: String, failed: String, block: () -> Unit): Boolean = try {
        block()
        modMessage("§a$done")
        true
    } catch (e: Exception) {
        e.printStackTrace()
        refuse(failed)
    }

    private fun isValidName(name: String) =
        name.isNotBlank() && FileUtil.sanitizeName(name) == name && FileUtil.isPathPartPortable(name)
}
