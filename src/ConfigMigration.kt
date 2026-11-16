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

     *  - Storm Phase's Crush Hitbox is Sub Splits' Show Crush Info (on only if Storm Phase was), its

     *    hitbox settings with it.

     *  - Term Info is gone: its HUD is Odin's Terminals Status (switched on where ours was if Odin's

     *    wasn't showing), Section Time a Timers HUD, the Core* settings Recore's (without "Core ");

     *  - Sub Splits' Core* settings and Paste Last In Core are Recore's (on if they were in use).

     *  - BR Roles' Doorer Kills is its Door Kills: on is Always, off Never.

     *  - Legit Zpew is Etherwarp's Legit Zpew setting, in Odin's config.

     *  - Dungeon Breaker Torch Guard and Odin's Breaker Display are Dungeonbreaker.

     *  - Timers' Necron Drop HUD is its Necron DPS (where it was, on or off as it was).

     *  - ADS's Shortbow Cooldown is its Shortbow Shot Cooldown (the wrong default 2 dropped, so it reads 5).

     *  - ADR's Private Runs is its Unlisted Runs (2026-10-30, ADM 1.0.10).

     *  - Engineer Client's Terminals Status goes (2026-11-06).

     *  - POV Previews' Sharing FPS is its Sharing FPS ❗ (2026-11-11).

     *  - Sound Manager and Mono Audio are Sounds.

     *  - World Border Click Fix and Shadow Assassin Jump Hider are Shadow Assassin.

     *  - Arrow Fix, Arrow Swap Title, Arrow Hit Sound, Odin's Quiver Display and the arrow settings of Render Cleanup

     *    and Random Stuff are Arrows.

     *  - Random Stuff's Party Finder Classes is Better Party Finder's Show Missing List, in Odin's config.

     *  - Mob Highlight's Teammate Class Glow goes, in Odin's config.

     *  - Screen Tweaks, Render Cleanup, Chat Tweaks and Tab Tweaks are Visibility.

     *  - Phase Timers, Timers and Odin's Tick Timers (its Fire Freeze Hud and look) are Tick Timers.

     *  - Odin's terminal modules, Term Progress and Terminal Hitboxes are Terminals.

     *  - Odin's Simon Says, Arrow Align and Arrows Device, and SS Practice, are Devices.

     *  - Odin's Dungeon Waypoints, Boss Waypoints and Odin's Temporary Waypoints (its From Party Chat) are Waypoints.

     *  - Odin's Secret Clicked, Waypoints' Secret Waypoints, Item Highlight and Close Chest On Key are Secrets.

     *  - Odin's Map Info and Extra Stats, Score Time, Room Alerts and Clear Breakdown are Clear Info.

     *  - Random Stuff's visibility settings are Visibility's, and Hide Numbers' Numbers and Show Next Numbers are Terminals'.

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



        for (old in listOf("Shadow Assassin Jump Hider", "World Border Click Fix")) module(modules, old)?.let {

            if (module(modules, "Shadow Assassin") == null) { it.addProperty("name", "Shadow Assassin"); changed = true }

        }



        // Zpew -> Legit Zpew: its settings keep under the new name.

        module(modules, "Zpew")?.let {

            if (module(modules, "Legit Zpew") == null) { it.addProperty("name", "Legit Zpew"); changed = true }

        }

        // Legit Zpew's Teleport became AOTE / AOTV and Hyperion: both take its value.

        module(modules, "Legit Zpew")?.let {

            val s = settings(it)

            val old = s.remove("Teleport") ?: return@let

            for (k in listOf("AOTE / AOTV", "Hyperion")) if (!s.has(k)) s.add(k, old)

            changed = true

        }



        // Legit Zpew is Etherwarp's Legit Zpew setting; Etherwarp is switched on where it wasn't, with its guess box off.

        module(modules, "Legit Zpew")?.let { lz ->

            if (!Files.exists(odinFile)) return@let

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val ew = module(odin, "Etherwarp") ?: JsonObject().apply { addProperty("name", "Etherwarp"); addProperty("enabled", false) }.also { odin.add(it) }

            val es = settings(ew)

            if (!es.has("Legit Zpew")) {

                val on = lz["enabled"]?.asBoolean ?: false

                es.addProperty("Legit Zpew", on)

                for ((k, v) in settings(lz).entrySet()) if (!es.has(k)) es.add(k, v)

                if (on && ew["enabled"]?.asBoolean != true) { ew.addProperty("enabled", true); es.addProperty("Show Guess", false) }

                write(odinFile, odin)

            }

            modules.remove(lz)

            changed = true

        }



        // Dungeon Breaker Torch Guard and Odin's Breaker Display are Dungeonbreaker's Torch Guard and Charges Display.

        run {

            val tg = module(modules, "Dungeon Breaker Torch Guard")

            val odin = if (Files.exists(odinFile)) JsonParser.parseString(Files.readString(odinFile)).asJsonArray else null

            val bd = odin?.let { module(it, "Breaker Display") }

            if (tg == null && bd == null) return@run

            if (module(modules, "Dungeonbreaker") == null) {

                val tgOn = tg?.get("enabled")?.asBoolean ?: false

                val bdOn = bd?.get("enabled")?.asBoolean ?: false

                val s = settings(ensure("Dungeonbreaker").also { it.addProperty("enabled", tgOn || bdOn) })

                s.addProperty("Torch Guard", tgOn)

                s.addProperty("Charges Display", bdOn)

                bd?.let { for ((k, v) in settings(it).entrySet()) if (!s.has(k)) s.add(k, v) }

            }

            tg?.let { modules.remove(it) }

            if (odin != null && bd != null) { odin.remove(bd); write(odinFile, odin) }

            changed = true

        }



        // ADS's Shortbow Cooldown is its Shortbow Shot Cooldown; 2 (a wrong default from 2026-10-07: shots every 2 ticks)

        // becomes the recorded 5, any other value is kept. The old key goes, so this runs once.

        module(modules, "ADS")?.let(::settings)?.let { ads ->

            val old = ads.remove("Shortbow Cooldown") ?: return@let

            changed = true

            if (!ads.has("Shortbow Shot Cooldown") && old.isJsonPrimitive && old.asDouble != 2.0) ads.add("Shortbow Shot Cooldown", old)

        }



        // ADR's Private Runs is its Unlisted Runs. The choice comes with it, so the once-per-install site check that puts

        // it back where the saved choice was already lost (ADR.checkUnlisted) is marked done. The old key goes, so this runs once.

        module(modules, "ADR")?.let(::settings)?.let { adr ->

            val old = adr.remove("Private Runs") ?: return@let

            changed = true

            if (adr.has("Unlisted Runs")) return@let

            adr.add("Unlisted Runs", old)

            adr.addProperty("Unlisted Runs Checked", true)

        }



        // POV Previews' Sharing FPS is its Sharing FPS ❗: the value comes with it. The old key goes, so this runs once.

        module(modules, "POV Previews")?.let(::settings)?.let { pov ->

            val old = pov.remove("Sharing FPS") ?: return@let

            changed = true

            if (!pov.has("Sharing FPS ❗")) pov.add("Sharing FPS ❗", old)

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



        // BR Roles' Doorer Kills checkbox is its Door Kills choice.

        module(modules, "BR Roles")?.let(::settings)?.let { br ->

            val old = br.remove("Doorer Kills") ?: return@let

            if (!br.has("Door Kills")) br.addProperty("Door Kills", if (old.isJsonPrimitive && old.asBoolean) "ALWAYS" else "NEVER")

            changed = true

        }



        // Timers' Necron Drop countdown became Necron DPS: the HUD keeps its place.

        module(modules, "Timers")?.let(::settings)?.let { t ->

            val old = t.remove("Necron Drop") ?: return@let

            if (!t.has("Necron DPS")) t.add("Necron DPS", old)

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



        // Term Info is no module any more: its HUD is Odin's Terminals Status, Section Time a Timers

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



        // Engineer Client's Terminals Status is gone (2026-11-06; Odin's own, in odin-config.json, is another module).

        val gone = module(modules, "Terminals Status")

        if (gone != null) { modules.remove(gone); changed = true }



        // Sound Manager and Mono Audio are Sounds: on if either was, volumes only from an enabled Sound Manager.

        val soundManager = module(modules, "Sound Manager")

        val monoAudio = module(modules, "Mono Audio")

        if ((soundManager != null || monoAudio != null) && module(modules, "Sounds") == null) {

            val smOn = soundManager?.get("enabled")?.asBoolean == true

            val monoOn = monoAudio?.get("enabled")?.asBoolean == true

            val to = settings(ensure("Sounds").also { it.addProperty("enabled", smOn || monoOn) })

            to.addProperty("Mono Audio", monoOn)

            if (smOn) soundManager?.let(::settings)?.get("Volumes")?.let { to.add("Volumes", it) }

            soundManager?.let(modules::remove)

            monoAudio?.let(modules::remove)

            changed = true

        }



        // Name Changer, Server ID Hider and Nametag Tweaks are Scoreboard and Nametags, on if any was.

        run {

            val names = module(modules, "Name Changer")

            val server = module(modules, "Server ID Hider")

            val tags = module(modules, "Nametag Tweaks")

            if (listOf(names, server, tags).all { it == null } || module(modules, "Scoreboard and Nametags") != null) return@run

            fun on(m: JsonObject?) = m?.get("enabled")?.asBoolean == true

            val to = settings(ensure("Scoreboard and Nametags").also { it.addProperty("enabled", on(names) || on(server) || on(tags)) })

            to.addProperty("Name Changer", on(names))

            to.addProperty("Server ID Hider", on(server))

            for (m in listOf(names, server)) m?.let(::settings)?.entrySet()?.forEach { (k, v) -> if (k != "Keybind") to.add(k, v) }

            if (on(tags)) settings(tags!!).entrySet().forEach { (k, v) -> if (k != "Keybind") to.add(k, v) }

            listOfNotNull(names, server, tags).forEach(modules::remove)

            changed = true

        }



        // Goldor Helper is Positional Messages; Melody Alert is Melody Message's, Current Section and Section Progress

        // Terminals Status's. Terminals Status is turned on for them with its waypoints off when it was off.

        module(modules, "Goldor Helper")?.let { gh ->

            if (module(modules, "Positional Messages") != null) return@let

            val on = gh.get("enabled")?.asBoolean == true

            val from = settings(gh)

            if (Files.exists(odinFile)) {

                val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

                fun host(name: String) = module(odin, name) ?: JsonObject().apply { addProperty("name", name); addProperty("enabled", false); add("settings", JsonObject()) }.also { odin.add(it) }

                fun hud(key: String) = from.get(key)?.deepCopy()?.also { if (!on && it.isJsonObject) it.asJsonObject.addProperty("enabled", false) }

                val melody = host("Melody Message")

                hud("Melody Alert")?.let { settings(melody).add("Melody Alert", it) }

                if (on && melody.get("enabled")?.asBoolean != true) melody.addProperty("enabled", true)

                val ts = host("Terminals Status")

                val ts2 = settings(ts)

                for (k in listOf("Current Section", "Section Progress")) hud(k)?.let { ts2.add(k, it) }

                from.get("Color Progress")?.let { ts2.add("Color Progress", it) }

                if (on && ts.get("enabled")?.asBoolean != true) { ts.addProperty("enabled", true); ts2.addProperty("Waypoints", false); (ts2.get("Term Info") as? JsonObject)?.addProperty("enabled", false) }

                write(odinFile, odin)

            }

            for (k in listOf("Melody Alert", "Current Section", "Section Progress", "Color Progress")) from.remove(k)

            gh.addProperty("name", "Positional Messages")

            changed = true

        }



        // Freeze Display is Lag Warning, its HUD too.

        module(modules, "Freeze Display")?.let { fd ->

            if (module(modules, "Lag Warning") != null) return@let

            fd.addProperty("name", "Lag Warning")

            settings(fd).remove("Freeze Display")?.let { settings(fd).add("Lag Warning", it) }

            changed = true

        }



        // POV Previews is a section of Leap Menu: its settings move there and its switch is Show POV Previews.

        module(modules, "POV Previews")?.let { pov ->

            if (!Files.exists(odinFile)) return@let

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val leap = module(odin, "Leap Menu") ?: JsonObject().apply { addProperty("name", "Leap Menu"); addProperty("enabled", false) }.also { odin.add(it) }

            val to = settings(leap)

            if (to.has("Show POV Previews")) return@let

            to.addProperty("Show POV Previews", pov.get("enabled")?.asBoolean == true)

            for ((k, v) in settings(pov).entrySet()) if (k != "Keybind" && !to.has(k)) to.add(k, v)

            write(odinFile, odin)

        }



        // Highlight is Mob Highlight.

        if (Files.exists(odinFile)) {

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val old = module(odin, "Highlight")

            if (old != null && module(odin, "Mob Highlight") == null) { old.addProperty("name", "Mob Highlight"); write(odinFile, odin) }

        }



        // Puzzle Solvers is Puzzles; Creeper Beams Ding and Puzzle Overlay are its settings.

        if (Files.exists(odinFile)) {

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            var odinChanged = false

            val old = module(odin, "Puzzle Solvers")

            if (old != null && module(odin, "Puzzles") == null) { old.addProperty("name", "Puzzles"); odinChanged = true }

            val ding = module(modules, "Creeper Beams Ding")

            val overlay = module(modules, "Puzzle Overlay")

            if (ding != null || overlay != null) {

                val puzzles = module(odin, "Puzzles") ?: JsonObject().apply { addProperty("name", "Puzzles"); addProperty("enabled", true) }.also { odin.add(it) }

                val to = settings(puzzles)

                ding?.let { d ->

                    to.addProperty("Creeper Beams Ding", d.get("enabled")?.asBoolean == true)

                    for ((k, v) in settings(d).entrySet()) when (k) {

                        "Keybind" -> {}

                        "Remove Creeper Hurt", "Remove Explosion" -> to.add(k, v)

                        else -> to.add("Ding $k", v)

                    }

                    modules.remove(d)

                }

                overlay?.let { o ->

                    val on = o.get("enabled")?.asBoolean == true

                    for ((k, v) in settings(o).entrySet()) when (k) {

                        "Keybind" -> {}

                        "Puzzle Overlay" -> to.add(k, v.deepCopy().also { if (!on && it.isJsonObject) it.asJsonObject.addProperty("enabled", false) })

                        "Style", "Player Names" -> to.add("Overlay $k", v)

                        else -> to.add(k, v)

                    }

                    modules.remove(o)

                }

                odinChanged = true

                changed = true

            }

            if (odinChanged) write(odinFile, odin)

        }



        // Sound Tweaks is Sounds settings, each on only if the module was; Sounds turns on with it.

        module(modules, "Sound Tweaks")?.let { st ->

            val on = st.get("enabled")?.asBoolean != false

            val fresh = module(modules, "Sounds") == null

            val sounds = ensure("Sounds")

            if (on || fresh) sounds.addProperty("enabled", on)

            val to = settings(sounds)

            val from = settings(st)

            for (k in listOf("Mute Cooldown Sound", "Old Tuba Sound", "Old Ragnarock Sound"))

                to.addProperty(k, on && (from.get(k)?.asBoolean ?: true))

            modules.remove(st)

            changed = true

        }



        // Bonzo Sound is a Sounds setting; Sounds turns on with it.

        module(modules, "Bonzo Sound")?.let { b ->

            val on = b.get("enabled")?.asBoolean == true

            val fresh = module(modules, "Sounds") == null

            val sounds = ensure("Sounds")

            if (on || fresh) sounds.addProperty("enabled", on)

            val to = settings(sounds)

            to.addProperty("Bonzo Sound", on)

            for ((k, v) in settings(b).entrySet()) if (k != "Keybind") to.add("Bonzo $k", v)

            modules.remove(b)

            changed = true

        }



        // Arrow Fix, Arrow Swap Title, Arrow Hit Sound and Odin's Quiver Display are Arrows settings, on if the module was; Render Cleanup's

        // and Random Stuff's arrow settings too, on only if their module was.

        run {

            if (module(modules, "Arrows") != null) return@run

            val odin = if (Files.exists(odinFile)) JsonParser.parseString(Files.readString(odinFile)).asJsonArray else null

            val quiver = odin?.let { module(it, "Quiver Display") }

            val fix = module(modules, "Arrow Fix")

            val swap = module(modules, "Arrow Swap Title")

            val hit = module(modules, "Arrow Hit Sound")

            val rc = module(modules, "Render Cleanup")

            val rs = module(modules, "Random Stuff")

            if (listOf(quiver, fix, swap, hit, rc, rs).all { it == null }) return@run

            fun on(m: JsonObject?, fallback: Boolean = false) = m?.get("enabled")?.asBoolean ?: fallback

            val to = settings(ensure("Arrows"))

            to.addProperty("No Shortbow Pullback", on(fix))

            to.addProperty("Quiver Display", on(quiver))

            quiver?.let(::settings)?.entrySet()?.forEach { (k, v) -> to.add(k, v) }

            if (swap != null) {

                val hud = settings(swap)["Arrow Swap Title"]?.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() ?: JsonObject()

                hud.addProperty("enabled", on(swap, true))

                to.add("Arrow Swap Title", hud)

            }

            to.addProperty("Arrow Hit Sound", on(hit))

            hit?.let(::settings)?.let { s -> for (k in listOf("Sound", "Sound Pitch", "Sound Volume")) s[k]?.let { to.add("Hit $k", it) } }

            for ((m, keys) in listOf(rc to listOf("Hide Grounded Arrows", "Hide Stuck Arrows"), rs to listOf("Hide Own Arrows Nearby"))) {

                val from = m?.let(::settings) ?: continue

                for (k in keys) from.remove(k)?.takeIf { it.isJsonPrimitive }?.let { to.addProperty(k, on(m) && it.asBoolean) }

            }

            listOfNotNull(fix, swap, hit).forEach(modules::remove)

            if (odin != null && quiver != null) { odin.remove(quiver); write(odinFile, odin) }

            changed = true

        }



        // Screen Tweaks, Render Cleanup, Chat Tweaks and Tab Tweaks are Visibility, on if any of them was. With it on, the switches of

        // a module that was off go off, so nothing shows up that wasn't. The old entries go, so this runs once.

        run {

            val folded = VISIBILITY_SWITCHES.keys.mapNotNull { module(modules, it) }

            if (folded.isEmpty()) return@run

            fun on(m: JsonObject) = m["enabled"]?.asBoolean == true

            val existing = module(modules, "Visibility")

            val anyOn = existing?.let(::on) == true || folded.any(::on)

            val to = settings((existing ?: ensure("Visibility")).also { it.addProperty("enabled", anyOn) })

            for (m in folded) {

                val name = m["name"].asString

                for ((k, v) in settings(m).entrySet()) if ((k != "Keybind" || name == "Screen Tweaks") && !to.has(k)) to.add(k, v)

                if (anyOn && !on(m)) for (k in VISIBILITY_SWITCHES.getValue(name)) to.addProperty(k, false)

            }

            folded.forEach(modules::remove)

            changed = true

        }



        // Random Stuff's Party Finder Classes is Better Party Finder's Show Missing List; where it was showing and Better Party

        // Finder was off, that module is switched on without its join messages. The old key goes, so this runs once.

        module(modules, "Random Stuff")?.let { rs ->

            val old = settings(rs).remove("Party Finder Classes") ?: return@let

            changed = true

            if (!Files.exists(odinFile)) return@let

            val on = (rs["enabled"]?.asBoolean ?: true) && old.isJsonPrimitive && old.asBoolean

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val bpf = module(odin, "Better Party Finder") ?: JsonObject().apply { addProperty("name", "Better Party Finder"); addProperty("enabled", false) }.also { odin.add(it) }

            val s = settings(bpf)

            if (s.has("Show Missing List")) return@let

            s.addProperty("Show Missing List", on)

            if (on && bpf["enabled"]?.asBoolean != true) {

                bpf.addProperty("enabled", true)

                s.addProperty("Stats display", false)

                s.addProperty("Send Kick Line", false)

            }

            write(odinFile, odin)

        }



        // Mob Highlight's Teammate Class Glow goes.

        if (Files.exists(odinFile)) {

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            if (module(odin, "Mob Highlight")?.let(::settings)?.remove("Teammate Class Glow") != null) write(odinFile, odin)

        }



        // Phase Timers, Timers and Odin's Tick Timers are Tick Timers, on if any was; Odin's other timers were already in the first two.

        // With it on, the timers of one that was off go off. HUDs linked to theirs follow. The old entries go, so this runs once.

        run {

            val odin = if (Files.exists(odinFile)) JsonParser.parseString(Files.readString(odinFile)).asJsonArray else null

            val phase = module(modules, "Phase Timers")

            val timers = module(modules, "Timers")

            val odinTicks = odin?.let { module(it, "Tick Timers") }

            if (listOf(phase, timers, odinTicks).all { it == null } || module(modules, "Tick Timers") != null) return@run

            fun on(m: JsonObject?) = m?.get("enabled")?.asBoolean == true

            fun hudOff(s: JsonObject, key: String) = (s[key] as? JsonObject ?: JsonObject().also { s.add(key, it) }).addProperty("enabled", false)

            val anyOn = on(phase) || on(timers) || on(odinTicks)

            val to = settings(ensure("Tick Timers").also { it.addProperty("enabled", anyOn) })

            for (m in listOfNotNull(phase, timers)) for ((k, v) in settings(m).entrySet()) if (!to.has(k)) to.add(k, v)

            odinTicks?.let(::settings)?.let { s -> for (k in ODIN_TICK_TIMERS_KEYS) s[k]?.let { to.add(k, it) } }

            if (anyOn) {

                if (!on(phase)) for (k in PHASE_TIMERS_SWITCHES) to.addProperty(k, false)

                if (!on(timers)) for (k in TIMERS_HUDS) hudOff(to, k)

                if (!on(odinTicks)) hudOff(to, "Fire Freeze Hud")

            }

            listOfNotNull(phase, timers).forEach(modules::remove)

            fun relink(list: JsonArray) = list.filterIsInstance<JsonObject>().flatMap { it.getAsJsonObject("settings")?.entrySet()?.map { e -> e.value }.orEmpty() }.filterIsInstance<JsonObject>().forEach { hud ->

                val link = hud["link"]?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach

                for (old in listOf("Phase Timers/", "Timers/")) if (link.startsWith(old)) hud.addProperty("link", "Tick Timers/" + link.removePrefix(old))

            }

            relink(modules)

            if (odin != null) {

                odinTicks?.let(odin::remove)

                relink(odin)

                write(odinFile, odin)

            }

            changed = true

        }



        // Odin's terminal modules, Term Progress and Terminal Hitboxes are Terminals' sections, each on if its module was.

        run {

            if (module(modules, "Terminals") != null) return@run

            val odin = if (Files.exists(odinFile)) JsonParser.parseString(Files.readString(odinFile)).asJsonArray else null

            val olds = TERMINAL_SECTIONS.mapNotNull { (name, switch, inOdin) -> (if (inOdin) odin?.let { module(it, name) } else module(modules, name))?.let { Triple(it, switch, inOdin) } }

            if (olds.isEmpty()) return@run

            val to = settings(ensure("Terminals"))

            for ((m, switch, _) in olds) {

                if (switch != null) to.addProperty(switch, m["enabled"]?.asBoolean ?: false)

                for ((k, v) in settings(m).entrySet()) {

                    if (k in TERMINAL_DROPPED) continue

                    when (k) {

                        "Render type" -> to.addProperty("Solver Style", if (v.isJsonPrimitive && v.asString == "CUSTOM_GUI") "CUSTOM" else "VANILLA")

                        "Stop Melody Solver" -> to.addProperty("Melody Solver", !(v.isJsonPrimitive && v.asBoolean))

                        else -> { val name = TERMINAL_RENAMES[k] ?: k; if (!to.has(name)) to.add(name, v) }

                    }

                }

            }

            fun relink(list: JsonArray) = list.filterIsInstance<JsonObject>().flatMap { it.getAsJsonObject("settings")?.entrySet()?.map { e -> e.value }.orEmpty() }.filterIsInstance<JsonObject>().forEach { hud ->

                val link = hud["link"]?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach

                for ((old, _, _) in TERMINAL_SECTIONS) if (link.startsWith("$old/")) hud.addProperty("link", "Terminals/" + link.removePrefix("$old/"))

            }

            for ((m, _, inOdin) in olds) if (!inOdin) modules.remove(m)

            relink(modules)

            if (odin != null) {

                for ((m, _, inOdin) in olds) if (inOdin) odin.remove(m)

                relink(odin)

                write(odinFile, odin)

            }

            changed = true

        }



        // Dungeon Waypoints is Waypoints, on if it, Boss Waypoints or Temporary Waypoints was; Party Waypoints is Temporary

        // Waypoints' From Party Chat where it was on. The old entries go, so this runs once.

        run {

            val odin = if (Files.exists(odinFile)) JsonParser.parseString(Files.readString(odinFile)).asJsonArray else JsonArray()

            val dw = module(odin, "Dungeon Waypoints")

            val tw = module(odin, "Temporary Waypoints")

            val bw = module(modules, "Boss Waypoints")

            if (listOf(dw, tw, bw).all { it == null } || module(odin, "Waypoints") != null) return@run

            fun on(m: JsonObject?) = m?.get("enabled")?.asBoolean == true

            val w = dw ?: JsonObject().apply { addProperty("name", "Waypoints"); add("settings", JsonObject()) }.also { odin.add(it) }

            w.addProperty("name", "Waypoints")

            w.addProperty("enabled", on(dw) || on(bw) || on(tw))

            tw?.let { settings(w).addProperty("Party Waypoints", on(it) && (settings(it)["From Party Chat"]?.asBoolean ?: true)) }

            tw?.let(odin::remove)

            write(odinFile, odin)

            bw?.let { modules.remove(it); changed = true }

        }



        // SS Practice is Devices' SS Practice section, on if it was (its Size is Practice Size).

        module(modules, "SS Practice")?.let { ssp ->

            val to = settings(ensure("Devices"))

            if (!to.has("Enable SS Practice")) to.addProperty("Enable SS Practice", ssp["enabled"]?.asBoolean ?: true)

            for ((k, v) in settings(ssp).entrySet()) { val key = if (k == "Size") "Practice Size" else k; if (!to.has(key)) to.add(key, v) }

            modules.remove(ssp)

            changed = true

        }



        // Odin's Simon Says, Arrow Align and Arrows Device are Devices' sections, each on if its module was.

        run {

            if (settings(module(modules, "Devices") ?: JsonObject()).has("Enable Simon Says") || !Files.exists(odinFile)) return@run

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val olds = DEVICE_SECTIONS.mapNotNull { (name, switch) -> module(odin, name)?.let { Triple(it, name, switch) } }

            if (olds.isEmpty()) return@run

            val to = settings(ensure("Devices"))

            for ((m, name, switch) in olds) {

                to.addProperty(switch, m["enabled"]?.asBoolean ?: false)

                for ((k, v) in settings(m).entrySet()) {

                    if (k in DEVICE_DROPPED) continue

                    val key = DEVICE_RENAMES["$name/$k"] ?: k

                    if (!to.has(key)) to.add(key, v)

                }

                odin.remove(m)

            }

            write(odinFile, odin)

            changed = true

        }



        // Secret Clicked is Secrets, on if it, Item Highlight, Close Chest On Key or Waypoints' Secret Waypoints was; each folded

        // one's switch is whether it was on. With Secrets on, what was off goes off, so nothing shows up that wasn't. The old

        // entries go, so this runs once.

        run {

            if (!Files.exists(odinFile)) return@run

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            if (module(odin, "Secrets") != null) return@run

            fun on(m: JsonObject?) = m?.get("enabled")?.asBoolean == true

            val sc = module(odin, "Secret Clicked")

            val ih = module(modules, "Item Highlight")

            val cc = module(modules, "Close Chest On Key")

            val wp = module(odin, "Waypoints")

            val sw = wp?.let(::settings)?.let { w -> SECRET_WAYPOINT_KEYS.filter(w::has).associateWith { w.remove(it) } }.orEmpty()

            if (sc == null && ih == null && cc == null && sw.isEmpty()) return@run

            val swOn = on(wp) && sw["Secret Waypoints"]?.let { it.isJsonPrimitive && it.asBoolean } == true

            val anyOn = on(sc) || on(ih) || on(cc) || swOn

            val secrets = sc ?: JsonObject().apply { add("settings", JsonObject()) }.also { odin.add(it) }

            secrets.addProperty("name", "Secrets")

            secrets.addProperty("enabled", anyOn)

            val to = settings(secrets)

            to.remove("Secret Boxes Dropdown")

            to.remove("Secret Chime Dropdown")

            if (anyOn && !on(sc)) { to.addProperty("Secret Boxes", false); to.addProperty("Secret Chime", false) }

            for ((k, v) in sw) if (k != "Secret Waypoint Settings" && !to.has(k)) to.add(k, v)

            if (anyOn && !swOn && sw.isNotEmpty()) to.addProperty("Secret Waypoints", false)

            to.addProperty("Item Highlight", on(ih))

            ih?.let(::settings)?.let { s -> for (k in ITEM_HIGHLIGHT_KEYS) s[k]?.let { if (!to.has(k)) to.add(k, it) } }

            to.addProperty("Close Chest On Key", on(cc))

            write(odinFile, odin)

            listOfNotNull(ih, cc).forEach { modules.remove(it); changed = true }

        }



        // Mob Highlight is Highlights.

        if (Files.exists(odinFile)) {

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val old = module(odin, "Mob Highlight")

            if (old != null && module(odin, "Highlights") == null) { old.addProperty("name", "Highlights"); write(odinFile, odin) }

        }



        // Odin's Map Info and Extra Stats, Score Time, Room Alerts and Clear Breakdown are Clear Info's sections, each switch on if its

        // module was. Map Info's Full Hud is its Map Info HUD; the two sounds and Score Time's Alert get the section's name.

        run {

            if (module(modules, "Clear Info") != null) return@run

            val odin = if (Files.exists(odinFile)) JsonParser.parseString(Files.readString(odinFile)).asJsonArray else JsonArray()

            val mi = module(odin, "Map Info")

            val es = module(odin, "Extra Stats")

            val st = module(modules, "Score Time")

            val ra = module(modules, "Room Alerts")

            val cb = module(modules, "Clear Breakdown")

            if (listOf(mi, es, st, ra, cb).all { it == null }) return@run

            fun on(m: JsonObject?) = m?.get("enabled")?.asBoolean == true

            val to = settings(ensure("Clear Info"))

            fun copy(m: JsonObject?, keys: Map<String, String>) {

                val from = m?.let(::settings) ?: return

                for ((k, key) in keys) from[k]?.let { if (!to.has(key)) to.add(key, it) }

            }

            fun sound(prefix: String) = listOf("Sound", "Sound Id", "Sound Pitch", "Sound Volume").associateWith { prefix + it }

            to.addProperty("Enable Map Info", on(mi))

            copy(mi, mapOf("Full Hud" to "Map Info HUD", "Disable in boss" to "Disable in boss", "300 Score Alert" to "300 Score Alert",

                "Highlight Portal" to "Highlight Portal", "Paul Score" to "Paul Score"))

            to.addProperty("Enable Score Time", on(st))

            copy(st, mapOf("S Score" to "S Score", "Min Secrets" to "Min Secrets", "Alert" to "Score Time Title") + sound("Score Time "))

            to.addProperty("Enable Room Alerts", on(ra))

            copy(ra, listOf("Cleared", "Secrets Done", "Display Time", "Key Rooms", "No Blood Room").associateWith { it } + sound("Room Alert "))

            to.addProperty("Enable Extra Stats", on(es))

            copy(es, listOf("Show Bits", "Show Class EXP", "Show Combat Stats", "Show Team Stats", "Show Teammates").associateWith { it })

            to.addProperty("Enable Clear Breakdown", on(cb))

            to.addProperty("Secrets Counter", on(cb) && cb?.let(::settings)?.get("Secrets Counter")?.let { it.isJsonPrimitive && it.asBoolean } == true)

            module(modules, "Clear Info")?.addProperty("enabled", listOf(mi, es, st, ra, cb).any(::on))

            listOfNotNull(st, ra, cb).forEach { modules.remove(it) }

            if (mi != null || es != null) { listOfNotNull(mi, es).forEach { odin.remove(it) }; write(odinFile, odin) }

            changed = true

        }



        // Health & Mana is a section of Odin's Player Display; its bars and hiding stay off where the module was off.

        module(modules, "Health & Mana")?.let { hm ->

            if (!Files.exists(odinFile)) return@let

            val on = hm["enabled"]?.asBoolean == true

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val pd = module(odin, "Player Display") ?: JsonObject().apply { addProperty("name", "Player Display"); addProperty("enabled", on) }.also { odin.add(it) }

            val to = settings(pd)

            for ((k, v) in settings(hm).entrySet()) {

                if (k == "Keybind" || to.has(k)) continue

                val copy = v.deepCopy()

                if (!on && copy.isJsonObject) copy.asJsonObject.addProperty("enabled", false)

                if (!on && k == "Hide Health/Mana Above %") to.addProperty(k, false) else to.add(k, copy)

            }

            if (on && pd["enabled"]?.asBoolean != true) pd.addProperty("enabled", true)

            write(odinFile, odin)

            modules.remove(hm)

            changed = true

        }



        // Random Stuff's visibility settings are Visibility's, its terminal numbers settings Terminals'. Its switches go off where it

        // was off; a target that was off comes on only for a switch that was on, with its own switches off, so nothing shows up that

        // wasn't. The keys go, so this runs once.

        module(modules, "Random Stuff")?.let { rs ->

            val from = settings(rs)

            val rsOn = rs["enabled"]?.asBoolean == true

            fun move(name: String, switches: List<String>, others: List<String>, ownSwitches: List<String>, defaultOn: Boolean) {

                if ((switches + others).none(from::has)) return

                val existing = module(modules, name)

                val wasOn = existing?.get("enabled")?.asBoolean ?: defaultOn

                val target = existing ?: JsonObject().apply { addProperty("name", name); addProperty("enabled", wasOn); add("settings", JsonObject()) }.also { modules.add(it) }

                val to = settings(target)

                var anyOn = false

                for (k in switches) {

                    val v = from.remove(k) ?: continue

                    val on = rsOn && v.isJsonPrimitive && v.asJsonPrimitive.isBoolean && v.asBoolean

                    anyOn = anyOn || on

                    if (!to.has(k)) to.add(k, if (rsOn) v else com.google.gson.JsonPrimitive(false))

                }

                for (k in others) from.remove(k)?.let { if (!to.has(k)) to.add(k, it) }

                if (anyOn && !wasOn) {

                    target.addProperty("enabled", true)

                    for (k in ownSwitches) to.addProperty(k, false)

                }

                changed = true

            }

            move("Visibility", RS_VISIBILITY_SWITCHES, RS_VISIBILITY_OTHERS, VISIBILITY_SWITCHES.values.flatten(), false)

            move("Terminals", listOf("Hide Numbers' Numbers"), listOf("Show Next Numbers"), TERMINAL_SECTIONS.mapNotNull { it.second }, true)

        }



        // LCM Melee Warning is a Mage Beam setting; Mage Beam turns on with it.

        module(modules, "LCM Melee Warning")?.let { lcm ->

            if (!Files.exists(odinFile)) return@let

            val on = lcm["enabled"]?.asBoolean == true

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val beam = module(odin, "Mage Beam") ?: JsonObject().apply { addProperty("name", "Mage Beam"); addProperty("enabled", false) }.also { odin.add(it) }

            val to = settings(beam)

            to.addProperty("LCM Melee Warning", on)

            settings(lcm)["Play Sound"]?.let { to.add("Melee Warning Sound", it) }

            settings(lcm)["Repeat Every"]?.let { to.add("Melee Warning Repeat", it) }

            if (on && beam["enabled"]?.asBoolean != true) beam.addProperty("enabled", true)

            write(odinFile, odin)

            modules.remove(lcm)

            changed = true

        }



        // Scoreboard and Nametags is Visibility's Scoreboard and Nametags sections; its switches stay off where it was off.

        module(modules, "Scoreboard and Nametags")?.let { sn ->

            val on = sn["enabled"]?.asBoolean == true

            val vis = ensure("Visibility")

            val to = settings(vis)

            for ((k, v) in settings(sn).entrySet()) {

                if (k == "Keybind" || k == "Scoreboard" || k == "Names" || k == "Server ID" || k == "Nametags") continue

                val copy = v.deepCopy()

                if (!on && copy.isJsonObject) copy.asJsonObject.addProperty("enabled", false)

                if (!on && copy.isJsonPrimitive && copy.asJsonPrimitive.isBoolean && k != "Hide Address") to.addProperty(k, false) else to.add(k, copy)

            }

            if (on && vis["enabled"]?.asBoolean != true) vis.addProperty("enabled", true)

            modules.remove(sn)

            changed = true

        }



        // Hide Portal Overlay is Visibility's.

        if (Files.exists(odinFile)) run {

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val cam = module(odin, "Camera") ?: return@run

            val s = settings(cam)

            val v = s["Hide Portal Overlay"] ?: return@run

            settings(ensure("Visibility")).addProperty("Hide Portal Overlay", cam["enabled"]?.asBoolean == true && v.asBoolean)

            s.remove("Hide Portal Overlay")

            write(odinFile, odin)

            changed = true

        }



        // Puzzles' Draft prompt is Draft (Prompt or Auto).

        if (Files.exists(odinFile)) run {

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val s = module(odin, "Puzzles")?.let(::settings) ?: return@run

            val v = s.remove("Draft prompt") ?: return@run

            if (!s.has("Draft")) s.add("Draft", v)

            write(odinFile, odin)

        }



        // LCM Style draws in the Dungeon Map's own colours: where it was on, its own ones carry over.

        if (Files.exists(odinFile)) run {

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val s = module(odin, "Dungeon Map")?.let(::settings) ?: return@run

            if (!s.has("LCM Normal Room")) return@run

            val lcmOn = s["LCM Style"]?.asBoolean == true

            val pairs = listOf("LCM Unopened Room" to "Unknown Room", "LCM Normal Room" to "Normal Room", "LCM Puzzle Room" to "Puzzle Room",

                "LCM Trap Room" to "Trap Room", "LCM Yellow Room" to "Champion Room", "LCM Blood Room" to "Blood Room", "LCM Entrance Room" to "Entrance Room",

                "LCM Fairy Room" to "Fairy Room", "LCM Rare Room" to "Rare Room", "LCM Background" to "Background Color", "LCM Border" to "Background Outline",

                "LCM Border Width" to "Outline Width", "LCM Own Marker" to "Own Marker Color")

            for ((from, to) in pairs) {

                val v = s.remove(from) ?: continue

                if (lcmOn || to == "Rare Room" || to == "Outline Width" || to == "Own Marker Color") s.add(to, v)

            }

            write(odinFile, odin)

        }



        // Legit Zpew's In Ice Fill is In Puzzles.

        if (Files.exists(odinFile)) run {

            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray

            val s = module(odin, "Etherwarp")?.let(::settings) ?: return@run

            val v = s.remove("In Ice Fill") ?: return@run

            if (!s.has("In Puzzles")) s.add("In Puzzles", v)

            write(odinFile, odin)

        }



        // Secret waypoint colours still on the old defaults take Devonian's.
        if (Files.exists(odinFile)) run {
            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray
            val s = module(odin, "Secrets")?.let(::settings) ?: return@run
            var moved = false
            for ((key, old, new) in listOf(Triple("Chest Color", "#FFAA00FF", "#00FF00FF"), Triple("Item Color", "#5555FFFF", "#0000FFFF"),
                    Triple("Essence Color", "#AA00AAFF", "#FF00FFFF"), Triple("Bat Color", "#55FF55FF", "#00FF96FF"), Triple("Redstone Key Color", "#FF5555FF", "#FF0000FF"))) {
                if (s[key]?.takeIf { it.isJsonPrimitive }?.asString.equals(old, true)) { s.addProperty(key, new); moved = true }
            }
            if (moved) write(odinFile, odin)
        }

        if (Files.exists(odinFile)) {
            val odin = JsonParser.parseString(Files.readString(odinFile)).asJsonArray
            val sprint = module(odin, "Auto Sprint")
            val rs = module(modules, "Random Stuff")
            if (sprint != null && rs != null && !settings(rs).has("Auto Sprint")) { settings(rs).addProperty("Auto Sprint", sprint["enabled"]?.asBoolean == true); changed = true }
            val cursor = module(odin, "No Cursor Reset")
            if (cursor != null && rs != null && !settings(rs).has("No Cursor Reset")) {
                settings(rs).addProperty("No Cursor Reset", cursor["enabled"]?.asBoolean == true)
                settings(cursor)["Unhook Timeout"]?.let { settings(rs).add("Unhook Timeout", it) }
                changed = true
            }
            val old = module(odin, "Menu Keybinds")
            if (old != null && module(odin, "LD WD Pet Keybinds") == null) { old.addProperty("name", "LD WD Pet Keybinds"); write(odinFile, odin) }
        }

        if (changed) {

            Files.createDirectories(file.parent)

            write(file, modules)

        }

        return changed

    }



    private val SECRET_WAYPOINT_KEYS = listOf(

        "Secret Waypoint Settings", "Secret Waypoints", "Chest Color", "Item Color", "Essence Color", "Bat Color", "Redstone Key Color",

        "Lever Color", "Secret Text", "Hide Collected", "Secret Through Walls",

    )



    private val ITEM_HIGHLIGHT_KEYS = listOf("Far Color", "Fresh Color", "Ready Color")



    /** Odin's device modules and their on/off switch in Devices. */

    private val DEVICE_SECTIONS = listOf("Simon Says" to "Enable Simon Says", "Arrow Align" to "Enable Arrow Align", "Arrows Device" to "Enable Arrows Device")



    private val DEVICE_RENAMES = mapOf(

        "Simon Says/Style" to "Box Style", "Simon Says/Party Message" to "Break Party Message", "Simon Says/Title" to "Break Title",

        "Simon Says/Sound" to "Break Sound", "Arrow Align/Block Wrong Clicks" to "Align Block Wrong Clicks",

        "Arrows Device/Depth check" to "Depth Check", "Arrows Device/Device complete alert" to "Device Complete Alert",

    )



    private val DEVICE_DROPPED = setOf("Custom Sounds Dropdown", "Reset")



    /** Old module, its on/off switch in Terminals (none: always on), and whether it lived in Odin's config. */

    private val TERMINAL_SECTIONS = listOf<Triple<String, String?, Boolean>>(

        Triple("Terminal Solver", "Enable Solver", true), Triple("Terminal Times", "Enable Times", true),

        Triple("Term Progress", "Enable Progress", false), Triple("Terminal Sounds", "Enable Sounds", true),

        Triple("Terminals Status", "Enable Status", true), Triple("Terminal Titles", "Enable Titles", true),

        Triple("Terminal Hitboxes", "Enable Hitboxes", false), Triple("Terminal Simulator", null, true),

    )



    private val TERMINAL_RENAMES = mapOf(

        "Normal Term Size" to "Vanilla Scale", "Term Size" to "Custom Size", "Slot gap" to "Slot Gap",

        "Terminal Times" to "Chat Times", "Terminal Splits" to "Section Splits", "Use Real Time" to "Real Time",

        "Show" to "Waypoints For", "Style" to "Waypoint Style", "Waypoint color" to "Waypoint Color",

        "Through Walls" to "Waypoints Through Walls", "Hide Default" to "Hide Default Names", "Ping" to "Simulator Ping",

    )



    private val TERMINAL_DROPPED = setOf(

        "Solver Functionality", "Client Prediction", "Resolve timeout", "Click Prot Dropdown", "First Click Prot", "Server Lag",

        "Lag Protection Ticks", "Color Settings", "Debug", "Adopted Term Info Settings", "Look", "Simple Mode",

    )



    /** A Core Leap setting's name in Recore: "Core Style" is "Style" there; Core Leap Highlight keeps its name. */

    private fun recoreKey(key: String) = if (key == "Core Leap Highlight" || !key.startsWith("Core ")) key else key.removePrefix("Core ")



    private val RANDOM_STUFF_RENAMES = mapOf("Hide Scoreboard Lines" to "Clean Scoreboard", "Skip Loading Screen" to "Clean Loading Screen", "Hide Numbers in Numbers" to "Hide Numbers' Numbers")



    private val STORM_HITBOX_KEYS = listOf("Hitbox Seconds", "Hitbox Margins", "Pillar Outline", "Inside Color", "Outside Color")





    private val VISIBILITY_SWITCHES = mapOf(

        "Screen Tweaks" to listOf(

            "No Vignette", "No Water Overlay", "No Suffocation Overlay", "Hide Potion Effect HUD", "Hide Inventory Effects", "No Fog",

            "Fullbright", "No Text Shadow", "No Glass Pane Highlight", "Hide Offhand Slot Background", "Sidebar Colors", "Dark Mode", "Time Changer",

        ),

        "Render Cleanup" to listOf(

            "Hide Block Break Particles", "Hide Smoke Particles", "Hide Explosion Particles", "Hide Entity Fire", "Hide Fire Overlay",

            "Disable Glowing", "Hide Falling Blocks", "Hide Lightning", "Hide Experience Orbs", "Hide Death Animation", "Hide Dying Armor Stands",

            "Hide Archer Passive", "Hide Healer Fairy", "Hide Soul Weaver", "Hide Tentacle Head", "Hide Players",

        ),

        "Chat Tweaks" to listOf("Remove Chat Limit", "Compact Chat", "Hide Empty Messages", "Compact Hoppity Messages", "Copy On Right Click"),

        "Tab Tweaks" to listOf("Remove Tab Ping", "Remove Useless Tab Heads"),

    )



    private val RS_VISIBILITY_SWITCHES = listOf(

        "Hide Dungeon Sheep", "Hide Item Names", "Hide Recipe Book", "Hide Action Bar", "Black Sky", "Blur In GUI", "No Enchant Glint",

        "No Drop Animation", "Clean Menus", "Clean Loading Screen", "Clean Scoreboard", "Clean Chat",

    )



    private val RS_VISIBILITY_OTHERS = listOf(

        "Blur Strength", "Scoreboard: Hide Catacombs Location", "Scoreboard: Hide Time Elapsed", "Scoreboard: Hide Cleared %", "Log Hidden Lines",

    )



    private val PHASE_TIMERS_SWITCHES = listOf("Maxor Tick Timer", "Storm Timer", "Term Start Timer", "Goldor Timer")



    private val TIMERS_HUDS = listOf(

        "Clear Countdown", "Portal Text", "Exit Core Text", "Maxor Move", "Crystal Spawn", "Purple Pad", "Giga Lightning", "Storm Death Time",

        "Section Time", "Necron DPS", "Tac Insertion",

    )



    private val ODIN_TICK_TIMERS_KEYS = listOf("Fire Freeze Hud", "Display in Ticks", "Display Symbol", "Show Prefix")



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

