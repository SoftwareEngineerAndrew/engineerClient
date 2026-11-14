package com.engineerclient.render

import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import java.util.Locale
import kotlin.math.abs

object BossBar : Module(
    name = "Boss Bar",
    category = Category.RENDER,
    description = "Shows the boss's health or percentage left on its boss bar.",
    key = null,
) {
    enum class Shown(private val label: String) { PERCENT("Percentage"), HEALTH("Health"); override fun toString() = label }

    private val shown by SelectorSetting("Show", Shown.PERCENT, desc = "Percentage left, or health when the boss is in view.")

    @JvmStatic
    fun draw(g: GuiGraphicsExtractor, x: Int, y: Int, event: BossEvent) {
        if (!enabled) return
        val progress = event.progress
        val text = (if (shown == Shown.HEALTH) health(progress) else null)
            ?: String.format(Locale.ROOT, "%.1f%%", progress * 100f)
        val w = mc.font.width(text)
        g.text(mc.font, text, x + (BAR_WIDTH - w) / 2, y - 1, -1, true)
    }

    // The bar only carries a fraction: the boss in view whose health is at that fraction gives the number.
    private fun health(progress: Float): String? {
        val level = mc.level ?: return null
        val me = mc.player ?: return null
        val boss = level.entitiesForRendering().asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { it !is Player && it.isAlive && it.maxHealth > 40f && abs(it.health / it.maxHealth - progress) < 0.01f }
            .minByOrNull { it.distanceToSqr(me) } ?: return null
        return "${format(boss.health)}/${format(boss.maxHealth)}"
    }

    private fun format(v: Float): String = when {
        v >= 1e9 -> String.format(Locale.ROOT, "%.1fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.ROOT, "%.1fM", v / 1e6)
        v >= 1e3 -> String.format(Locale.ROOT, "%.1fk", v / 1e3)
        else -> v.toInt().toString()
    }

    private const val BAR_WIDTH = 182
}
