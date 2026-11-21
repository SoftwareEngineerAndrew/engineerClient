package com.engineerclient

import com.engineerclient.misc.ImportTables
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Runs before any mod reads its settings. On a fresh install Odin's, Devonian's and Blade Addons' configs are copied to
 * config/engineerclient/imported for [com.engineerclient.misc.ModImport], and Odin's settings for ADM's modules become ADM's. Every launch,
 * what Engineer Client also does is switched off in those mods' configs, so only one of each runs; nothing else in them changes.
 */
object ModTakeover : PreLaunchEntrypoint {
    private val logger = LoggerFactory.getLogger("engineerclient")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Odin modules with the same name and settings as ADM's. */
    private val ADM_NAMES = setOf(
        "Better Party Finder", "Blessing Display", "Blood Camp", "Build Helper", "Camera", "Chat Commands",
        "Command Keybinds", "Croesus", "Door Highlight", "Dungeon Map", "Etherwarp", "Fresh Tools", "Gyro Wand",
        "Invincibility Timer", "Kuudra Info", "Kuudra Tracker", "Leap Menu", "Livid Solver", "Mage Beam", "Melody Message",
        "Mimic", "Misc", "Pearl Waypoints", "Player Display", "Player Size", "Pre-Spot Alert", "Ragnarock", "Remove Perks",
        "Slot Binds", "Spirit Bear", "Splits", "Spring Boots", "Supply Helper", "Terracotta Timer", "Vesuvius",
        "Waypoints", "Wither Dragons",
    )
    private val ODIN_TAKEN = ADM_NAMES + ImportTables.ODIN.keys + setOf("Dungeon Queue", "Tick Timers")

    override fun onPreLaunch() {
        try { run(File(FabricLoader.getInstance().gameDir.toFile(), "config")) } catch (t: Throwable) { logger.warn("[ec] taking over other mods' configs failed", t) }
    }

    private fun run(config: File) {
        val odin = File(config, "odin/odin-config.json")
        val devonian = File(config, "devonianConfig.json")
        val blade = File(config, "blade-addons.json")

        if (!File(config, "engineerclient/config.json").exists()) {
            val saved = File(config, ImportTables.IMPORTED)
            // A copy already there is from a launch that stopped before the import, taken before anything was switched off.
            val kept = listOf(odin, devonian, blade).count { keep(it, File(saved, it.name)) }
            if (kept > 0) File(saved, ImportTables.PENDING).let { if (!it.exists()) it.createNewFile() }
            val adm = File(config, "adm/adm-config.json")
            val odinJson = (read(File(saved, odin.name)) ?: read(odin)) as? JsonArray
            if (!adm.exists() && odinJson != null) {
                val own = JsonArray().apply { odinJson.filter { (it as? JsonObject)?.get("name")?.asString in ADM_NAMES }.forEach(::add) }
                adm.parentFile.mkdirs()
                write(adm, own)
            }
        }

        (read(odin) as? JsonArray)?.let { list ->
            var off = 0
            for (m in list) {
                val o = m as? JsonObject ?: continue
                if (o["name"]?.asString in ODIN_TAKEN && o["enabled"]?.asBoolean == true) { o.addProperty("enabled", false); off++ }
            }
            if (off > 0) { write(odin, list); logger.info("[ec] switched off $off Odin modules Engineer Client has") }
        }
        (read(devonian) as? JsonObject)?.let { root ->
            val c = root.getAsJsonObject("config") ?: return@let
            if (switchOff(c, ImportTables.DEVONIAN.keys) > 0) write(devonian, root)
        }
        (read(blade) as? JsonObject)?.let { root ->
            if (switchOff(root, ImportTables.BLADE.keys) > 0) write(blade, root)
        }
    }

    /** Copies [from] to [to] unless [to] is already there; true when [to] is there afterwards. */
    private fun keep(from: File, to: File): Boolean {
        if (to.isFile) return true
        if (!from.isFile) return false
        return try {
            to.parentFile.mkdirs()
            val tmp = File(to.parentFile, to.name + ".ec-tmp")
            Files.copy(from.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (t: Throwable) {
            logger.warn("[ec] copying ${from.name} failed", t)
            false
        }
    }

    private fun switchOff(json: JsonObject, keys: Set<String>): Int {
        var off = 0
        for (k in keys) {
            val v = json[k] ?: continue
            if (v.isJsonPrimitive && v.asJsonPrimitive.isBoolean && v.asBoolean) { json.addProperty(k, false); off++ }
        }
        return off
    }

    private fun read(file: File): JsonElement? = try { if (file.isFile) JsonParser.parseString(file.readText()) else null } catch (t: Throwable) { null }

    private fun write(file: File, json: JsonElement) {
        val tmp = File(file.parentFile, file.name + ".ec-tmp")
        tmp.writeText(gson.toJson(json))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
