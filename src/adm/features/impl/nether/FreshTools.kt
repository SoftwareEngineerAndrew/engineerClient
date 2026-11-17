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
        if (example) {
            text("§6Player1§f: 9s", 0, 0)
            val width = textDim("§6Player2§f: 5s", 0, mc.font.lineHeight).first
            return@HUD width to mc.font.lineHeight * 2
        }
        if (KuudraUtils.phase != 2 || !KuudraUtils.inKuudra || KuudraUtils.freshers.isEmpty()) return@HUD 0 to 0

        val now = System.currentTimeMillis()
        var y = 0
        var width = 0
        for ((name, since) in KuudraUtils.freshers) {
            val left = FRESH_MS - (now - since)
            if (left <= 0) continue
            if (prefixes.size > 64) prefixes.clear()
            val prefix = prefixes.getOrPut(name) { "§6$name§f: " }
            val prefixWidth = mc.font.width(prefix)
            val time = secondsLeft(left.toInt())
            text(prefix, 0, y)
            text(time, prefixWidth, y)
            width = maxOf(width, prefixWidth + mc.font.width(time))
            y += mc.font.lineHeight
        }
        width to y
    }

    private const val FRESH_MS = 10000L
    private const val OWN_FRESH = "Your Fresh Tools Perk bonus doubles your building speed for the next 10 seconds!"

    private val prefixes = HashMap<String, String>()
    private val seconds = arrayOfNulls<String>(FRESH_MS.toInt() + 1)

    private fun secondsLeft(ms: Int): String =
        seconds[ms] ?: "${(ms / 1000f).toFixed()}s".also { seconds[ms] = it }

    init {
        on<MessageEvent.Chat> {
            if (notifyFresh && message == OWN_FRESH && KuudraUtils.inKuudra) sendCommand("pc FRESH")
        }
    }
}
