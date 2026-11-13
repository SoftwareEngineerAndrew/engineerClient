package com.engineerclient.dungeon

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import kotlin.math.abs

object CreeperBeamsDing : Module(
    name = "Creeper Beams Ding",
    category = Category.DUNGEON,
    description = "Creeper Beams: your own sound when you hit a lantern.",
    key = null,
) {
    private val removeCreeperHurt by BooleanSetting("Remove Creeper Hurt", false, desc = "Mutes the creeper when two lanterns connect.")
    private val removeExplosion by BooleanSetting("Remove Explosion", false, desc = "Mutes the explosion when the puzzle is done.")
    private val ding = SoundOption(this, "minecraft:block.note_block.iron_xylophone")

    init {
        onReceive<ClientboundSoundPacket> { event ->
            if (DungeonUtils.currentRoomName != "Creeper Beams") return@onReceive
            val s = sound.value()
            when {
                s == SoundEvents.CREEPER_HURT || s == SoundEvents.CREEPER_PRIMED -> if (removeCreeperHurt) event.cancel()
                s == SoundEvents.GENERIC_EXPLODE.value() -> if (removeExplosion) event.cancel()
                (s == SoundEvents.EXPERIENCE_ORB_PICKUP && near(pitch, 0.7936508f)) ||
                    (s == SoundEvents.ELDER_GUARDIAN_HURT && (near(pitch, 1.3968254f) || near(pitch, 2f))) -> if (ding.enabled) {
                    event.cancel()
                    mc.execute { ding.play() }
                }
            }
        }
    }

    private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}
