package com.devgineerclient.waypoints

import com.devgineerclient.DevgineerClient
import com.engineerclient.rotation.SetupCheck
import com.engineerclient.waypoints.BrWaypoints2
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.odtheking.odin.features.impl.dungeon.PositionalMessages
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import java.nio.file.Files
import java.nio.file.Path

/**
 * Positional Messages is Odin's own on the Minecraft 26.1.2 build (Odin 0.3.4): [PosMsgExtras]'
 * settings come over once, the editor joins BR Roles' wand, Odin's boxes feed Engineer Client's
 * /ec setup, and /posmsg edit and /posmsg wand join Odin's /posmsg.
 */
object PosMsgSetup {

    /** Before Odin loads devgineerclient.json: the re-trigger and box-here key as Engineer Client saved them (BR Roles' once), if this file hasn't got them yet. */
    fun migrate(odinDir: Path) {
        val dev = odinDir.resolve("addons").resolve("devgineerclient.json")
        val modules = if (Files.exists(dev)) JsonParser.parseString(Files.readString(dev)).asJsonArray else JsonArray()
        if (module(modules, NAME) != null) return
        val ec = odinDir.resolve("addons").resolve("engineerclient.json")
        if (!Files.exists(ec)) return
        val from = module(JsonParser.parseString(Files.readString(ec)).asJsonArray, "Positional Messages")?.getAsJsonObject("settings") ?: return
        val settings = JsonObject()
        for (k in listOf("Re-trigger", "Box Here Keybind")) from[k]?.let { settings.add(k, it.deepCopy()) }
        if (settings.size() == 0) return
        modules.add(JsonObject().apply { addProperty("name", NAME); addProperty("enabled", true); add("settings", settings) })
        Files.createDirectories(dev.parent)
        Files.writeString(dev, GsonBuilder().setPrettyPrinting().create().toJson(modules))
        DevgineerClient.logger.info("[dc] Posmsg Extras: settings brought over")
    }

    fun install() {
        BrWaypoints2.wandUser = PosMsgEditor
        SetupCheck.posMessages = { if (PositionalMessages.enabled) PositionalMessages.posMessageStrings.map { it.message } else emptyList() }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // Brigadier merges this into Odin's /posmsg tree.
            dispatcher.register(literal("posmsg").then(literal("edit").executes { DevgineerClient.mc.execute { PosMsgEditor.toggle() }; 1 })
                .then(literal("wand").executes { DevgineerClient.mc.execute { com.engineerclient.waypoints.BrWaypoints2.makeWand() }; 1 }))
        }
    }

    private const val NAME = "Posmsg Extras"

    private fun module(list: JsonArray, name: String): JsonObject? =
        list.firstOrNull { it.isJsonObject && it.asJsonObject["name"]?.asString == name }?.asJsonObject
}
