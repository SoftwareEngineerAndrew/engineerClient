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

    private fun melodyText(): String? {
        val me = Minecraft.getInstance().user.name
        val (name, heard) = MelodyChat.heard().filterKeys { !it.equals(me, true) }.maxByOrNull { it.value.percent } ?: return null
        val who = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, true) }?.clazz?.name ?: name
        return "§5§l$who§r§d has melody! ${minOf(heard.percent / 33, 2)}/3"
    }
}
