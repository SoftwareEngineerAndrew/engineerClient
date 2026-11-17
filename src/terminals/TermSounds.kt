package com.engineerclient.terminals

import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import com.adm.events.core.EventBus
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.utils.playSoundAtPlayer
import com.adm.utils.playSoundSettings
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents

/** Your own terminal click and completion sounds in place of Hypixel's ding. */
object TermSounds {
    private val coreRegex = Regex("^The Core entrance is opening!$")
    private val gateRegex = Regex("^The gate has been destroyed!$")
    private var lastPlayed = 0L

    /** Click sounds replace the terminal's own (the simulator's too). */
    @JvmStatic val replacesClicks get() = Terminals.soundsOn && Terminals.clickSounds

    private val replacing get() = replacesClicks && TerminalUtils.currentTerm != null

    fun install() {
        // Melody: the alert repeats for as long as the lined up pane can be clicked.
        var melodyTicks = 0
        on<TickEvent.End> {
            val m = TerminalUtils.currentTerm as? MelodyHandler
            if (m == null || !Terminals.melodyAlert || !m.alerting()) { melodyTicks = 0; return@on }
            if (melodyTicks++ % Terminals.melodyRepeat == 0) playSoundSettings(Terminals.melodySound())
        }

        val glow = ScreenEvents.AfterExtract { _, graphics, _, _, _ ->
            val m = TerminalUtils.currentTerm as? MelodyHandler ?: return@AfterExtract
            if (Terminals.melodyAlert && Terminals.melodyVignette && m.alerting()) {
                val c = Terminals.melodyVignetteColor
                com.engineerclient.render.Vignette.draw(graphics, c.rgba, (c.alphaFloat * 255).toInt())
            }
        }
        // Every init and resize gives the screen fresh events, so this registers again each time.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*>) ScreenEvents.afterExtract(screen).register(glow)
        }

        on<TerminalEvent.Solve> {
            if (!Terminals.soundsOn) return@on
            if (replacing && !Terminals.completeSounds) playSoundAtPlayer(SoundEvents.NOTE_BLOCK_PLING.value(), 8f, 4f)
        }

        on<TerminalEvent.Click> {
            if (!Terminals.soundsOn) return@on
            val last = solution.isEmpty() || (terminal.type == TerminalTypes.MELODY && slotIndex == 34)
            if (last && Terminals.completeSounds) playSoundSettings(Terminals.completeSound())
            else if (Terminals.clickSounds && System.currentTimeMillis() - lastPlayed > 2) {
                playSoundSettings(Terminals.clickSound())
                lastPlayed = System.currentTimeMillis()
            }
        }

        onReceive<ClientboundSoundPacket> {
            if (!replacing) return@onReceive
            // Hypixel's own click sound (the orb), and its completion pling.
            if (sound.value() == SoundEvents.EXPERIENCE_ORB_PICKUP ||
                sound.value() == SoundEvents.NOTE_BLOCK_PLING.value() && volume == 8f && pitch == 4.047619f) it.cancel()
        }

        on<MessageEvent.Chat> {
            if (!DungeonUtils.inDungeons || !replacing) return@on
            if (gateRegex.matches(message) || coreRegex.matches(message)) playSoundAtPlayer(SoundEvents.NOTE_BLOCK_PLING.value())
        }
        EventBus.subscribe(this)
    }
}
