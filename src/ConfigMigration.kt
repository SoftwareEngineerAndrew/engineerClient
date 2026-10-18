package com.engineerclient

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Settings kept across renames and moves; see [run]. Pure file work, so it tests without the game. */
object ConfigMigration {
    /** The name this build keeps its settings and files under: engineerclient here, adm in the public build (promote.sh). */
    private const val NAME = "engineerclient"
    private const val OLD = "engineerclient"

    /**
     * The public build is ADM, which was Engineer Client: there, Engineer Client's Odin settings file is copied to
     * ADM's and its own folder under config (ADR runs, P3 Sim saves, stats...) moved to ADM's, the first time
     * it starts, before anything reads either. Nothing to do where the names are the same. [gameDir] is the game's
     * folder.
     */
    fun rename(gameDir: Path) {
        if (NAME == OLD) return
        val addons = gameDir.resolve("config").resolve("odin").resolve("addons")
        val oldJson = addons.resolve("$OLD.json")
        val newJson = addons.resolve("$NAME.json")
        // Copied through a temp file: a half-written copy would pass the run-once check and never be repaired.
        if (Files.exists(oldJson) && !Files.exists(newJson)) {
            val tmp = newJson.resolveSibling(newJson.fileName.toString() + ".tmp")
            Files.copy(oldJson, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, newJson, StandardCopyOption.ATOMIC_MOVE)
        }
        val oldDir = gameDir.resolve("config").resolve(OLD)
        val newDir = gameDir.resolve("config").resolve(NAME)
        if (Files.isDirectory(oldDir) && !Files.exists(newDir)) Files.move(oldDir, newDir)
    }

