package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.RenderableSetting.Companion.withDependency
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.ColorSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.TickEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.Color
import com.odtheking.odin.utils.render.text
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import java.util.Locale

object PhaseTimers : Module(
    name = "Phase Timers",
    category = Category.BOSS,
    description = "Counts up through each F7 phase: Maxor, Storm, Goldor, and the 3 s until terminals open.",
    key = null,
) {
    private val stormOn by BooleanSetting("Storm Timer", true, desc = "Time since Storm's first line, until he dies.")
    private val stormColor by ColorSetting("Storm Color", Color(0xFF00F9DD.toInt()), false, desc = "Colour of the Storm timer.").withDependency { stormOn }
    private val termStartOn by BooleanSetting("Term Start Timer", true, desc = "Counts down the 3 s from Storm's death until terminals can be done.")
    private val goldorOn by BooleanSetting("Goldor Timer", true, desc = "Time since Goldor's first line, green / gold / red through each 3 s death tick.")
    private val combine by BooleanSetting("Combine Timers", true, desc = "Storm, Term Start and Goldor share one HUD.")

    private val maxorHud by HUD("Maxor Timer", "Time since Maxor's first line.", true, 400, 200, 2f) { example ->
        if (!example && phase != 1) return@HUD 0 to 0
        draw(fmt(if (example) 0 else maxorTicks), WHITE)
    }
    private val combinedHud by HUD("Combined Timer", "Storm, Term Start and Goldor timers in one place.", false, 400, 230, 2f) { example ->
        if (!combine) return@HUD 0 to 0
        if (example) return@HUD draw(fmt(0), WHITE)
        current()?.let { (t, c) -> draw(t, c) } ?: (0 to 0)
    }.withDependency { combine }
    private val stormHud by HUD("Storm HUD", "The Storm timer.", false, 400, 230, 2f) { example ->
        if (combine) return@HUD 0 to 0
        if (example) return@HUD draw(fmt(0), stormColor)
        stormText()?.let { (t, c) -> draw(t, c) } ?: (0 to 0)
    }.withDependency { !combine && stormOn }
    private val termStartHud by HUD("Term Start HUD", "The Term Start timer.", false, 400, 245, 2f) { example ->
        if (combine) return@HUD 0 to 0
        if (example) return@HUD draw(fmt(60), YELLOW)
        termStartText()?.let { (t, c) -> draw(t, c) } ?: (0 to 0)
    }.withDependency { !combine && termStartOn }
    private val goldorHud by HUD("Goldor HUD", "The Goldor timer.", false, 400, 260, 2f) { example ->
        if (combine) return@HUD 0 to 0
        if (example) return@HUD draw(fmt(0), GREEN)
        goldorText()?.let { (t, c) -> draw(t, c) } ?: (0 to 0)
    }.withDependency { !combine && goldorOn }

    private val WHITE = Color(0xFFFFFFFF.toInt())
    private val YELLOW = Color(0xFFFFFF55.toInt())
    private val GREEN = Color(0xFF55FF55.toInt())
    private val GOLD = Color(0xFFFFAA00.toInt())
    private val RED = Color(0xFFFF5555.toInt())

    private const val MAXOR_START = "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!"
    private const val STORM_START = "[BOSS] Storm: Pathetic Maxor, just like expected."
    private const val STORM_DEAD = "[BOSS] Storm: I should have known that I stood no chance."
    private const val GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?"
    private const val CORE_OPEN = "The Core entrance is opening!"
    private const val TERM_START_TICKS = 60

    /** 1 Maxor, 2 Storm, 3 terminals, 4 after; 0 outside the boss. */
    private var phase = 0
    private var maxorTicks = 0
    private var stormTicks = 0
    private var stormDead = false
    private var goldorTicks = 0
    private var termStartLeft = TERM_START_TICKS
    private var stormHurt = false

    private fun fmt(ticks: Int) = String.format(Locale.ROOT, "%.2f", ticks / 20.0)

    private fun stormText(): Pair<String, Color>? =
        if (stormOn && phase == 2 && !stormDead) fmt(stormTicks) to stormColor else null

    private fun termStartText(): Pair<String, Color>? =
        if (termStartOn && phase == 2 && stormHurt && termStartLeft > 0) fmt(termStartLeft) to YELLOW else null

    private fun goldorText(): Pair<String, Color>? {
        if (!goldorOn || phase != 3) return null
        val mod = (goldorTicks / 20.0) % 3
        return fmt(goldorTicks) to if (mod < 1) GREEN else if (mod < 2) GOLD else RED
    }

    private fun current() = stormText() ?: termStartText() ?: goldorText()

    private fun GuiGraphicsExtractor.draw(s: String, color: Color): Pair<Int, Int> {
        text(s, 0, 0, color, true)
        return mc.font.width(s) to 9
    }

    init {
        on<MessageEvent.Chat> {
            when (message) {
                MAXOR_START -> { phase = 1; maxorTicks = 0 }
                STORM_START -> { phase = 2; stormTicks = 0; stormDead = false }
                STORM_DEAD -> stormDead = true
                GOLDOR_START -> { phase = 3; goldorTicks = 0 }
                CORE_OPEN -> phase = 4
            }
        }

        // Storm's death is a loud wither hurt; terminals open 3 s after it.
        onReceive<ClientboundSoundPacket> {
            if (stormHurt || phase !in 2..3) return@onReceive
            if (sound.value() == SoundEvents.WITHER_HURT && volume == 15f && pitch == 1f) stormHurt = true
        }

        on<TickEvent.Server> {
            when (phase) {
                1 -> maxorTicks++
                2 -> if (!stormDead) stormTicks++
                3 -> goldorTicks++
            }
            if (stormHurt && termStartLeft > 0) termStartLeft--
        }

        on<LevelEvent.Load> {
            phase = 0; maxorTicks = 0; stormTicks = 0; stormDead = false; goldorTicks = 0
            termStartLeft = TERM_START_TICKS; stormHurt = false
        }
    }
}
