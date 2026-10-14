package com.engineerclient.chat

import com.engineerclient.EngineerClient
import com.engineerclient.rotation.EcLog
import net.minecraft.network.chat.Component
import java.nio.file.Files

/**
 * Random Stuff's Clean Chat: hides chat lines its rules mark Block — at the very last step, when the chat GUI is about to
 * add the line to its list (see ChatComponentMixin).
 *
 * That placement is the point. Everything else that reads chat — Odin's parsers, this mod's
 * own rotation, blade-addons, devonian, anything on Fabric's message events or the packet —
 * runs before the GUI, and none of it is touched: a hidden line is still received, still parsed
 * by every mod, still written to latest.log, it just never appears on screen. Cancelling a chat
 * event or the packet would have silenced the other mods too.
 *
 * Rules come from `chat/hidden.json` in the jar; drop a file of the same shape at
 * `config/engineerclient/chat-hider.json` to override without a rebuild (Reload Rules picks it up).
 */
object ChatHider {
    /** Clean Chat's Reload Rules. */
    fun reload() = load(announce = true)

    @Volatile
    private var rules: ChatRules = ChatRules(emptyList(), emptyList())
    private var loaded = false

    private val overrideFile = EngineerClient.mc.gameDirectory.toPath().resolve("config").resolve("engineerclient").resolve("chat-hider.json")

    private fun load(announce: Boolean) {
        loaded = true
        val (set, from) = try {
            if (Files.exists(overrideFile)) ChatRules.parse(Files.readString(overrideFile)) to overrideFile.fileName.toString()
            else ChatRules.bundled() to "built-in"
        } catch (t: Throwable) {
            EngineerClient.logger.warn("[ec] chat hider rules failed to load", t)
            ChatRules(emptyList(), emptyList()) to "none (load failed)"
        }
        rules = set
        EngineerClient.logger.info("[ec] chat hider: ${set.size} rules from $from" + if (set.invalid.isNotEmpty()) ", ${set.invalid.size} invalid" else "")
        if (announce) EngineerClient.msg("§7clean chat: §f${set.size}§7 rules from §f$from" + if (set.invalid.isNotEmpty()) " §c(${set.invalid.size} invalid regex)" else "")
    }

    /** Called from the chat GUI's single add-message funnel. True = do not display. */
    fun shouldHide(message: Component): Boolean {
        if (!com.engineerclient.misc.RandomStuff.cleansChat()) return false
        if (!loaded) load(announce = false)
        val text = ChatRules.strip(message.string)
        val rule = rules.hides(text) ?: return false
        if (com.engineerclient.misc.RandomStuff.logsHiddenChat()) EcLog.log("CHAT-HIDE", "$rule ⇐ ${text.replace("\n", "\\n")}")
        return true
    }
}
