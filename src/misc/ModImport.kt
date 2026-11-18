package com.engineerclient.misc

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.HUDSetting
import com.adm.features.ModuleManager
import com.engineerclient.EngineerClient
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** On a fresh install, turns on what the player already had on in Odin, Devonian and Blade Addons. Only ever turns things on. */
object ModImport {

    fun run(gameDir: File) {
        val config = File(gameDir, "config")
        var on = 0
        val saved = File(config, "engineerclient/imported")
        fun source(name: String) = com.engineerclient.ModTakeover.imported[name] ?: File(saved, name)
        read(source("odin-config.json"))?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { e ->
            val m = e as? JsonObject ?: return@forEach
            val name = m["name"]?.asString ?: return@forEach
            if (m["enabled"]?.asBoolean != true) return@forEach
            val odin = ImportTables.ODIN[name] ?: ImportTables.Odin(name).takeIf { ModuleManager.modules.containsKey(name.lowercase()) } ?: return@forEach
            if (turnOn(odin.to)) on++
            val module = odin.to.substringBefore('>')
            m.getAsJsonObject("settings")?.entrySet()?.forEach { (key, value) ->
                if (isOn(value) && turnOn("$module>${odin.renames[key] ?: key}", onlyIfExists = true)) on++
            }
        }
        read(source("devonianConfig.json"))?.asJsonObject?.getAsJsonObject("config")?.let { on += apply(it, ImportTables.DEVONIAN) }
        read(source("blade-addons.json"))?.asJsonObject?.let { on += apply(it, ImportTables.BLADE) }
        if (on > 0) EngineerClient.logger.info("[ec] turned on $on settings from other mods' configs")
    }

    private fun apply(json: JsonObject, table: Map<String, String>): Int =
        table.count { (key, to) -> json[key]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true && turnOn(to) }

    private fun isOn(v: JsonElement) =
        (v.isJsonPrimitive && v.asJsonPrimitive.isBoolean && v.asBoolean) || (v is JsonObject && v["enabled"]?.asBoolean == true)

    /** With [onlyIfExists], a setting the module doesn't have leaves the module as it was. */
    private fun turnOn(to: String, onlyIfExists: Boolean = false): Boolean {
        val module = ModuleManager.modules[to.substringBefore('>').lowercase()] ?: return false
        val name = to.substringAfter('>', "")
        if (name.isNotEmpty()) {
            when (val s = module.settings[name]) {
                is BooleanSetting -> s.value = true
                is HUDSetting -> s.value.enabled = true
                else -> if (onlyIfExists) return false
            }
        }
        if (!module.enabled) module.toggle()
        return true
    }

    private fun read(file: File) = try { if (file.isFile) JsonParser.parseString(file.readText()) else null } catch (t: Throwable) { null }
}
