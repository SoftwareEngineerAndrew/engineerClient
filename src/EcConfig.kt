package com.engineerclient

import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** EC's own settings outside Odin's module config: the player's P3 starting role and the class stash. */
object EcConfig {

    data class Data(
        /** Last class detected from tab (DungeonClass name), so the player's class is known outside a dungeon. */
        var lastKnownClass: String? = null,
        /**
         * This player's phase-3 starting role (section-1 role id). Each client knows only its own; the team's
         * full binding is assembled on every client from the roles each announces to party chat.
         */
        var myStartingRole: String? = null,
        /** Legacy whole-team role map, kept only so an old file's own-role entry can be migrated. */
        var roleBindings: MutableMap<String, String>? = null,
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("config.json")

    var data: Data = Data()
        private set

    /** Returns true when this is a fresh install (no config file existed yet). */
    fun load(): Boolean {
        try {
            if (Files.exists(file)) {
                data = gson.fromJson(Files.readString(file), Data::class.java) ?: Data()
                // Old shape stored the whole team; keep only the entry that was ours.
                data.roleBindings?.let { old ->
                    val me = Minecraft.getInstance().player?.name?.string
                    if (data.myStartingRole == null && me != null) data.myStartingRole = old.entries.firstOrNull { it.value.equals(me, true) }?.key
                    data.roleBindings = null
                    save()
                }
                return false
            }
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] failed to load config, keeping defaults", t)
            return false
        }
        save()
        return true
    }

    fun save() {
        try {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("config.json.tmp")
            Files.writeString(tmp, gson.toJson(data))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] failed to save config", t)
        }
    }
}
