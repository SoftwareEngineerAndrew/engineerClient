package com.engineerclient

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minecraft 26.1.2 + Odin 0.3.6 port only. Coming from an Engineer Client that still had the modules
 * Coffee Client and Devgineer Client took over, whose settings now live in their own files
 * (config/odin/addons/coffeeclient.json and devgineerclient.json). This fills each of those once,
 * while it doesn't exist yet, so what was set carries over:
 *  - the modules that moved, as they were (on/off, settings, HUD positions);
 *  - Coffee Client's Random Stuff: the toggles it took from Engineer Client's Random Stuff, on if
 *    that was (its own defaults for them differ, e.g. Mute Party Chat In Boss).
 * Runs before Coffee Client and Devgineer Client read their files. Pure file work, like [ConfigMigration].
 */
object PortConfigMigration {

    private val COFFEE_MODULES = listOf("Speed HUD", "Sound Editor", "Camera Offset", "Entity Distance", "Pre-Requeue", "Better PF Menu", "Leap Extras")
    private val DEVGINEER_MODULES = listOf("Maxor Crystals", "Boss Recorder", "Dungeon Recorder")
    private val COFFEE_RANDOM_STUFF_KEYS = listOf(
        "Hide Damage Indicators", "Hide Armor Stands", "Mute Completion Ding", "Keep Gate & Core Ding",
        "Mute Party Chat In Boss", "Movable Scoreboard", "Scoreboard",
    )

    /** [odinDir] is config/odin. True if a file was written. */
    fun run(odinDir: Path): Boolean {
        val addons = odinDir.resolve("addons")
        val ecFile = addons.resolve("engineerclient.json")
        if (!Files.exists(ecFile)) return false
        val ec = JsonParser.parseString(Files.readString(ecFile)).asJsonArray

        val coffee = JsonArray()
        COFFEE_MODULES.mapNotNull { module(ec, it) }.forEach { coffee.add(it.deepCopy()) }
        module(ec, "Random Stuff")?.let { rs ->
            val from = rs.getAsJsonObject("settings") ?: return@let
            val settings = JsonObject()
            for (key in COFFEE_RANDOM_STUFF_KEYS) from[key]?.let { settings.add(key, it.deepCopy()) }
            if (settings.size() == 0) return@let
            coffee.add(JsonObject().apply {
                addProperty("name", "Random Stuff")
                addProperty("enabled", rs["enabled"]?.asBoolean ?: true)
                add("settings", settings)
            })
        }

        val devgineer = JsonArray()
        DEVGINEER_MODULES.mapNotNull { module(ec, it) }.forEach { devgineer.add(it.deepCopy()) }

        return writeOnce(addons.resolve("coffeeclient.json"), coffee) or writeOnce(addons.resolve("devgineerclient.json"), devgineer)
    }

    private fun writeOnce(file: Path, modules: JsonArray): Boolean {
        if (modules.isEmpty || Files.exists(file)) return false
        Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(modules))
        return true
    }

    private fun module(list: JsonArray, name: String): JsonObject? =
        list.firstOrNull { it.isJsonObject && it.asJsonObject["name"]?.asString == name }?.asJsonObject
}
