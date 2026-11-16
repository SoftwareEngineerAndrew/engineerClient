package com.adm.features.impl.skyblock

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.MessageEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Module
import com.adm.utils.*
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents

object Ragnarock : Module(
    name = "Ragnarock",
    description = "Alerts when you cast the Ragnarock or it gets cancelled."
) {
    private val castAlert by BooleanSetting("Cast alert", true, "Alerts when you cast Ragnarock.")
    private val cancelAlert by BooleanSetting("Cancel alert", true, "Alerts when Ragnarock is cancelled.")
    private val strengthGainedMessage by BooleanSetting("Strength gained", true, "Shows Ragnarock strength gained.")
    private val announceStrengthGained by BooleanSetting("Announce strength", false, "Announce gained strength in party chat").withDependency { strengthGainedMessage }

    private val cancelRegex = Regex("Ragnarock was cancelled due to (?:being hit|taking damage)!")

    init {
        on<MessageEvent.Chat> {
            if (cancelAlert && message.matches(cancelRegex)) alert("§cRagnarock Cancelled!")
        }

        onReceive<ClientboundSoundPacket> {
            if (pitch == 1.4920635f && mc.player?.mainHandItem?.itemId == "RAGNAROCK_AXE" &&
                SoundEvents.WOLF_SOUNDS.entries.any { it.value.adultSounds.deathSound.value().location == sound.value().location }
            ) {
                if (castAlert) alert("§aCasted Rag")
                val strengthGained = ((mc.player?.mainHandItem?.strength ?: return@onReceive) * 1.5).toInt()
                if (strengthGainedMessage) {
                    modMessage("§7Gained strength: §4$strengthGained")
                    if (announceStrengthGained) sendCommand("pc Gained strength from Ragnarock: $strengthGained")
                }
            }
        }
    }
}