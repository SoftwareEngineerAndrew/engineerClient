package com.engineerclient.dungeon

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.events.PacketEvent
import com.adm.events.core.SubscribeWhile
import com.adm.events.core.onReceive
import com.adm.features.impl.dungeon.puzzlesolvers.PuzzleSolvers
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import kotlin.math.abs

object CreeperBeamsDing {
    private val solverOn get() = PuzzleSolvers.settings["Creeper Beams"]?.value == true
    private val on = BooleanSetting("Creeper Beams Ding", false, desc = "Your own sound when you hit a lantern.").withDependency { solverOn }
    private val removeCreeperHurt = BooleanSetting("Remove Creeper Hurt", false, desc = "Mutes the creeper when two lanterns connect.").withDependency { solverOn && on.value }
    private val removeExplosion = BooleanSetting("Remove Explosion", false, desc = "Mutes the explosion when the puzzle is done.").withDependency { solverOn && on.value }
    private lateinit var ding: SoundOption

    private val creeperHurt = SoundEvents.CREEPER_HURT
    private val creeperPrimed = SoundEvents.CREEPER_PRIMED
    private val explode = SoundEvents.GENERIC_EXPLODE.value()
    private val orb = SoundEvents.EXPERIENCE_ORB_PICKUP
    private val guardian = SoundEvents.ELDER_GUARDIAN_HURT
    private val playDing = Runnable { ding.play() }

    fun install() {
        PuzzleSolvers.insertSettings("Reset Beams") {
            PuzzleSolvers.registerSetting(on)
            PuzzleSolvers.registerSetting(removeCreeperHurt)
            PuzzleSolvers.registerSetting(removeExplosion)
            ding = SoundOption(PuzzleSolvers, "minecraft:block.note_block.iron_xylophone", prefix = "Ding ") { solverOn && on.value }
        }
        onReceive<ClientboundSoundPacket> { event ->
            if (on.value && PuzzleSolvers.enabled && DungeonUtils.currentRoomName == "Creeper Beams") filter(sound.value(), pitch, event)
        }
        SubscribeWhile.add(this) { on.value && PuzzleSolvers.enabled }
    }

    private fun filter(sound: SoundEvent, pitch: Float, event: PacketEvent.Receive) {
        if (sound == creeperHurt || sound == creeperPrimed) {
            if (removeCreeperHurt.value) event.cancel()
        } else if (sound == explode) {
            if (removeExplosion.value) event.cancel()
        } else if (isLanternHit(sound, pitch) && ding.enabled) {
            event.cancel()
            Minecraft.getInstance().execute(playDing)
        }
    }

    private fun isLanternHit(sound: SoundEvent, pitch: Float) =
        sound == orb && abs(pitch - 0.7936508f) < 1e-3f ||
            sound == guardian && (abs(pitch - 1.3968254f) < 1e-3f || abs(pitch - 2f) < 1e-3f)
}
