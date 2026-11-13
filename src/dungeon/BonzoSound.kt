package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.events.MessageEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import kotlin.math.abs

object BonzoSound : Module(
    name = "Bonzo Sound",
    category = Category.DUNGEON,
    description = "The old ghast moan when your Bonzo's Mask saves you.",
    key = null,
) {
    private val muteNew by BooleanSetting("Mute New Sound", true, desc = "Mutes Hypixel's eat and cure sounds for a Bonzo proc.")
    private val moanVolume by NumberSetting("Volume", 1f, 0.1..1.0, 0.05f, desc = "Volume of the moan.")
    private val moanPitch by NumberSetting("Pitch", 1f, 0.5..2.0, 0.01f, desc = "Pitch of the moan.")

    private fun wearingBonzo(): Boolean =
        mc.player?.getItemBySlot(EquipmentSlot.HEAD)?.hoverName?.string?.contains("Bonzo's Mask") == true

    init {
        on<MessageEvent.Chat> {
            if (!message.startsWith("Your") || !message.endsWith("Bonzo's Mask saved your life!")) return@on
            mc.player?.playSound(SoundEvents.GHAST_AMBIENT, moanVolume, moanPitch)
        }

        onReceive<ClientboundSoundPacket> { event ->
            if (!muteNew || !wearingBonzo()) return@onReceive
            val s = sound.value()
            if ((s == SoundEvents.GENERIC_EAT.value() && abs(pitch - 0.59f) < 0.02f) ||
                (s == SoundEvents.ZOMBIE_VILLAGER_CURE && abs(pitch - 2f) < 0.01f)) event.cancel()
        }
    }
}
