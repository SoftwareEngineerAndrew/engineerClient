package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.DropdownSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.impl.dungeon.SecretClicked
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.textDim
import com.odtheking.odin.utils.skyblock.ActionBarListener
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.util.Optional

/** Secrets' counter: the room's found/total as a HUD, red to yellow to green, in place of the action bar's. */
object SecretCounter {
    private val dropdown = DropdownSetting("Counter", desc = "The room's secret count as a HUD.")
    private val hud = SecretClicked.HUD("Secret Counter", "The room's secrets found and total; the action bar's count goes while it's on.", true, 470, 300, 1.5f) { example ->
        val (found, max) = if (example) 3 to 5 else count?.takeIf { DungeonUtils.inClear } ?: return@HUD 0 to 0
        val color = when {
            found >= max -> "§a"
            found == 0 -> "§c"
            else -> "§e"
        }
        textDim("$color$found/$max", 0, 0, Colors.WHITE)
    }.withDependency { dropdown.value }

    private val SECRETS = Regex("""\s*\d+/\d+ Secrets""")
    private var count: Pair<Int, Int>? = null

    private fun on() = SecretClicked.enabled && hud.value.enabled && DungeonUtils.inDungeons

    fun install() {
        SecretClicked.registerSetting(dropdown)
        SecretClicked.registerSetting(hud)
        on<LevelEvent.Load> { count = null }
        on<RoomEnterEvent> { count = null }
        on<MessageEvent.ModifyOverlay> {
            if (!on()) return@on
            val m = Regex("""(\d+)/(\d+) Secrets""").find(message) ?: return@on
            count = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            ActionBarListener.secrets(message)
            component = without(component, SECRETS)
        }
        EventBus.subscribe(this)
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
