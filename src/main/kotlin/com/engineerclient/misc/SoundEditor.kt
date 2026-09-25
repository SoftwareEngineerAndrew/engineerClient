package com.engineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.NumberSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

/**
 * A volume slider for particular sounds, from 0x (silent) to 10x, 1x being untouched.
 *
 * Above 1x is the point of it, and the game does not allow that on its own: every sound is capped
 * at full volume twice, once by Minecraft (SoundEngine clamps to 1) and once by OpenAL (each
 * source's AL_MAX_GAIN defaults to 1). The mixins scale the volume after Minecraft's cap and lift
 * OpenAL's for just that sound, so a boosted sound really plays louder — nothing else changes.
 */
object SoundEditor : Module(
    name = "Sound Editor",
    category = Category.custom("Engineer Client"),
    description = "Makes particular sounds louder or quieter, from silent to 10x.",
) {

    private val keyPickup by NumberSetting("Key Pickup", 1f, 0f, 10f, 0.1f, desc = "Devonian's key pickup sound (the vault shutter).", unit = "x")
    private val arrows by NumberSetting("Arrows", 1f, 0f, 10f, 0.1f, desc = "Every arrow sound: shooting, hitting a block, hitting a player.", unit = "x")
    private val endermanDeath by NumberSetting("Enderman Death", 1f, 0f, 10f, 0.1f, desc = "An enderman dying.", unit = "x")
    private val endermanTeleport by NumberSetting("Enderman Teleport", 1f, 0f, 10f, 0.1f, desc = "An enderman teleporting.", unit = "x")
    private val zombie by NumberSetting("Zombie", 1f, 0f, 10f, 0.1f, desc = "Every zombie sound: idle, hurt, death, steps, door attacks. Not zombie villagers or husks.", unit = "x")

    /** The multiplier for a sound event id such as "minecraft:entity.zombie.hurt"; 1 if none applies. */
    @JvmStatic
    fun multiplier(id: String): Float {
        if (!enabled) return 1f
        val path = id.removePrefix("minecraft:")
        return when {
            path == "block.vault.open_shutter" -> keyPickup
            path.startsWith("entity.arrow.") -> arrows
            path == "entity.enderman.death" -> endermanDeath
            path == "entity.enderman.teleport" -> endermanTeleport
            path.startsWith("entity.zombie.") -> zombie
            else -> 1f
        }
    }
}
