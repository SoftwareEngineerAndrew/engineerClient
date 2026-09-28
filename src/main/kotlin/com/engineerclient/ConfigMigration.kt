package com.engineerclient

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/** Settings kept across renames and moves; see [run]. Pure file work, so it tests without the game. */
object ConfigMigration {

    /**
     * One-time moves inside config/odin/addons/engineerclient.json, before Odin reads it, so a
     * module that was renamed or folded into another keeps what was set in it:
     *  - BR Waypoints 2 is BR Roles;
     *  - Lowest BIN is two settings in Random Stuff;
     *  - what used to live in a modified Odin and ships with engineerClient now - Leap Menu's Click
     *    Delay and Leap Outline (Leap Extras), Player Display's Health/Mana Bar HUDs (Random Stuff) -
     *    is copied out of Odin's own config, where that Odin saved it.
     * Each only happens while its target is still missing, so it runs once. [odinDir] is
     * config/odin. True if the file was rewritten.
     */
    fun run(odinDir: Path): Boolean {
        val file = odinDir.resolve("addons").resolve("engineerclient.json")
        if (!Files.exists(file)) return false
        val modules = JsonParser.parseString(Files.readString(file)).asJsonArray
        fun ensure(name: String) = module(modules, name) ?: JsonObject().apply {
            addProperty("name", name); addProperty("enabled", true); add("settings", JsonObject())
        }.also { modules.add(it) }
        var changed = false

        module(modules, "BR Waypoints 2")?.let {
            if (module(modules, "BR Roles") == null) { it.addProperty("name", "BR Roles"); changed = true }
        }
        module(modules, "Lowest BIN")?.let { old ->
            val rs = settings(ensure("Random Stuff"))
            if (!rs.has("Lowest BIN")) {
                rs.addProperty("Lowest BIN", old["enabled"]?.asBoolean ?: true)
                settings(old)["Stack Total"]?.let { rs.add("Lowest BIN Stack Total", it) }
            }
            modules.remove(old)
            changed = true
        }

        val odinFile = odinDir.resolve("odin-config.json")
        if (Files.exists(odinFile)) {
            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray
            fun copy(from: String, to: String, keys: List<String>) {
                val src = module(odin, from)?.let(::settings) ?: return
                if (keys.none { src.has(it) }) return // never saved by a modified Odin
                val dst = settings(ensure(to))
                if (keys.any { dst.has(it) }) return
                for (k in keys) src[k]?.let { dst.add(k, it) }
                changed = true
            }
            copy("Leap Menu", "Leap Extras", listOf("Click Delay", "Leap Outline"))
            copy("Player Display", "Random Stuff", listOf("Health Bar HUD", "Health Bar Width", "Health Bar Height", "Mana Bar HUD", "Mana Bar Width", "Mana Bar Height"))
        }

        if (changed) Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(modules))
        return changed
    }

    private fun module(list: JsonArray, name: String): JsonObject? =
        list.firstOrNull { it.isJsonObject && it.asJsonObject["name"]?.asString == name }?.asJsonObject

    private fun settings(m: JsonObject): JsonObject =
        m.getAsJsonObject("settings") ?: JsonObject().also { m.add("settings", it) }
}
