package com.engineerclient.chat

import com.engineerclient.EngineerClient
import com.engineerclient.misc.RandomStuff
import com.engineerclient.rotation.EcLog
import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.network.chat.Component
import java.nio.file.Files

/**
 * Hides chat lines the Dungeon Chat Hider page marked Block — at the very last step, when the
 * chat GUI is about to add the line to its list (see ChatComponentMixin).
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
object ChatHider : Module(
    name = "Chat Hider",
    category = Category.custom("Blood Rush"),
    description = "Hides the chat lines marked Block in the Dungeon Chat Hider page. Other mods still see every line.",
    toggled = true,
) {
    private val logHidden by BooleanSetting("Log Hidden Lines", false, desc = "Writes every hidden line and the rule that hid it to the EC session log, for checking a rule.")
    private val reload by ActionSetting("Reload Rules", desc = "Re-reads config/engineerclient/chat-hider.json (or the built-in rules if that file is absent).") { load(announce = true) }

    @Volatile
    private var rules: ChatRules = ChatRules(emptyList(), emptyList())
    private var loaded = false
    var hiddenCount = 0
        private set

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
        if (announce) EngineerClient.chat("§8[§6EC§8]§7 chat hider: §f${set.size}§7 rules from §f$from" + if (set.invalid.isNotEmpty()) " §c(${set.invalid.size} invalid regex)" else "")
    }

    /** Called from the chat GUI's single add-message funnel. True = do not display. */
    fun shouldHide(message: Component): Boolean {
        if (RandomStuff.hideChat) return true
        if (!enabled) return false
        if (!loaded) load(announce = false)
        val text = ChatRules.strip(message.string)
        val rule = rules.hides(text) ?: return false
        hiddenCount++
        if (logHidden) EcLog.log("CHAT-HIDE", "$rule ⇐ ${text.replace("\n", "\\n")}")
        return true
    }
}
