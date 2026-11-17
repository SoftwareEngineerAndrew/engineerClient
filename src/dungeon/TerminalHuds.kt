package com.engineerclient.dungeon

import com.engineerclient.misc.MelodyChat
import com.adm.features.impl.boss.MelodyMessage
import com.adm.utils.Colors
import com.adm.utils.render.text
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft

/** ADM's Melody Alert, in Melody Message. */
object TerminalHuds {
    private val melodyHud = MelodyMessage.HUD("Melody Alert", "Who has melody and how far along they are.", true, 270, 45, 3.6f) { example ->
        val s = if (example) "§5§lARCHER§r§d has melody! 1/3" else melodyText() ?: return@HUD 0 to 0
        text(s, 0, 0, Colors.WHITE, true)
        Minecraft.getInstance().font.width(s) to 9
    }

    fun install() {
        MelodyMessage.registerSetting(melodyHud)
    }

    private var lastWho: String? = null
    private var lastStep = -1
    private var lastText = ""

    private fun melodyText(): String? {
        val heard = MelodyChat.heard()
        if (heard.isEmpty()) return null
        val me = Minecraft.getInstance().user.name
        var name: String? = null
        var percent = 0
        for ((n, h) in heard) if (!n.equals(me, true) && (name == null || h.percent > percent)) { name = n; percent = h.percent }
        name ?: return null
        val who = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, true) }?.clazz?.name ?: name
        val step = minOf(percent / 33, 2)
        if (who != lastWho || step != lastStep) {
            lastWho = who; lastStep = step
            lastText = "§5§l$who§r§d has melody! $step/3"
        }
        return lastText
    }
}
