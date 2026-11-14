package com.engineerclient.dungeon

import com.engineerclient.misc.OdinMelodyChat
import com.engineerclient.practice.TermInfo
import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.impl.boss.MelodyMessage
import com.odtheking.odin.features.impl.boss.TerminalsStatus
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Blade's P3 HUDs: Melody Alert in Melody Message, Current Section and Section Progress in Terminals Status. */
object TerminalHuds {
    private val colorProgress = BooleanSetting("Color Progress", true, desc = "Section progress goes red, yellow, green, gold as it fills.")

    private val melodyHud = MelodyMessage.HUD("Melody Alert", "Who has melody and how far along they are.", true, 270, 45, 3.6f) { example ->
        if (example) return@HUD draw("§5§lARCHER§r§d has melody! 1/3")
        melodyText()?.let { draw(it) } ?: (0 to 0)
    }
    private val sectionHud = TerminalsStatus.HUD("Current Section", "The terminal section you're on.", true, 900, 520, 1f) { example ->
        val n = if (example) 1 else TermInfo.sectionNumber() ?: return@HUD 0 to 0
        draw("§5Section: §f$n")
    }
    private val progressHud = TerminalsStatus.HUD("Section Progress", "The current section's tasks done.", true, 420, 180, 3.6f) { example ->
        val (done, total) = if (example) 3 to 7 else TermInfo.sectionProgress() ?: return@HUD 0 to 0
        draw(progressText(done, total))
    }

    fun install() {
        MelodyMessage.registerSetting(melodyHud)
        for (s in listOf(sectionHud, progressHud, colorProgress.withDependency { progressHud.value.enabled })) TerminalsStatus.registerSetting(s)
    }

    private fun GuiGraphicsExtractor.draw(s: String): Pair<Int, Int> {
        text(s, 0, 0, Colors.WHITE, true)
        return Minecraft.getInstance().font.width(s) to 9
    }

    private fun progressText(done: Int, total: Int): String {
        if (!colorProgress.value) return "§a(§c$done§a/$total)"
        val c = when {
            done >= total -> "§6§l"
            total - done == 1 -> "§a"
            done >= 3 -> "§e"
            else -> "§c"
        }
        return "§f($c$done§f/§a$total§f)"
    }

    private fun melodyText(): String? {
        val (name, heard) = OdinMelodyChat.heard().maxByOrNull { it.value.percent } ?: return null
        val who = DungeonUtils.dungeonTeammates.firstOrNull { it.name.equals(name, true) }?.clazz?.name ?: name
        return "§5§l$who§r§d has melody! ${minOf(heard.percent / 33, 2)}/3"
    }
}
