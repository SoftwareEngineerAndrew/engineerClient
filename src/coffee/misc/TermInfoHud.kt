package com.coffeeclient.misc

import com.engineerclient.practice.TermInfo
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.HUDSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.features.impl.boss.TerminalsStatus

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
