// Adapted from Odin (https://github.com/odtheking/OdinFabric), Copyright (c) 2025, odtheking. BSD 3-Clause License: see licenses/Odin-LICENSE.txt.
package com.engineerclient.ads.splits

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * ADS's split personal bests (Odin's PersonalBest), kept apart from Odin's: config/engineerclient/splits.json, a
 * time in seconds per split per group (F7 and M7 for Rustclear runs, Sim for the P3 Sim). A split is keyed by its
 * name without colour codes ("Maxor", "Total").
 */
object AdsPersonalBests {
    private val bests = HashMap<String, HashMap<String, Float>>()
    private var loaded = false

    private val path: Path get() = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("splits.json")

    private fun key(name: String) = name.replace(CODES, "").trim()

    fun get(group: String, name: String): Float? {
        load()
        return bests[group]?.get(key(name))
    }

    /**
     * [time] for [name] in [group]: kept if it beats the best, and said in chat (when [say]) as Odin says it, the
     * old best after it.
     */
    fun time(group: String, name: String, time: Float, message: String, say: Boolean) {
        val old = get(group, name)
        val note = when {
            // A Spectate Route is the bots' run, not yours: timed, never a PB.
            com.engineerclient.p3sim.SpectateRoute.active -> "§8(spectated)"
            old == null -> { set(group, name, time); "§7(§d§lFirst PB§r§7)" }
            old > time -> { set(group, name, time); "§7(§d§lNew PB§r§7) Old PB was §8${"%.2f".format(java.util.Locale.ROOT, old)}" }
            else -> "§8(§7${"%.2f".format(java.util.Locale.ROOT, old)}§8)"
        }
        if (say) EngineerClient.msg("$message${"%.2f".format(java.util.Locale.ROOT, time)}s§7! $note")
    }

    fun set(group: String, name: String, time: Float) {
        load()
        bests.getOrPut(group) { HashMap() }[key(name)] = time
        save()
    }

    fun reset() {
        load()
        bests.clear()
        save()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!Files.exists(path)) return
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            for ((group, times) in json.entrySet()) {
                val m = bests.getOrPut(group) { HashMap() }
                for ((name, t) in times.asJsonObject.entrySet()) runCatching { m[name] = t.asFloat }
            }
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ads] failed to load splits.json, starting with no PBs", t)
        }
    }

    private fun save() {
        try {
            Files.createDirectories(path.parent)
            val json = JsonObject()
            for ((group, times) in bests) json.add(group, JsonObject().also { o -> times.forEach { (n, t) -> o.addProperty(n, t) } })
            val tmp = path.resolveSibling("splits.json.tmp")
            Files.writeString(tmp, GSON.toJson(json))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ads] failed to save splits.json", t)
        }
    }

    private val CODES = Regex("§.")
    private val GSON = GsonBuilder().setPrettyPrinting().create()
}
