package com.adm.features.impl.dungeon

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.ColorSetting
import com.adm.features.Module
import com.adm.utils.Color
import com.adm.utils.Colors
import com.adm.utils.render.getStringWidth
import com.adm.utils.render.text
import com.adm.utils.skyblock.dungeon.Blessing
import com.adm.utils.skyblock.dungeon.DungeonUtils

object BlessingDisplay : Module(
    name = "Blessing Display",
    description = "Displays the current active blessings of the dungeon."
) {
    private val power by BooleanSetting("Power Blessing", true, desc = "Displays the power blessing.")
    private val powerColor by ColorSetting("Power Color", Colors.MINECRAFT_DARK_RED, true, desc = "The color of the power blessing.").withDependency { power }
    private val time by BooleanSetting("Time Blessing", true, desc = "Displays the time blessing.")
    private val timeColor by ColorSetting("Time Color", Colors.MINECRAFT_DARK_PURPLE, true, desc = "The color of the time blessing.").withDependency { time }
    private val stone by BooleanSetting("Stone Blessing", false, desc = "Displays the stone blessing.")
    private val stoneColor by ColorSetting("Stone Color", Colors.MINECRAFT_GRAY, true, desc = "The color of the stone blessing.").withDependency { stone }
    private val life by BooleanSetting("Life Blessing", false, desc = "Displays the life blessing.")
    private val lifeColor by ColorSetting("Life Color", Colors.MINECRAFT_RED, true, desc = "The color of the life blessing.").withDependency { life }
    private val wisdom by BooleanSetting("Wisdom Blessing", false, desc = "Displays the wisdom blessing.")
    private val wisdomColor by ColorSetting("Wisdom Color", Colors.MINECRAFT_AQUA, true, desc = "The color of the wisdom blessing.").withDependency { wisdom }

    private const val EXAMPLE_LEVEL = 19
    private val shownOrder = arrayOf(Blessing.POWER, Blessing.TIME, Blessing.STONE, Blessing.LIFE, Blessing.WISDOM)
    private val lineLevels = IntArray(shownOrder.size) { -1 }
    private val lines = arrayOfNulls<String>(shownOrder.size)

    private fun shown(i: Int) = when (i) { 0 -> power; 1 -> time; 2 -> stone; 3 -> life; else -> wisdom }
    private fun color(i: Int): Color = when (i) { 0 -> powerColor; 1 -> timeColor; 2 -> stoneColor; 3 -> lifeColor; else -> wisdomColor }

    private fun line(i: Int, level: Int): String {
        if (lineLevels[i] != level || lines[i] == null) {
            lines[i] = "${shownOrder[i].displayString} §a$level§r"
            lineLevels[i] = level
        }
        return lines[i]!!
    }

    private val hud by HUD(name, "Displays the current active blessings of the dungeon.", false) { example ->
        if (!example && !DungeonUtils.inDungeons) return@HUD 0 to 0
        var row = 0
        for (i in shownOrder.indices) {
            if (!shown(i)) continue
            val level = if (example) EXAMPLE_LEVEL else shownOrder[i].current
            if (level <= 0) continue
            text(line(i, level), 0, 9 * row, color(i))
            row++
        }
        getStringWidth("Power: 19") to 1 + 10 * row
    }
}
