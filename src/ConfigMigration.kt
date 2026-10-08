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
     *  - the health and mana settings moved from Random Stuff to Health & Mana (on if Random Stuff
     *    was); Player Display's Health/Mana Bar HUD settings found in Odin's own config are copied
     *    to Health & Mana, which provides those HUDs;
     *  - Sub Splits' detail levels: "Extreme" is "Debug", and "Off" is the HUD switched off;
     *  - a former engineerClient Splits HUD that was on becomes Odin's Splits in the Engineer
     *    Splits look (written into Odin's config, read once the look's settings exist - see
     *    OdinSplitsLook.install);
     *  - Positional Messages was removed from Odin in 0.3.6 and is provided here: Odin's module
     *    (on/off, settings and the saved boxes) is copied over as it was.
     * Each only happens while its target is still missing, so it runs once. [odinDir] is
     * config/odin. True if the file was rewritten.
     */
    fun run(odinDir: Path): Boolean {
        val file = odinDir.resolve("addons").resolve("engineerclient.json")
        val odinFile = odinDir.resolve("odin-config.json")
        val oldPosMsgs = if (Files.exists(odinFile)) module(JsonParser.parseString(Files.readString(odinFile)).asJsonArray, "Positional Messages") else null
        if (!Files.exists(file) && oldPosMsgs == null) return false
        val modules = if (Files.exists(file)) JsonParser.parseString(Files.readString(file)).asJsonArray else JsonArray()
        fun ensure(name: String) = module(modules, name) ?: JsonObject().apply {
            addProperty("name", name); addProperty("enabled", true); add("settings", JsonObject())
        }.also { modules.add(it) }
        var changed = false

        if (oldPosMsgs != null && module(modules, "Positional Messages") == null) {
            modules.add(oldPosMsgs.deepCopy())
            changed = true
        }

        module(modules, "BR Waypoints 2")?.let {
            if (module(modules, "BR Roles") == null) { it.addProperty("name", "BR Roles"); changed = true }
        }

        module(modules, "Random Stuff")?.let { rs ->
            val from = settings(rs)
            val moved = HEALTH_MANA_KEYS.filter { from.has(it) }
            if (moved.isEmpty() || module(modules, "Health & Mana") != null) return@let
            val to = settings(ensure("Health & Mana").also { it.addProperty("enabled", rs["enabled"]?.asBoolean ?: true) })
            for (k in moved) to.add(k, from.remove(k))
            changed = true
        }

        // Sub Splits' detail levels lost "Off" and renamed "Extreme" to "Debug". Off was a way of
        // hiding the HUD, so it becomes the HUD switched off (the level itself back to Compact).
        module(modules, "Sub Splits")?.let(::settings)?.let { sub ->
            for ((key, value) in sub.entrySet().toList()) {
                if (!key.endsWith(" Detail") || !value.isJsonPrimitive) continue
                when (value.asString) {
                    "Extreme" -> { sub.addProperty(key, "Debug"); changed = true }
                    "Off" -> {
                        sub.addProperty(key, "Compact")
                        sub[key.removeSuffix(" Detail") + " Sub Splits"]?.takeIf { it.isJsonObject }?.asJsonObject?.addProperty("enabled", false)
                        changed = true
                    }
                }
            }
        }

        if (Files.exists(odinFile)) {
            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray
            fun copy(from: String, to: String, keys: List<String>) {
                val src = module(odin, from)?.let(::settings) ?: return
                if (keys.none { src.has(it) }) return // nothing to copy
                val dst = settings(ensure(to))
                if (keys.any { dst.has(it) }) return
                for (k in keys) src[k]?.let { dst.add(k, it) }
                changed = true
            }
            copy("Player Display", "Health & Mana", listOf("Health Bar HUD", "Health Bar Width", "Health Bar Height", "Mana Bar HUD", "Mana Bar Width", "Mana Bar Height"))

            val oldSplitsHud = module(modules, "Sub Splits")?.let(::settings)?.get("Splits")
            val used = oldSplitsHud?.takeIf { it.isJsonObject }?.asJsonObject?.get("enabled")?.asBoolean == true
            val odinSplits = module(odin, "Splits")?.let(::settings)
            if (used && odinSplits != null && !odinSplits.has("Look")) {
                odinSplits.addProperty("Look", "Engineer Splits")
                Files.writeString(odinFile, GsonBuilder().setPrettyPrinting().create().toJson(odin))
                changed = true
            }
        }

        if (changed) {
            Files.createDirectories(file.parent)
            Files.writeString(file, GsonBuilder().setPrettyPrinting().create().toJson(modules))
        }
        return changed
    }

    private val HEALTH_MANA_KEYS = listOf(
        "Hide Health/Mana Above %", "Threshold", "Health Bar HUD", "Health Bar Width", "Health Bar Height",
        "Mana Bar HUD", "Mana Bar Width", "Mana Bar Height",
    )

    private fun module(list: JsonArray, name: String): JsonObject? =
        list.firstOrNull { it.isJsonObject && it.asJsonObject["name"]?.asString == name }?.asJsonObject

    private fun settings(m: JsonObject): JsonObject =
        m.getAsJsonObject("settings") ?: JsonObject().also { m.add("settings", it) }
}
