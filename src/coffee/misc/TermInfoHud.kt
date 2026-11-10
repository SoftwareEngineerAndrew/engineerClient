package com.coffeeclient.misc

import com.engineerclient.practice.TermInfo
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.impl.boss.TerminalTitles
import com.odtheking.odin.features.impl.boss.TerminalsStatus
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.world.phys.Vec3

/**
 * Term Info: the current terminal section's terms, levers, device and gate as Devonian counts them, or with Simple
 * Mode just the total (3/7, green once the gate is down), through all of P3. [TermInfo] does the counting; this is
 * its HUD. Was the ADM look on Odin's Terminals Status: [adoptOdinLook] carries that over once.
 */
object TermInfoHud : Module(
    name = "Term Info",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "The current terminal section's terms, levers, device and gate on their own lines (or just the total, Simple Mode), shown through all of P3."
) {
    private var simple by BooleanSetting("Simple Mode", false, desc = "Only the total progress of the section, e.g. 3/7 (green once the gate is down). Off: terms, levers, device and gate on their own lines.")

    private val completionTitles by SelectorSetting("Completion Titles", Titles.HIDE_ALL, desc = "What to do with the server's P3 titles (\"X activated a terminal! (3/7)\", levers, devices, the gate). Hide Others': only those naming another player. Final Only: only the one that finishes the section; the gate's are hidden too.")
    private val hideOnI4Ss by BooleanSetting("Hide Titles On i4/SS", false, desc = "While you stand on the i4 plate or at Simon Says, these titles are hidden whatever Completion Titles says.")

    private enum class Titles(private val label: String) {
        SHOW_ALL("Show All"), HIDE_ALL("Hide All"), HIDE_OTHERS("Hide Others'"), FINAL_ONLY("Final Only");
        override fun toString() = label
    }

    private val CODES = Regex("§.")
    private val TASK_TITLE = Regex("^(.{1,16}) (?:activated a terminal|activated a lever|completed a device)! \\((\\d+)/(\\d+)\\)$")
    private val SS_BUTTON = Vec3(108.0, 119.0, 94.0)

    /** The i4 plate, or within 3 blocks of Simon Says' start button. */
    private fun atI4OrSs(): Boolean {
        if (com.engineerclient.misc.I4Complete.onPlate()) return true
        val me = com.engineerclient.EngineerClient.mc.player ?: return false
        return me.position().distanceTo(SS_BUTTON) <= 3.0
    }

    /** Whether the title with this [raw] text is hidden by the two settings above; which titles are the terminal ones is [com.engineerclient.practice.TermInfo]'s to say. */
    private fun hidesTitle(raw: String): Boolean {
        if (!com.engineerclient.practice.TermInfo.isCompletionTitle(raw)) return false
        // A task's title names who did it and how far the section is; the gate and core ones don't.
        val task = TASK_TITLE.matchEntire(raw.replace(CODES, ""))
        if (hideOnI4Ss && atI4OrSs()) return true
        return when (completionTitles) {
            Titles.SHOW_ALL -> false
            Titles.HIDE_ALL -> true
            Titles.HIDE_OTHERS -> task != null && task.groupValues[1] != com.engineerclient.EngineerClient.mc.player?.gameProfile?.name
            Titles.FINAL_ONLY -> task == null || task.groupValues[2] != task.groupValues[3]
        }
    }

    /**
     * Above Odin's Terminal Titles (priority 0, which runs even on a cancelled packet and would show its own copy):
     * with it on, whatever it put up is cleared again once its own game-thread call has run.
     */
    private fun hide(text: String, cancel: () -> Unit) {
        if (!hidesTitle(text)) return
        cancel()
        if (TerminalTitles.enabled) com.engineerclient.EngineerClient.mc.let { mc -> mc.execute { mc.execute { mc.gui.hud.clearTitles() } } }
    }

    init {
        onReceive<ClientboundSetTitleTextPacket>(priority = 1000) { hide(text.string, it::cancel) }
        onReceive<ClientboundSetSubtitleTextPacket>(priority = 1000) { hide(text.string, it::cancel) }
    }

    private val hud by HUD("Term Info", "The current terminal section's progress.", true, 633, 416, 2.05f) { example ->
        TermInfo.drawInfo(this, example, simple)
    }

    private var adopted by BooleanSetting("Adopted Odin Look", false, desc = "").hide()

    /**
     * Once: where Odin's Terminals Status had the ADM look (read from odin-config.json, which still has it until Odin
     * next saves), this takes its place, scale, Simple Mode and on/off, and Odin's Term Info HUD goes off.
     */
    fun adoptOdinLook() {
        if (adopted) return
        adopted = true
        val file = com.coffeeclient.CoffeeClient.mc.gameDirectory.toPath().resolve("config/odin/odin-config.json")
        val ts = if (java.nio.file.Files.exists(file)) com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).asJsonArray
            .firstOrNull { it.asJsonObject.get("name")?.asString == "Terminals Status" }?.asJsonObject else null
        val from = ts?.getAsJsonObject("settings")
        if (from?.get("Look")?.asString == "ADM") {
            val odinHud = (TerminalsStatus.settings["Term Info"] as? HUDSetting)?.value
            if (odinHud != null) {
                hud.x = odinHud.x; hud.y = odinHud.y; hud.scale = odinHud.scale
                hud.enabled = odinHud.enabled
                odinHud.enabled = false
            }
            simple = from.get("Simple Mode")?.asBoolean ?: false
            if (TerminalsStatus.enabled && !enabled) toggle()
        }
        com.odtheking.odin.features.ModuleManager.saveConfigurations()
    }
}
