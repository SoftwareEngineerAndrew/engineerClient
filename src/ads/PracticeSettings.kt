package com.engineerclient.ads

import com.engineerclient.EngineerClient
import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The Practice page's choices, kept between games in config/engineerclient/practice.json. */
object PracticeSettings {

    data class Data(
        /** The rail tab last open (PracticeScreen.Mode name). */
        var mode: String = "FULL_CLEAR",
        /** "F7" or "M7"; Wither King practice is always M7. */
        var floor: String = "M7",
        /** Full clear and blood rush: replay [seed] instead of a new map each time. */
        var fixedSeed: Boolean = false,
        var seed: String = "",
        /** The last random seed a practice used, offered for replaying it. */
        var lastSeed: Long? = null,
        var room: String? = null,
        /** north, east, south, west or random. */
        var door: String = "random",
        /** Full clear's dungeon: "recorded" (Engineer Client's full run: bots, lag, recorded layouts) or "engine" (Rustclear's). */
        var clearEngine: String = "recorded",
        /** The recorded run's layout: "random", "seed" or "recorded" (one from the layout browser). */
        var runLayout: String = "random",
        var runSeed: String = "",
        /** The recorded layout's id, chosen in the browser. */
        var runLayoutId: String? = null,
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("practice.json")

    var data: Data = load()
        private set

    private fun load(): Data = try {
        if (Files.exists(file)) gson.fromJson(Files.readString(file), Data::class.java) ?: Data() else Data()
    } catch (t: Throwable) {
        EngineerClient.logger.warn("[ads] failed to load practice settings, keeping defaults", t)
        Data()
    }

    fun save() {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("practice.json.tmp")
            Files.writeString(tmp, gson.toJson(data))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ads] failed to save practice settings", t)
        }
    }

    /** The typed seed: a number as is, any other text hashed to one, so "practice" works as a seed too. */
    fun parsedSeed(): Long? {
        val s = data.seed.trim()
        if (s.isEmpty()) return null
        return s.toLongOrNull() ?: s.hashCode().toLong()
    }
}
