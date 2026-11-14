package com.engineerclient.visual

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.animal.wolf.WolfSoundVariants
import kotlin.math.abs

object SoundTweaks : Module(
    name = "Sound Tweaks",
    category = Category.RENDER,
    description = "Mutes the ability cooldown sound and brings back old item sounds.",
    key = null,
    toggled = true,
) {
    private val muteCooldown by BooleanSetting("Mute Cooldown Sound", true, desc = "No sound when an ability is on cooldown.")
    private val oldTuba by BooleanSetting("Old Tuba Sound", true, desc = "The Weird Tuba plays the 1.8.9 wolf howl.")
    private val oldRagnarock by BooleanSetting("Old Ragnarock Sound", true, desc = "Ragnarock plays the 1.8.9 wolf howl.")

    private val wolfDeath by lazy { SoundEvents.WOLF_SOUNDS[WolfSoundVariants.SoundSet.CLASSIC]!!.adultSounds().deathSound().value() }
    private val howl = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("engineerclient", "wolf-howl"))

    private fun holding(name: String) = mc.player?.mainHandItem?.hoverName?.string?.contains(name) == true

    init {
        onReceive<ClientboundSoundPacket> { event ->
            val s = sound.value()
            if (muteCooldown && s == SoundEvents.ENDERMAN_TELEPORT && pitch == 0f && volume == 8f) {
                event.cancel()
                return@onReceive
            }
            if (s != wolfDeath) return@onReceive
            val replace = oldTuba && volume == 0.5f && holding("Tuba") ||
                oldRagnarock && (volume == 1f || abs(pitch - 1.4920635f) < 0.001f) && holding("Ragnarock")
            if (!replace) return@onReceive
            event.cancel()
            val vol = volume
            val pit = pitch
            mc.execute { mc.player?.playSound(howl, vol, pit) }
        }
    }
}
