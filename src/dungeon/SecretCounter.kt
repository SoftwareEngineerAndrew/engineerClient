package com.engineerclient.dungeon

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.DropdownSetting
import com.adm.events.LevelEvent
import com.adm.events.RoomEnterEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.on
import com.adm.features.impl.dungeon.SecretClicked
import com.adm.features.impl.dungeon.map.tile.MapCheckmark
import com.adm.utils.Colors
import com.adm.utils.render.textDim
import com.adm.utils.noControlCodes
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.util.Optional

/** Secrets' counter: the room's found/total as a HUD, red to yellow to green, in place of the action bar's. */
object SecretCounter {
    private val dropdown = DropdownSetting("Counter", desc = "The room's secret count as a HUD.")
    private val show = BooleanSetting("Show Counter", true, desc = "The room's secrets found and total; the action bar's count goes while it's on.").withDependency { dropdown.value }
    private val hud = SecretClicked.HUD("Secret Counter", "Where the counter sits.", false, 470, 300, 1.5f) { example ->
        if (!show.value) return@HUD 0 to 0
        val (found, max) = if (example) 3 to 5 else count?.takeIf { DungeonUtils.inClear } ?: return@HUD 0 to 0
        val color = when {
            found >= max -> "§a"
            found == 0 -> "§c"
            else -> "§e"
        }
        textDim("$color$found/$max", 0, 0, Colors.WHITE)
    }.withDependency { dropdown.value }

    private val SECRETS = Regex("""\s*(§.)*\d+/\d+ Secrets""")
    private val COUNT = Regex("""(\d+)/(\d+) Secrets""")
    private var count: Pair<Int, Int>? = null

    private fun on() = SecretClicked.enabled && show.value && DungeonUtils.inDungeons

    fun install() {
        SecretClicked.registerSetting(dropdown)
        SecretClicked.registerSetting(show)
        SecretClicked.registerSetting(hud)
        on<LevelEvent.Load> { count = null }
        // Until the action bar carries a count, the scanned room's own numbers.
        on<RoomEnterEvent> {
            val r = room
            val max = r?.data?.maxSecrets ?: 0
            count = if (r == null || max <= 0) null
            else (if (r.checkmark == MapCheckmark.GREEN) max else r.foundSecrets ?: 0) to max
        }
        SubscribeWhile.add(this, { count = null }) { SecretClicked.enabled && show.value }
    }

    @JvmStatic
    fun overlay(c: Component): Component {
        if (!on()) return c
        val m = COUNT.find(c.string.noControlCodes) ?: return c
        count = m.groupValues[1].toInt() to m.groupValues[2].toInt()
        return without(c, SECRETS)
    }

    /** [c] with the text [cut] matches taken out, each remaining piece keeping its style. */
    private fun without(c: Component, cut: Regex): Component {
        val parts = ArrayList<Pair<Style, String>>()
        c.visit({ style, text -> parts += style to text; Optional.empty<Unit>() }, Style.EMPTY)
        val plain = parts.joinToString("") { it.second }
        val range = cut.find(plain)?.range ?: return c
        val out: MutableComponent = Component.empty()
        var at = 0
        for ((style, text) in parts) {
            val start = at; val end = at + text.length
            at = end
            val keep = buildString { for (i in start until end) if (i !in range) append(text[i - start]) }
            if (keep.isNotEmpty()) out.append(Component.literal(keep).setStyle(style))
        }
        return out
    }
}
