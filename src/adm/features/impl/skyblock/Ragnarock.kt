package com.adm.features.impl.skyblock

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.LevelEvent
import com.adm.events.MessageEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.events.core.onSend
import com.adm.features.Module
import com.adm.features.impl.boss.WitherDragons
import com.adm.utils.Colors
import com.adm.utils.alert
import com.adm.utils.itemId
import com.adm.utils.modMessage
import com.adm.utils.ServerUtils
import com.adm.utils.sendCommand
import com.adm.utils.render.textDim
import com.adm.utils.strength
import com.adm.utils.toFixed
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import kotlin.math.abs

object Ragnarock : Module(
    name = "Ragnarock",
    description = "Ragnarock cast alerts and a channel and strength timer."
) {
    private val castAlert by BooleanSetting("Cast alert", true, "Alerts when you cast Ragnarock.")
    private val cancelAlert by BooleanSetting("Cancel alert", true, "Alerts when Ragnarock is cancelled.")
    private val strengthGainedMessage by BooleanSetting("Strength gained", true, "Shows Ragnarock strength gained.")
    private val announceStrengthGained by BooleanSetting("Announce strength", false, "Announce gained strength in party chat").withDependency { strengthGainedMessage }

    private val ragDragonTimer by BooleanSetting("Rag in dragon spawn", true, "Shows when to rag for the next M7 dragon.")
    private val hud by HUD("Rag Timer HUD", "Shows the Ragnarock channel and strength time.") { example ->
        if (example) return@HUD textDim("§eRag: §fcasting 2.15s", 0, 0, Colors.WHITE)
        val text = hudText() ?: return@HUD 0 to 0
        textDim(text, 0, 0, Colors.WHITE)
    }

    private const val CHANNEL_TICKS = 60
    private const val BUFF_TICKS = 200

    private var tick = 0L
    private var clicked = -1L
    private var castStart = -1L
    private var buffStart = -1L

    private fun hudText(): String? {
        if (castStart >= 0) return "§eRag: §fcasting ${((CHANNEL_TICKS - (tick - castStart)).coerceAtLeast(0) / 20f).toFixed(2)}s"
        if (buffStart >= 0) return "§cRag: §a${((BUFF_TICKS - (tick - buffStart)) / 20f).toFixed(2)}s"
        if (!ragDragonTimer) return null
        val spawn = WitherDragons.nextSpawnTicks()
        if (spawn < 0) return null
        val lead = spawn - CHANNEL_TICKS
        return if (lead > 0) "§6Rag in §f${(lead / 20f).toFixed(2)}s" else "§aRag now!"
    }

    // Hypixel only starts a cast it takes (off cooldown, with the mana), and then clicks a lever at you on that tick
    // and each second after; it lands with a wolf's death 60 ticks after the first lever.
    private const val LEVER_PITCH = 2f
    private const val LEVER_VOLUME = 0.55f
    private const val CAST_PITCH = 1.4920635f

    private val castSounds: Set<Identifier> by lazy {
        SoundEvents.WOLF_SOUNDS.values.mapTo(HashSet()) { it.adultSounds().deathSound().value().location() }
    }

    private fun near(x: Double, y: Double, z: Double, range: Double): Boolean =
        (mc.player?.distanceToSqr(x, y, z) ?: return false) <= range * range

    private fun cancelled() {
        clicked = -1
        if (castStart < 0) return
        castStart = -1
        if (cancelAlert) alert("§cRagnarock Cancelled!")
    }

    init {
        on<MessageEvent.Chat> {
            if (message == "Ragnarock was cancelled due to being hit!" || message == "Ragnarock was cancelled due to taking damage!") cancelled()
        }

        on<MessageEvent.Overlay> {
            if ("CANCELLED" in message) cancelled()
        }

        onSend<ServerboundUseItemPacket> {
            if (mc.player?.mainHandItem?.itemId == "RAGNAROCK_AXE") clicked = tick
        }

        on<TickEvent.Server> {
            tick++
            if (castStart >= 0 && tick - castStart > CHANNEL_TICKS + 40) castStart = -1
            if (buffStart >= 0 && tick - buffStart >= BUFF_TICKS) buffStart = -1
        }

        on<LevelEvent.Load> {
            clicked = -1
            castStart = -1
            buffStart = -1
        }

        onReceive<ClientboundSoundPacket> {
            if (abs(pitch - LEVER_PITCH) < 0.01f && abs(volume - LEVER_VOLUME) < 0.01f && sound.value().location().path == "block.lever.click" && near(x, y, z, 2.0)) {
                // the first lever comes back within a round trip of the click; later ones, or anyone else's, don't start one
                val trip = ServerUtils.averagePing / 50 + 1
                if (castStart < 0 && clicked >= 0 && tick - clicked <= trip + 3) castStart = tick
                return@onReceive
            }
            if (pitch != CAST_PITCH || sound.value().location() !in castSounds || !near(x, y, z, 3.0)) return@onReceive
            if (castStart < 0 && (clicked < 0 || tick - clicked > CHANNEL_TICKS + 40)) return@onReceive
            castStart = -1
            clicked = -1
            buffStart = tick
            if (castAlert) alert("§aCasted Rag")
            if (!strengthGainedMessage) return@onReceive
            val held = mc.player?.mainHandItem?.takeIf { it.itemId == "RAGNAROCK_AXE" } ?: return@onReceive
            val gained = (held.strength * 1.5).toInt()
            modMessage("§7Gained strength: §4$gained")
            if (announceStrengthGained) sendCommand("pc Gained strength from Ragnarock: $gained")
        }
    }
}
