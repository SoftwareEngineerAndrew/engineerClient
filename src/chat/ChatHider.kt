package com.engineerclient.chat

import com.engineerclient.EngineerClient
import com.engineerclient.rotation.EcLog
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.Component
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files

/**
 * Random Stuff's Clean Chat: hides chat lines its rules mark Block — at the very last step, when the chat GUI is about to
 * add the line to its list (see ChatComponentMixin).
 *
 * That placement is the point. Everything else that reads chat — ADM's parsers, this mod's
 * own rotation, other mods, anything on Fabric's message events or the packet —
 * runs before the GUI, and none of it is touched: a hidden line is still received, still parsed
 * by every mod, still written to latest.log, it just never appears on screen. Cancelling a chat
 * event or the packet would have silenced the other mods too.
 *
 * Rules come from `chat/hidden.json` in the jar. `config/engineerclient/chat-hider.json`, of the same shape, holds only the
 * player's own rules, which go ahead of the built-in ones (Reload Rules picks it up).
 */
object ChatHider {
    /** Clean Chat's Reload Rules. */
    fun reload() = load(announce = true)

    /** /adm chathide: a block rule added to the player's own rules. */
    fun addRule(regex: String) {
        try { java.util.regex.Pattern.compile(regex) } catch (e: java.util.regex.PatternSyntaxException) {
            return EngineerClient.msg("§cInvalid regex: ${e.description}")
        }
        try {
            val root = if (Files.exists(userFile)) JsonParser.parseString(Files.readString(userFile)).asJsonObject else JsonObject()
            val custom = root.getAsJsonArray("custom") ?: JsonArray().also { root.add("custom", it) }
            custom.add(JsonObject().apply { addProperty("regex", regex); addProperty("action", "block") })
            write(root)
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] chat hider rule not saved", t)
            return EngineerClient.msg("§cCould not save the rule to ${userFile.fileName}.")
        }
        EngineerClient.msg("§7clean chat: added §f$regex")
        reload()
    }

    @Volatile
    private var rules: ChatRules = ChatRules(emptyList(), emptyList())
    private var loaded = false

    private val userFile = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("chat-hider.json")

    private fun write(root: JsonObject) {
        Files.createDirectories(userFile.parent)
        Files.writeString(userFile, GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root))
    }

    // Older builds copied every built-in rule into the file; only the player's own stay, so later built-in changes reach them.
    private fun ownRules(text: String, bundled: ChatRules): ChatRules {
        val root = JsonParser.parseString(text).asJsonObject
        var dropped = false
        for ((key, builtIn) in listOf("custom" to bundled.custom.mapTo(HashSet()) { it.regex }, "block" to bundled.block.mapTo(HashSet()) { it.regex })) {
            val entries = root.getAsJsonArray(key)?.iterator() ?: continue
            while (entries.hasNext()) {
                val regex = (entries.next() as? JsonObject)?.get("regex")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                if (regex in builtIn) { entries.remove(); dropped = true }
            }
        }
        if (dropped) {
            for (key in listOf("version", "updatedAt", "source")) root.remove(key)
            write(root)
        }
        return ChatRules.parse(root.toString())
    }

    private fun load(announce: Boolean) {
        loaded = true
        val (set, from) = try {
            val bundled = ChatRules.bundled()
            if (Files.exists(userFile)) {
                val own = ownRules(Files.readString(userFile), bundled)
                ChatRules.merged(own, bundled) to "built-in + ${own.custom.size + own.block.size} of yours"
            } else bundled to "built-in"
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] chat hider rules failed to load", t)
            ChatRules.bundled() to "built-in (${userFile.fileName} failed to load)"
        }
        rules = set
        EngineerClient.logger.info("[ec] chat hider: ${set.size} rules from $from" + if (set.invalid.isNotEmpty()) ", ${set.invalid.size} invalid" else "")
        if (announce) EngineerClient.msg("§7clean chat: §f${set.size}§7 rules from §f$from" + if (set.invalid.isNotEmpty()) " §c(${set.invalid.size} invalid regex)" else "")
    }

    private var afterHidden = false
    private val BLANK = Regex("§.|[\\s\u00A0\u200B-\u200D\u2800\uFEFF]")
    private fun blank(c: Component) = BLANK.replace(c.string, "").isEmpty()

    /** True = do not display. Hypixel frames messages with blank lines: those around a hidden one go with it. */
    fun hides(message: Component, all: MutableList<GuiMessage>, lines: MutableList<GuiMessage.Line>): Boolean {
        if (shouldHide(message)) {
            val last = all.firstOrNull()
            if (last != null && last.addedTime() == EngineerClient.mc.gui.hud.guiTicks && blank(last.content())) {
                all.removeAt(0)
                lines.removeIf { it.parent() === last }
            }
            afterHidden = true
            return true
        }
        if (afterHidden && blank(message)) return true
        afterHidden = false
        return false
    }

    private fun shouldHide(message: Component): Boolean {
        if (!com.engineerclient.render.Visibility.cleansChat()) return false
        if (!loaded) load(announce = false)
        val text = ChatRules.strip(message.string)
        val rule = rules.hides(text) ?: return false
        if (com.engineerclient.render.Visibility.logsHiddenChat()) EcLog.log("CHAT-HIDE", "$rule ⇐ ${text.replace("\n", "\\n")}")
        return true
    }
}
