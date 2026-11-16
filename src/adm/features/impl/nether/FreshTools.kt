package com.adm.features.impl.nether

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.features.Module
import com.adm.utils.render.text
import com.adm.utils.render.textDim
import com.adm.utils.sendCommand
import com.adm.utils.skyblock.KuudraUtils
import com.adm.utils.toFixed

object FreshTools : Module(
    name = "Fresh Tools",
    description = "Tracks your party's fresh tools time.",
) {
    private val notifyFresh by BooleanSetting("Notify Fresh", true, desc = "Notifies your party when you get fresh timer.")
    private val hud by HUD("Fresh timer", "Displays how long players have fresh for.") { example ->
        if (!example && (!KuudraUtils.inKuudra || KuudraUtils.phase != 2 || KuudraUtils.freshers.isEmpty())) return@HUD 0 to 0

        var yOffset = 0
        var maxWidth = 0

        if (example) {
            text("§6Player1§f: 9s", 0, yOffset)
            yOffset += mc.font.lineHeight
            maxWidth = textDim("§6Player2§f: 5s", 0, yOffset).first
            yOffset += mc.font.lineHeight
        } else {
            KuudraUtils.freshers.forEach { fresher ->
                val timeLeft = fresher.value?.let { (10000L - (System.currentTimeMillis() - it)) }?.takeIf { it > 0 }
                    ?: return@forEach
                val text = "§6${fresher.key}§f: ${(timeLeft / 1000f).toFixed()}s"
                val width = textDim(text, 0, yOffset).first
                maxWidth = maxOf(maxWidth, width)
                yOffset += mc.font.lineHeight
            }
        }

        maxWidth to yOffset
    }

    private val ownFreshRegex = Regex("^Your Fresh Tools Perk bonus doubles your building speed for the next 10 seconds!$")

    init {
        on<MessageEvent.Chat> {
            if (notifyFresh && KuudraUtils.inKuudra && ownFreshRegex.matches(message))
                sendCommand("pc FRESH")
        }
    }
}