package com.ascent

import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Ascent's own settings. Everything waypoint-shaped lives in Odin's pack files;
 * this holds only the profile axes and the class stash.
 */
object AscentConfig {

    data class Data(
        /** Master switch: when false Ascent never touches Odin's pack selection. */
        var enabled: Boolean = true,
        /** How many party members run the blood rush with this strategy (2..5). Manual — a team-strategy choice. */
        var playersOnRush: Int = 4,
        /** Whether the strategy uses a dedicated door opener. Manual. */
        var dedicatedDoor: Boolean = false,
        /** Manual class override (DungeonClass name, e.g. "MAGE"); null = auto-detect. */
        var classOverride: String? = null,
        /** Last class successfully detected from tab (DungeonClass name). Fallback for the rare runs where tab detection fails. */
        var lastKnownClass: String? = null,
        /** Extra Odin waypoint packs kept selected alongside the active profile pack. */
        var customPacks: MutableList<String> = mutableListOf(),
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("ascent").resolve("config.json")

    var data: Data = Data()
        private set

    fun load() {
        try {
            if (Files.exists(file)) {
                data = gson.fromJson(Files.readString(file), Data::class.java) ?: Data()
                if (data.playersOnRush !in 2..5) data.playersOnRush = 4
            }
        } catch (t: Throwable) {
            AscentMod.logger.warn("[ascent] failed to load config, keeping defaults", t)
        }
    }

    fun save() {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("config.json.tmp")
            Files.writeString(tmp, gson.toJson(data))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            AscentMod.logger.warn("[ascent] failed to save config", t)
        }
    }
}
