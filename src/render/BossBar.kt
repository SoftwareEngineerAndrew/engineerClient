package com.engineerclient.render

import com.adm.clickgui.settings.impl.SelectorSetting
import com.adm.events.EntityEvent
import com.adm.utils.FormatCache
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.utils.skyblock.dungeon.DungeonUtils
import com.adm.features.Category
import com.adm.features.Module
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object BossBar : Module(
    name = "Boss Bar",
    category = Category.DUNGEON,
    description = "Shows the boss's health or percentage left on its boss bar, and a bar for the Watcher's mobs.",
    key = null,
) {
    enum class Shown(private val label: String) { PERCENT("Percentage"), HEALTH("Health"); override fun toString() = label }

    private val shown by SelectorSetting("Show", Shown.PERCENT, desc = "Percentage left, or the boss's health.")

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
        on<LevelEvent.Load> { camp = false; killed = 0; mobs.clear(); cache.clear() }
    }

    @JvmStatic
    fun draw(g: GuiGraphicsExtractor, x: Int, y: Int, event: BossEvent) {
        if (!enabled) return
        val progress = event.progress
        val text = (if (shown == Shown.HEALTH) health(event, progress) else null)
            ?: percent(progress.toDouble())
        val w = mc.font.width(text)
        g.text(mc.font, text, x + (BAR_WIDTH - w) / 2, y - 1, -1, true)
    }

    private class Cached(var progress: Float, var at: Long, var text: String?)
    private val cache = java.util.IdentityHashMap<BossEvent, Cached>()

    private fun health(event: BossEvent, progress: Float): String? {
        val now = System.currentTimeMillis()
        val c = cache[event]
        if (c != null && c.progress == progress && (c.text != null || now - c.at < 1000)) return c.text
        val text = known(ChatFormatting.stripFormatting(event.name.string) ?: "", progress) ?: scan(progress)
        if (c == null) { if (cache.size > 8) cache.clear(); cache[event] = Cached(progress, now, text) } else { c.progress = progress; c.at = now; c.text = text }
        return text
    }

    // Hypixel never sends a dungeon boss's real HP, so its known max times the bar stands in for it.
    private fun known(name: String, progress: Float): String? {
        val floor = DungeonUtils.floor ?: return null
        val mm = floor.isMM
        val max = when (name) {
            "The Watcher" -> return count(progress, floor.floorNumber + 8)
            "Thorn" -> return count(progress, if (mm) 6 else 4)
            "Maxor" -> if (mm) 800e6 else 100e6
            "Storm" -> if (mm) 1e9 else 400e6
            "Goldor" -> if (mm) 1.2e9 else 750e6
            "Necron" -> if (mm) 1.4e9 else 1e9
            else -> return null
        }
        return "${amount(max * progress)}/${amount(max)}"
    }

    private fun count(progress: Float, total: Int) = "${(progress * total).roundToInt()}/$total"

    // Outside the table the boss in view whose health is at the bar's fraction gives the number.
    private fun scan(progress: Float): String? {
        val level = mc.level ?: return null
        val me = mc.player ?: return null
        var boss: LivingEntity? = null
        var best = Double.MAX_VALUE
        for (e in level.entitiesForRendering()) {
            if (e !is LivingEntity || e is Player || !e.isAlive || e.maxHealth <= 40f) continue
            if (abs(e.health / e.maxHealth - progress) >= 0.01f) continue
            val d = e.distanceToSqr(me)
            if (d < best) { best = d; boss = e }
        }
        boss ?: return null
        return "${amount(boss.health.toDouble())}/${amount(boss.maxHealth.toDouble())}"
    }

    private val percent = FormatCache(1) { String.format(Locale.ROOT, "%.1f%%", it.toFloat() * 100f) }
    private val amount = FormatCache { format(it.toFloat()) }

    private fun format(v: Float): String = when {
        v >= 1e9 -> String.format(Locale.ROOT, "%.2fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.ROOT, "%.1fM", v / 1e6)
        v >= 1e3 -> String.format(Locale.ROOT, "%.1fk", v / 1e3)
        else -> v.toInt().toString()
    }

    private const val BAR_WIDTH = 182
}
