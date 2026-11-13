package com.engineerclient.visual

import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.createSoundSettings
import com.odtheking.odin.utils.playSoundSettings
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents

object ArrowHitSound : Module(
    name = "Arrow Hit Sound",
    category = Category.SKYBLOCK,
    description = "Replaces the arrow hit sound with a sound of your choice.",
    key = null,
) {
    private val sound = createSoundSettings("Sound", "block.note_block.harp") { true }

    @JvmStatic
    fun onSoundPlay(instance: SoundInstance): Boolean {
        if (!enabled || instance.identifier != SoundEvents.ARROW_HIT_PLAYER.location) return false
        val settings = sound()
        if (Identifier.tryParse(settings.first) == instance.identifier) return false
        playSoundSettings(settings)
        return true
    }
}