    /**
     * One-time moves inside config/odin/addons/engineerclient.json, before Odin reads it, so a
     * module that was renamed or folded into another keeps what was set in it:
     *  - BR Waypoints 2 is BR Roles;
     *  - Better PF is ADR (its runs folder too);
     *  - P3 Sim is P3 Sim Beta;
     *  - Random Stuff's Hide Scoreboard Lines and Skip Loading Screen are Clean Scoreboard and Clean
     *    Loading Screen; Chat Hider is its Clean Chat, and Hub Nametag Stats its Hub Nametag Stats;
     *  - the health and mana settings moved from Random Stuff to Health & Mana (on if Random Stuff
     *    was); Player Display's Health/Mana Bar HUD settings found in Odin's own config are copied
     *    to Health & Mana, which provides those HUDs;
     *  - the Boss Enter Timer (as Clear Countdown), Portal Text and Portal Chime moved from Random
     *    Stuff to Timers (on if Random Stuff was);
     *  - Sub Splits' detail levels: "Extreme" is "Debug", and "Off" is the HUD switched off;
     *  - a former engineerClient Splits HUD that was on becomes Odin's Splits in the Engineer
     *    Splits look (written into Odin's config, read once the look's settings exist - see
     *    OdinSplitsLook.install);
     *  - POV Previews' Show In choice is its matching Only In checkbox (Everywhere: none ticked).
     *  - Sub Splits' Blood Rush Sub Splits HUD is the BR Sub Split;
     *  - Agro Leaderboard is Agro Sphere (on only if its Sphere Mode was);
     *  - BR Roles' Posmsg Re-trigger and Posmsg Here Keybind are Positional Messages' Re-trigger and
     *    Box Here Keybind (Positional Messages itself is
     *    looked after where it is installed);
     *  - Storm Phase's Crush Hitbox is Sub Splits' Show Crush Info (on only if Storm Phase was), its
     *    hitbox settings with it.
     *  - Term Info is gone: its HUD is Odin's Terminals Status (Look ADM, and switched on where ours was if
     *    Odin's wasn't showing), Section Time a Timers HUD, the Core* settings Recore's (without "Core ");
     *  - Sub Splits' Core* settings and Paste Last In Core are Recore's (on if they were in use).
     *  - BR Roles' Doorer Kills is its Door Kills: on is Always, off Never.
     * Each only happens while its target is still missing, so it runs once. [odinDir] is
     * config/odin. True if the file was rewritten.
     */
    fun run(odinDir: Path): Boolean {
        val file = odinDir.resolve("addons").resolve("engineerclient.json")
        val odinFile = odinDir.resolve("odin-config.json")
        if (!Files.exists(file)) return false
        val modules = JsonParser.parseString(Files.readString(file)).asJsonArray
        fun ensure(name: String) = module(modules, name) ?: JsonObject().apply {
            addProperty("name", name); addProperty("enabled", true); add("settings", JsonObject())
        }.also { modules.add(it) }
        var changed = false

        module(modules, "BR Waypoints 2")?.let {
            if (module(modules, "BR Roles") == null) { it.addProperty("name", "BR Roles"); changed = true }
        }

        module(modules, "Better PF")?.let {
            if (module(modules, "ADR") == null) { it.addProperty("name", "ADR"); changed = true }
        }
        // P3 Sim -> P3 Sim Beta -> ADS (Advanced Dungeon Simulator): its settings keep under the new name.
        for (old in listOf("P3 Sim Beta", "P3 Sim")) module(modules, old)?.let {
            if (module(modules, "ADS") == null) { it.addProperty("name", "ADS"); changed = true }
        }

        module(modules, "Random Stuff")?.let(::settings)?.let { rs ->
            for ((old, new) in RANDOM_STUFF_RENAMES) rs.remove(old)?.let { if (!rs.has(new)) rs.add(new, it); changed = true }
        }

        // Modules that became Random Stuff settings: on/off and the settings kept; the module entry goes.
        fun fold(name: String, enabledKey: String, keys: Map<String, String>) {
            val old = module(modules, name) ?: return
            val rs = settings(ensure("Random Stuff"))
            if (!rs.has(enabledKey)) rs.addProperty(enabledKey, old["enabled"]?.asBoolean ?: true)
            val from = settings(old)
            for ((k, to) in keys) from[k]?.let { if (!rs.has(to)) rs.add(to, it) }
            modules.remove(old)
            changed = true
        }
        fold("Chat Hider", "Clean Chat", mapOf("Log Hidden Lines" to "Log Hidden Lines"))
        fold("Hub Nametag Stats", "Hub Nametag Stats", mapOf("PB Floor" to "PB Floor", "Gap" to "Gap"))

        val own = odinDir.parent.resolve(NAME)
        if (Files.isDirectory(own.resolve("betterpf")) && !Files.exists(own.resolve("adr"))) Files.move(own.resolve("betterpf"), own.resolve("adr"))

        module(modules, "Random Stuff")?.let { rs ->
            val from = settings(rs)
            val moved = HEALTH_MANA_KEYS.filter { from.has(it) }
            if (moved.isEmpty() || module(modules, "Health & Mana") != null) return@let
            val to = settings(ensure("Health & Mana").also { it.addProperty("enabled", rs["enabled"]?.asBoolean ?: true) })
            for (k in moved) to.add(k, from.remove(k))
            changed = true
        }

        module(modules, "Random Stuff")?.let { rs ->
            val from = settings(rs)
            val moved = TIMERS_KEYS.filterKeys { from.has(it) }
            if (moved.isEmpty() || module(modules, "Timers") != null) return@let
            val to = settings(ensure("Timers").also { it.addProperty("enabled", rs["enabled"]?.asBoolean ?: true) })
            for ((old, new) in moved) to.add(new, from.remove(old))
            changed = true
        }

        // Agro Leaderboard is Agro Sphere: the list went, so it's on only if its sphere was.
        module(modules, "Agro Leaderboard")?.let {
            if (module(modules, "Agro Sphere") != null) return@let
            val s = settings(it)
            val sphere = s.remove("Sphere Mode")?.takeIf { v -> v.isJsonPrimitive }?.asBoolean ?: true
            s.remove("Agro Leaderboard")
            it.addProperty("name", "Agro Sphere")
            it.addProperty("enabled", (it["enabled"]?.asBoolean ?: true) && sphere)
            changed = true
        }

        // The /posmsg settings moved from BR Roles to Positional Messages.
        module(modules, "BR Roles")?.let(::settings)?.let { br ->
            val moved = POSMSG_KEYS.filterKeys { br.has(it) }
            if (moved.isEmpty()) return@let
            val to = settings(module(modules, "Positional Messages") ?: ensure("Positional Messages").also { it.addProperty("enabled", false) })
            for ((old, new) in moved) br.remove(old).let { if (!to.has(new)) to.add(new, it) }
            changed = true
        }

        // BR Roles' Doorer Kills checkbox is its Door Kills choice.
        module(modules, "BR Roles")?.let(::settings)?.let { br ->
            val old = br.remove("Doorer Kills") ?: return@let
            if (!br.has("Door Kills")) br.addProperty("Door Kills", if (old.isJsonPrimitive && old.asBoolean) "ALWAYS" else "NEVER")
            changed = true
        }

        // The Blood Rush Sub Splits HUD is the BR Sub Split.
        module(modules, "Sub Splits")?.let(::settings)?.let { sub ->
            val old = sub.remove("Blood Rush Sub Splits") ?: return@let
            if (!sub.has("BR Sub Split")) sub.add("BR Sub Split", old)
            changed = true
        }

        // Storm Phase's crush hitbox moved to Sub Splits as Show Crush Info: it drew only with Storm Phase on.
        module(modules, "Storm Phase")?.let { sp ->
            val from = settings(sp)
            val moved = STORM_HITBOX_KEYS.filter { from.has(it) }
            val sub = settings(ensure("Sub Splits"))
            if (sub.has("Show Crush Info")) return@let
            val hitbox = from.remove("Crush Hitbox")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
            sub.addProperty("Show Crush Info", (sp["enabled"]?.asBoolean ?: true) && hitbox)
            for (k in moved) from.remove(k).let { if (!sub.has(k)) sub.add(k, it) }
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

        // POV Previews' one Show In choice became a checkbox per place; the old key goes, so this runs once.
        module(modules, "POV Previews")?.let(::settings)?.let { pov ->
            val old = pov.remove("Show In") ?: return@let
            changed = true
            val key = POV_SHOW_IN[old.takeIf { it.isJsonPrimitive }?.asString] ?: return@let
            if (!pov.has(key)) pov.addProperty(key, true)
        }

        // Term Info is no module any more: its HUD is Odin's Terminals Status with the ADM look, Section Time a Timers
        // HUD (on only if Section Times was), and Core Leap Highlight Recore's. The entry goes, so this runs once.
        module(modules, "Term Info")?.let { ti ->
            val on = ti["enabled"]?.asBoolean ?: true
            val from = settings(ti)
            fun hud(key: String) = from[key]?.takeIf { it.isJsonObject }?.asJsonObject
            fun bool(key: String, fallback: Boolean) = from[key]?.takeIf { it.isJsonPrimitive }?.asBoolean ?: fallback

            if (module(modules, "Recore") == null) {
                val recore = settings(ensure("Recore"))
                for ((k, v) in from.entrySet()) if (k.startsWith("Core ")) recore.add(recoreKey(k), v)
                if (!on) recore.addProperty("Core Leap Highlight", false)
            }

            val timers = settings(ensure("Timers"))
            if (!timers.has("Section Time")) hud("Section Time")?.deepCopy()?.let {
                it.addProperty("enabled", on && bool("Section Times", true) && (it["enabled"]?.asBoolean ?: true))
                timers.add("Section Time", it)
            }
            from["Section Time Seconds"]?.let { if (!timers.has("Section Time Seconds")) timers.add("Section Time Seconds", it) }

            if (Files.exists(odinFile)) {
                val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray
                val ts = module(odin, "Terminals Status")
                    ?: JsonObject().apply { addProperty("name", "Terminals Status"); addProperty("enabled", false) }.also { odin.add(it) }
                val tss = settings(ts)
                val ours = hud("Term Info")?.takeIf { on && it["enabled"]?.asBoolean != false }
                if (!tss.has("Look")) tss.addProperty("Look", if (ours != null) "ADM" else "ODIN")
                if (!tss.has("Simple Mode")) tss.addProperty("Simple Mode", bool("Simple Mode", false))
                // Ours was showing and Odin's wasn't: Odin's on, where ours was.
                val odinsShowing = ts["enabled"]?.asBoolean == true && tss["Term Info"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("enabled")?.asBoolean == true
                if (ours != null && !odinsShowing) {
                    ts.addProperty("enabled", true)
                    tss.add("Term Info", ours.deepCopy())
                }
                write(odinFile, odin)
            }
            modules.remove(ti)
            changed = true
        }

        // Core Leap Highlight and Paste Last In Core went from Sub Splits to Recore (on if either was in use there).
        module(modules, "Sub Splits")?.let { ss ->
            val sub = settings(ss)
            val keys = sub.keySet().filter { it.startsWith("Core ") || it == "Paste Last In Core" }
            if (keys.isEmpty()) return@let
            fun bool(key: String, fallback: Boolean) = sub[key]?.takeIf { it.isJsonPrimitive }?.asBoolean ?: fallback
            val subOn = ss["enabled"]?.asBoolean ?: true
            // Made by the Term Info move just above (an install from before Sub Splits had them) or by Recore itself: kept as it is.
            val recore = module(modules, "Recore") ?: ensure("Recore").also {
                it.addProperty("enabled", subOn && (bool("Core Leap Highlight", true) || bool("Paste Last In Core", false)))
                if (!subOn) settings(it).addProperty("Core Leap Highlight", false)
            }
            val to = settings(recore)
            for (k in keys) sub.remove(k).let { if (!to.has(recoreKey(k))) to.add(recoreKey(k), it) }
            changed = true
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
                odinSplits.addProperty("Look", "ENGINEER_SPLITS")
                write(odinFile, odin)
                changed = true
            }
        }

        if (changed) {
            Files.createDirectories(file.parent)
            write(file, modules)
        }
        return changed
    }

    /** A Core Leap setting's name in Recore: "Core Style" is "Style" there; Core Leap Highlight keeps its name. */
    private fun recoreKey(key: String) = if (key == "Core Leap Highlight" || !key.startsWith("Core ")) key else key.removePrefix("Core ")

    private val RANDOM_STUFF_RENAMES = mapOf("Hide Scoreboard Lines" to "Clean Scoreboard", "Skip Loading Screen" to "Clean Loading Screen", "Hide Numbers in Numbers" to "Hide Numbers' Numbers")

    private val STORM_HITBOX_KEYS = listOf("Hitbox Seconds", "Hitbox Margins", "Pillar Outline", "Inside Color", "Outside Color")

    private val POSMSG_KEYS = mapOf("Posmsg Re-trigger" to "Re-trigger", "Posmsg Here Keybind" to "Box Here Keybind")

    private val TIMERS_KEYS = mapOf("Boss Enter Timer" to "Clear Countdown", "Portal Text" to "Portal Text", "Portal Chime" to "Portal Chime")

    private val HEALTH_MANA_KEYS = listOf(
        "Hide Health/Mana Above %", "Threshold", "Health Bar HUD", "Health Bar Width", "Health Bar Height",
        "Mana Bar HUD", "Mana Bar Width", "Mana Bar Height",
    )

    private val POV_SHOW_IN = mapOf(
        "ONLY_IN_BLOOD_RUSH" to "Only In Blood Rush", "ONLY_IN_BOSS" to "Only In Boss", "ONLY_IN_GOLDOR" to "Only In Goldor",
    )

    /** Through a temp file and an atomic move, so a crash mid-write can't leave the user's settings truncated. */
    private fun write(file: Path, json: JsonArray) {
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, GsonBuilder().setPrettyPrinting().create().toJson(json))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun module(list: JsonArray, name: String): JsonObject? =
        list.firstOrNull { it.isJsonObject && it.asJsonObject["name"]?.asString == name }?.asJsonObject

    private fun settings(m: JsonObject): JsonObject =
        m.getAsJsonObject("settings") ?: JsonObject().also { m.add("settings", it) }
}
