package com.engineerclient.render

import com.odtheking.odin.clickgui.settings.impl.SelectorSetting
import com.odtheking.odin.events.EntityEvent
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
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
    category = Category.DUNGEON,
    description = "Shows the boss's health or percentage left on its boss bar, and a bar for the Watcher's mobs.",
    key = null,
) {
    enum class Shown(private val label: String) { PERCENT("Percentage"), HEALTH("Health"); override fun toString() = label }

    private val shown by SelectorSetting("Show", Shown.PERCENT, desc = "Percentage left, or health when the boss is in view.")

    // The Watcher's camp: his mobs as a boss bar, emptying as they die (F7/M7 15, F6/M6 14, one fewer each floor down).
    private val watcherBar by HUD("Watcher Bar", "A boss bar for the Watcher's mobs left in the blood camp.", true, 389, 20, 1f) { example ->
        val total = if (example) 15 else campTotal() ?: return@HUD 0 to 0
        val left = if (example) 9 else (total - killed).coerceAtLeast(0)
        val title = "§cThe Watcher §7$left/$total"
        val tw = mc.font.width(title)
        text(mc.font, title, (BAR_WIDTH - tw) / 2, 0, -1, true)
        fill(0, 10, BAR_WIDTH, 15, 0xFF3A0000.toInt())
        fill(0, 10, BAR_WIDTH * left / total, 15, 0xFFE02020.toInt())
        BAR_WIDTH to 15
    }

    private var camp = false
    private var killed = 0
    private val mobs = HashSet<Int>()

    private fun campTotal(): Int? {
        if (!camp || !DungeonUtils.inClear) return null
        val floor = DungeonUtils.floor?.floorNumber ?: return null
        return floor + 8
    }

    init {
        on<MessageEvent.Chat> {
            when {
                message == "[BOSS] The Watcher: You have proven yourself. You may pass." -> camp = false
                message.startsWith("[BOSS] The Watcher: ") && !camp && killed == 0 && mobs.isEmpty() -> camp = true
            }
        }
        on<EntityEvent.Add> {
            val e = entity as? Player ?: return@on
            if (!camp || e === mc.player) return@on
            val name = e.name.string
            if (name.isBlank() || "Watcher" in name || DungeonUtils.dungeonTeammates.any { it.name == name }) return@on
            mobs += e.id
        }
        on<EntityEvent.Remove> {
            val e = entity as? LivingEntity ?: return@on
            if (mobs.remove(e.id) && e.isDeadOrDying) killed++
        }
        on<LevelEvent.Load> { camp = false; killed = 0; mobs.clear() }
    }

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
