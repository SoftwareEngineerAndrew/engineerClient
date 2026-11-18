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

/**
 * Runs before any mod reads its settings. On a fresh install Odin's, Devonian's and Blade Addons' configs are moved aside to
 * config/odin-moved, devonian-moved and blade-moved for [com.engineerclient.misc.ModImport], and Odin's settings for ADM's modules become ADM's. Every launch,
 * what Engineer Client also does is switched off in those mods' configs, so only one of each runs.
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
            val adm = File(config, "adm/adm-config.json")
            val odinJson = read(odin) as? JsonArray
            if (!adm.exists() && odinJson != null) {
                val own = JsonArray().apply { odinJson.filter { (it as? JsonObject)?.get("name")?.asString in ADM_NAMES }.forEach(::add) }
                adm.parentFile.mkdirs()
                write(adm, own)
            }
            moveAside(config, "odin-moved", listOf(File(config, "odin")))?.let { imported[odin.name] = File(it, odin.name) }
            moveAside(config, "devonian-moved", listOf(devonian, File(config, "devonian")))?.let { imported[devonian.name] = File(it, devonian.name) }
            moveAside(config, "blade-moved", listOf(blade, File(config, "blade-addons"), File(config, "blade-addons-waypoints.json")))?.let { imported[blade.name] = File(it, blade.name) }
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

    /** Where each mod's config went this launch, by its file name. */
    val imported = mutableMapOf<String, File>()

    // Moved rather than edited or deleted, so the player keeps them as they were.
    private fun moveAside(config: File, name: String, files: List<File>): File? {
        val present = files.filter { it.exists() }
        if (present.isEmpty()) return null
        var dir = File(config, name)
        var n = 2
        while (dir.exists()) dir = File(config, "$name-${n++}")
        dir.mkdirs()
        for (f in present) try {
            java.nio.file.Files.move(f.toPath(), File(dir, f.name).toPath())
        } catch (t: Throwable) {
            logger.warn("[ec] moving ${f.name} failed", t)
        }
        logger.info("[ec] moved ${present.joinToString { it.name }} to config/${dir.name}")
        return dir
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
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
