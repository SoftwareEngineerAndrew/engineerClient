package com.engineerclient.dungeon

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.core.EventBus
import com.adm.events.core.onReceive
import com.adm.features.impl.dungeon.puzzlesolvers.PuzzleSolvers
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import kotlin.math.abs

object CreeperBeamsDing {
    private val beams get() = PuzzleSolvers.settings["Creeper Beams"]?.value == true
    private val on = BooleanSetting("Creeper Beams Ding", false, desc = "Your own sound when you hit a lantern.").withDependency { beams }
    private val removeCreeperHurt = BooleanSetting("Remove Creeper Hurt", false, desc = "Mutes the creeper when two lanterns connect.").withDependency { beams && on.value }
    private val removeExplosion = BooleanSetting("Remove Explosion", false, desc = "Mutes the explosion when the puzzle is done.").withDependency { beams && on.value }
    private lateinit var ding: SoundOption

    fun install() {
        PuzzleSolvers.insertSettings("Reset Beams") {
            for (s in listOf(on, removeCreeperHurt, removeExplosion)) PuzzleSolvers.registerSetting(s)
            ding = SoundOption(PuzzleSolvers, "minecraft:block.note_block.iron_xylophone", prefix = "Ding ") { beams && on.value }
        }
        onReceive<ClientboundSoundPacket> { event ->
            if (!PuzzleSolvers.enabled || !on.value || DungeonUtils.currentRoomName != "Creeper Beams") return@onReceive
            val s = sound.value()
            when {
                s == SoundEvents.CREEPER_HURT || s == SoundEvents.CREEPER_PRIMED -> if (removeCreeperHurt.value) event.cancel()
                s == SoundEvents.GENERIC_EXPLODE.value() -> if (removeExplosion.value) event.cancel()
                (s == SoundEvents.EXPERIENCE_ORB_PICKUP && near(pitch, 0.7936508f)) ||
                    (s == SoundEvents.ELDER_GUARDIAN_HURT && (near(pitch, 1.3968254f) || near(pitch, 2f))) -> if (ding.enabled) {
                    event.cancel()
                    Minecraft.getInstance().execute { ding.play() }
                }
            }
        }
        EventBus.subscribe(this)
    }

    private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}
