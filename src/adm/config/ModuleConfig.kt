@file:Suppress("unused")

package com.adm.config

import com.google.gson.*
import com.adm.AdmMod
import com.adm.AdmMod.logger
import com.adm.clickgui.settings.Saving
import com.adm.features.Module
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * # ModuleConfig
 *
 * This class handles saving Modules, and their settings, into a JSON format.
 */
class ModuleConfig internal constructor(file: File) {

    /**
     * Main constructor for Addons. (config/core/addons/{fileName})
     */
    constructor(fileName: String) : this(File(AdmMod.configFile, "addons/$fileName"))

    // key is module name in lowercase
    internal val modules: HashMap<String, Module> = hashMapOf()

    internal val file: File = file.apply {
        try {
            parentFile.mkdirs()
            createNewFile()
        } catch (e: Exception) {
            logger.error("Error initializing module config", e)
        }
    }

    /**
     * Loads configuration from file, into [modules]. A bad module or setting is skipped, not the rest of the file.
     */
    fun load() {
        val json = readFile() ?: return
        for (entry in json) {
            val moduleObj = entry as? JsonObject ?: continue
            try {
                val module = modules[moduleObj.get("name")?.asString?.lowercase() ?: continue] ?: continue
                val enabled = moduleObj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean
                if (enabled != null && enabled != module.enabled) module.toggle()
                val settingObj = moduleObj.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet() ?: continue
                for ((key, value) in settingObj) {
                    try {
                        (module.settings[key] as? Saving)?.read(value ?: continue, gson)
                    } catch (e: Exception) {
                        logger.error("Error loading setting $key of ${module.name}", e)
                    }
                }
            } catch (e: Exception) {
                logger.error("Error loading module ${moduleObj.get("name")}", e)
            }
        }
    }

    /**
     * Saves configuration to files, from [modules]. Modules and settings in the file that aren't registered
     * (yet) are kept, so an early save or another build can't wipe them.
     */
    fun save() = synchronized(lock) {
        try {
            val old = readFile()
            val oldByName = old?.filterIsInstance<JsonObject>()?.associateBy { it.get("name")?.takeIf { n -> n.isJsonPrimitive }?.asString?.lowercase() }.orEmpty()
            val jsonArray = JsonArray()
            for ((key, module) in modules) {
                val settings = oldByName[key]?.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() ?: JsonObject()
                for ((name, setting) in module.settings) {
                    if (setting !is Saving) continue
                    try { settings.add(name, setting.write(gson)) } catch (e: Exception) { logger.error("Error saving setting $name of ${module.name}", e) }
                }
                jsonArray.add(JsonObject().apply {
                    add("name", JsonPrimitive(module.name))
                    add("enabled", JsonPrimitive(module.enabled))
                    add("settings", settings)
                })
            }
            for ((key, obj) in oldByName) if (key != null && key !in modules) jsonArray.add(obj)
            writeFile(jsonArray)
        } catch (e: Exception) {
            logger.error("Error saving module config.", e)
        }
    }

    /** Saves only [module]: its settings are read here, the file is written off the game thread. */
    fun saveModule(module: Module) {
        val settings = JsonObject()
        for ((name, setting) in module.settings) {
            if (setting !is Saving) continue
            try { settings.add(name, setting.write(gson)) } catch (e: Exception) { logger.error("Error saving setting $name of ${module.name}", e) }
        }
        val enabled = module.enabled
        val key = module.name.lowercase()
        com.engineerclient.misc.DiskWrites.write {
            synchronized(lock) {
                try {
                    val jsonArray = readFile() ?: JsonArray()
                    val entry = jsonArray.filterIsInstance<JsonObject>().firstOrNull { it.get("name")?.takeIf { n -> n.isJsonPrimitive }?.asString?.lowercase() == key }
                        ?: JsonObject().also { jsonArray.add(it) }
                    val merged = entry.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                    for ((name, value) in settings.entrySet()) merged.add(name, value)
                    entry.add("name", JsonPrimitive(module.name))
                    entry.add("enabled", JsonPrimitive(enabled))
                    entry.add("settings", merged)
                    writeFile(jsonArray)
                } catch (e: Exception) {
                    logger.error("Error saving module config.", e)
                }
            }
        }
    }

    private fun writeFile(jsonArray: JsonArray) {
        val text = gson.toJson(jsonArray)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** The file's modules; null when empty or missing. A file that can't be read is copied aside before anything overwrites it. */
    private fun readFile(): JsonArray? {
        val text = try { if (file.isFile) file.readText(Charsets.UTF_8) else return null } catch (e: Exception) { logger.error("Error reading module config", e); return null }
        if (text.isBlank()) return null
        return try {
            JsonParser.parseString(text).asJsonArray
        } catch (e: Exception) {
            logger.error("Module config $file is broken, keeping a copy", e)
            try { file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true) } catch (_: Exception) {}
            null
        }
    }

    private companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        private val lock = Any()
    }

    override fun toString(): String {
        return "ModuleConfig(file=$file)"
    }
}