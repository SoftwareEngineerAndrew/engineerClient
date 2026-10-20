package com.coffeeclient.misc

import com.coffeeclient.CoffeeClient.mc
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Colors
import com.odtheking.odin.utils.render.text
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Storm's countdowns, in server ticks so lag doesn't run them down (they were Engineer Client's Timers'):
 *  - Storm Crush Check: the next crush check, every 20 ticks from the phase starting (a tick before his
 *    first line) until he dies.
 *  - Storm Lightning: his Giga Lightning strikes 520 ticks into the phase (519-521 in 662 F7 and 371 M7
 *    runs since the boss update), 33 after "I'd be happy to show you what that's like!", which sets it
 *    again if his first line was missed.
 * Green / yellow / red by the share of the wait still to go, as Odin's tick timers are.
 */
object CoffeeTimers : Module(
    name = "Coffee Timers",
    key = null,
    category = Category.custom("Coffee Client", 860, 10),
    description = "Storm's countdowns: his next crush check and his Giga Lightning."
) {
    private val stormCheckHud by HUD("Storm Crush Check", "Counts down to Storm's next crush check, once a second through his phase.", true, 10, 130, 1.5f) { example ->
        val start = stormStart
        when {
            example -> timer("Crush", 12, CRUSH_PERIOD, true)
            start == null || stormDead -> 0 to 0
            else -> timer("Crush", CRUSH_PERIOD - Math.floorMod(serverTicks - start, CRUSH_PERIOD), CRUSH_PERIOD)
        }
    }
    private val stormLightningHud by HUD("Storm Lightning", "Counts down to Storm's Giga Lightning striking, 26 s into his phase.", true, 10, 145, 1.5f) { example ->
        val at = strikeAt
        when {
            example -> timer("Lightning", 300, LIGHTNING, true)
            stormDead || at == null -> 0 to 0
            else -> (at - serverTicks).takeIf { it in 1..LIGHTNING }?.let { timer("Lightning", it, LIGHTNING) } ?: (0 to 0)
        }
    }

    private const val CRUSH_PERIOD = 20
    private const val LIGHTNING = 520
    private const val STRIKE_AFTER_SHOW = 33

    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val STORM_SHOW = "[BOSS] Storm: I'd be happy to show you what that's like!"
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"

    private var serverTicks = 0
    private var stormStart: Int? = null
    private var stormDead = false
    /** When Giga Lightning strikes. */
    private var strikeAt: Int? = null

    private fun colour(left: Int, total: Int): String = when {
        left >= total * 0.66f -> "§a"
        left >= total * 0.33f -> "§6"
        else -> "§c"
    }

    /** The time left; [label] only in the HUD editor ([example]), to tell the timers apart there. */
    private fun GuiGraphicsExtractor.timer(label: String, left: Int, total: Int, example: Boolean = false): Pair<Int, Int> {
        val s = (if (example) "§7$label: " else "") + colour(left, total) + String.format(java.util.Locale.ROOT, "%.2fs", left / 20.0)
        text(s, 0, 0, Colors.WHITE, shadow = true)
        return mc.font.width(s) to 9
    }

    init {
        on<MessageEvent.Chat> {
            when (message) {
                STORM_START -> { stormStart = serverTicks - 1; stormDead = false; strikeAt = serverTicks - 1 + LIGHTNING }
                STORM_SHOW -> strikeAt = serverTicks + STRIKE_AFTER_SHOW
                STORM_DEAD -> stormDead = true
                GOLDOR_START -> { stormStart = null; strikeAt = null }
            }
        }
        on<TickEvent.Server> { serverTicks++ }
        on<LevelEvent.Load> { stormStart = null; stormDead = false; strikeAt = null }
    }
}
