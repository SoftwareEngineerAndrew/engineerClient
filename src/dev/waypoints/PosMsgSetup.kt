package com.devgineerclient.waypoints

import com.devgineerclient.DevgineerClient
import com.engineerclient.rotation.SetupCheck
import com.engineerclient.waypoints.BrWaypoints2
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import java.nio.file.Files
import java.nio.file.Path

/**
 * Positional Messages lives here (it was Engineer Client's, and Odin's before 0.3.6): its module's
 * saved state comes over once, its editor joins BR Roles' wand, its boxes feed Engineer Client's
 * /ec setup, and /posmsg (with /posmsg edit) is registered.
 */
object PosMsgSetup {

    /** Before Odin loads devgineerclient.json: the module as Engineer Client (or older, Odin) saved it, if this file hasn't got it yet. */
    fun migrate(odinDir: Path) {
        val dev = odinDir.resolve("addons").resolve("devgineerclient.json")
        val modules = if (Files.exists(dev)) JsonParser.parseString(Files.readString(dev)).asJsonArray else JsonArray()
        if (module(modules, NAME) != null) return
        val from = listOf(odinDir.resolve("addons").resolve("engineerclient.json"), odinDir.resolve("odin-config.json"))
            .firstNotNullOfOrNull { f -> if (Files.exists(f)) module(JsonParser.parseString(Files.readString(f)).asJsonArray, NAME) else null } ?: return
        modules.add(from.deepCopy())
        Files.createDirectories(dev.parent)
        Files.writeString(dev, GsonBuilder().setPrettyPrinting().create().toJson(modules))
        DevgineerClient.logger.info("[dc] Positional Messages: settings and boxes brought over")
    }

    fun install() {
        BrWaypoints2.wandUser = PosMsgEditor
        SetupCheck.posMessages = { if (PositionalMessages.enabled) PositionalMessages.posMessageStrings.map { it.message } else emptyList() }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            PositionalMessages.registerCommand(dispatcher)
            // Brigadier merges this into /posmsg's tree.
            dispatcher.register(literal("posmsg").then(literal("edit").executes { DevgineerClient.mc.execute { PosMsgEditor.toggle() }; 1 }))
        }
    }

    private const val NAME = "Positional Messages"

    private fun module(list: JsonArray, name: String): JsonObject? =
        list.firstOrNull { it.isJsonObject && it.asJsonObject["name"]?.asString == name }?.asJsonObject
}
