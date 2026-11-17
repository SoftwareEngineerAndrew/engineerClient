package com.adm.features.impl.skyblock

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Module
import com.adm.utils.alert
import com.adm.utils.itemId
import com.adm.utils.modMessage
import com.adm.utils.sendCommand
import com.adm.utils.strength
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents

object Ragnarock : Module(
    name = "Ragnarock",
    description = "Alerts when you cast the Ragnarock or it gets cancelled."
) {
    private val castAlert by BooleanSetting("Cast alert", true, "Alerts when you cast Ragnarock.")
    private val cancelAlert by BooleanSetting("Cancel alert", true, "Alerts when Ragnarock is cancelled.")
    private val strengthGainedMessage by BooleanSetting("Strength gained", true, "Shows Ragnarock strength gained.")
    private val announceStrengthGained by BooleanSetting("Announce strength", false, "Announce gained strength in party chat").withDependency { strengthGainedMessage }

    private const val CAST_PITCH = 1.4920635f

    // The cast plays a wolf death sound at this pitch; any wolf variant's death sound counts.
    private val castSounds: Set<Identifier> by lazy {
        SoundEvents.WOLF_SOUNDS.values.mapTo(HashSet()) { it.adultSounds().deathSound().value().location() }
    }

    init {
        on<MessageEvent.Chat> {
            if (cancelAlert && message.startsWith("Ragnarock was cancelled due to ") &&
                (message == "Ragnarock was cancelled due to being hit!" || message == "Ragnarock was cancelled due to taking damage!")
            ) alert("§cRagnarock Cancelled!")
        }

        onReceive<ClientboundSoundPacket> {
            if (pitch != CAST_PITCH || sound.value().location() !in castSounds) return@onReceive
            val held = mc.player?.mainHandItem ?: return@onReceive
            if (held.itemId != "RAGNAROCK_AXE") return@onReceive
            if (castAlert) alert("§aCasted Rag")
            if (!strengthGainedMessage) return@onReceive
            val gained = (held.strength * 1.5).toInt()
            modMessage("§7Gained strength: §4$gained")
            if (announceStrengthGained) sendCommand("pc Gained strength from Ragnarock: $gained")
        }
    }
}
