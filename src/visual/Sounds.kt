package com.engineerclient.visual

import com.adm.clickgui.settings.RenderableSetting.Companion.withDependency
import com.adm.clickgui.settings.impl.ActionSetting
import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.clickgui.settings.impl.MapSetting
import com.adm.clickgui.settings.impl.NumberSetting
import com.adm.events.MessageEvent
import com.adm.events.LevelEvent
import com.adm.events.TickEvent
import com.adm.events.core.on
import com.adm.events.core.onReceive
import com.adm.features.Category
import com.adm.features.Module
import com.adm.utils.itemId
import com.adm.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.animal.wolf.WolfSoundVariants
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.phys.Vec3
import org.lwjgl.openal.AL10
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

object Sounds : Module(
    name = "Sounds",
    category = Category.SKYBLOCK,
    description = "Per-sound volumes, mono audio, sound tweaks and the old Bonzo sound.",
    key = null,
) {
    private val monoAudio by BooleanSetting("Mono Audio", false, desc = "Plays all game audio through a single centred channel.")
    private val volumes by MapSetting("Volumes", mutableMapOf<String, Float>())
    private val open by ActionSetting("Open Sound Manager", desc = "Opens the per-sound volume list.") {
        val previous = mc.gui.screen()
        mc.schedule { mc.gui.setScreen(SoundManagerScreen(previous)) }
    }
    private val muteCooldown by BooleanSetting("Mute Cooldown Sound", true, desc = "No sound when an ability is on cooldown.")
    private val hideCooldownMessage by BooleanSetting("Hide Cooldown Message", false, desc = "Hides the \"This ability is on cooldown\" chat message.")
    private val muteF7 by BooleanSetting("Mute F7 Boss Sounds", false, desc = "Mutes very loud sounds and the wither hurt/death spam in the F7/M7 boss.")
    private val muteAboveCap by BooleanSetting("Mute Sounds Above Cap", false, desc = "Also mutes F7/M7 boss sounds louder than normal (volume over 1).").withDependency { muteF7 }
    private val capF7 by BooleanSetting("Cap F7 Boss Sounds", false, desc = "Plays F7/M7 boss sounds louder than normal at normal volume instead.").withDependency { muteF7 }
    private val muteWitherborn by BooleanSetting("Mute Witherborn Sound", false, desc = "Mutes the Witherborn wither exploding.")
    private val muteExplosions by BooleanSetting("Mute Explosions", false, desc = "Mutes every explosion sound.")
    private val keepHype by BooleanSetting("Keep Hyperion Explosions", true, desc = "Hyperion's Implosion still plays.").withDependency { muteExplosions }
    private val muteAotv by BooleanSetting("Mute AOTV Sounds", false, desc = "Mutes the Aspect of the End and Aspect of the Void teleport sound.")
    private val muteBlessing by BooleanSetting("Mute Blessing Jingle", false, desc = "Mutes the rising harp notes in dungeons.")
    private val muteBonzoStaff by BooleanSetting("Mute Bonzo Staff Sound", false, desc = "Mutes the Bonzo Staff's ghast scream; the firework boom stays.")
    private val lastBreath by BooleanSetting("Last Breath Pull Sound", false, desc = "Ticking sound while drawing a Last Breath, like Spring Boots.")
    private val lastBreathVolume by NumberSetting("Last Breath Volume", 1f, 0.0..5.0, 0.1f, desc = "Volume of the ticking.").withDependency { lastBreath }
    private val lastBreathThreshold by NumberSetting("Last Breath Threshold", 9, 0..21, 1, desc = "Ticks of drawing before the sound changes (21 never).").withDependency { lastBreath }
    private val golemSound by BooleanSetting("Golem Stage 5 Sound", false, desc = "Anvil sound when the End Stone Protector rises.")
    private val oldTuba by BooleanSetting("Old Tuba Sound", true, desc = "The Weird Tuba plays the 1.8.9 wolf howl.")
    private val oldRagnarock by BooleanSetting("Old Ragnarock Sound", true, desc = "Ragnarock plays the 1.8.9 wolf howl.")
    private val bonzo by BooleanSetting("Bonzo Sound", false, desc = "The old ghast moan when your Bonzo's Mask saves you.")
    private val bonzoMute by BooleanSetting("Bonzo Mute New Sound", true, desc = "Mutes Hypixel's eat and cure sounds for a Bonzo proc.").withDependency { bonzo }
    private val bonzoVolume by NumberSetting("Bonzo Volume", 1f, 0.1..1.0, 0.05f, desc = "Volume of the moan.").withDependency { bonzo }
    private val bonzoPitch by NumberSetting("Bonzo Pitch", 1f, 0.5..2.0, 0.01f, desc = "Pitch of the moan.").withDependency { bonzo }

    private val wolfDeath by lazy { SoundEvents.WOLF_SOUNDS[WolfSoundVariants.SoundSet.CLASSIC]!!.adultSounds().deathSound().value() }
    private val howl = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("engineerclient", "wolf-howl"))

    private fun holdingAotv() = holding("Aspect of the End") || holding("Aspect of the Void")
    fun mutesAotv() = enabled && muteAotv && holdingAotv()
    private fun holding(name: String) = mc.player?.mainHandItem?.hoverName?.string?.contains(name) == true

    interface MonoChannel {
        fun ecRefreshMonoPosition()
    }

    private val recentSounds = Collections.synchronizedSet(LinkedHashSet<Identifier>())
    @Volatile var recentSoundsVersion = 0L
        private set

    private var pullTicks = 0
    private val cooldownMessage = Regex("^This ability is on cooldown for \\d+s\\.$")

    private fun inF7Boss() = DungeonUtils.inBoss && DungeonUtils.isFloor(7)

    init {
        on<LevelEvent.Load> { recentSounds.clear(); pullTicks = 0 }
        on<MessageEvent.Chat> {
            if (hideCooldownMessage && cooldownMessage.matches(message)) cancel()
            if (golemSound && message == "The ground begins to shake as an End Stone Protector rises from below!")
                mc.player?.playSound(SoundEvents.ANVIL_PLACE, 1f, 1f)
        }
        on<TickEvent.End> {
            val player = mc.player
            val held = player?.mainHandItem
            if (!lastBreath || player == null || held == null || held.isEmpty || !player.isUsingItem || player.useItem !== held ||
                held.itemId.let { it != "LAST_BREATH" && it != "STARRED_LAST_BREATH" }) {
                pullTicks = 0
                return@on
            }
            pullTicks++
            val pitch = 0.5f + pullTicks.coerceAtMost(20) * 0.075f
            if (pullTicks >= lastBreathThreshold) player.playSound(SoundEvents.ARROW_HIT_PLAYER, lastBreathVolume, pitch)
            else player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), lastBreathVolume, pitch)
        }
        onReceive<ClientboundSoundPacket> { event ->
            val s = sound.value()
            if (muteBlessing && s == SoundEvents.NOTE_BLOCK_HARP.value() && volume == 0.7f && DungeonUtils.inDungeons) {
                event.cancel()
                return@onReceive
            }
            if (muteAotv && s == SoundEvents.ENDERMAN_TELEPORT && volume == 1f && pitch == 1f && holdingAotv()) {
                event.cancel()
                return@onReceive
            }
            // Implosion is the only explosion Hypixel plays at volume 1, pitch 1.
            if (muteExplosions && s == SoundEvents.GENERIC_EXPLODE.value() && !(keepHype && volume == 1f && pitch == 1f)) {
                event.cancel()
                return@onReceive
            }
            if (muteWitherborn && s == SoundEvents.WITHER_DEATH && volume == 1f && abs(pitch - 1.1904762f) < 0.0001f ||
                muteBonzoStaff && volume == 1f && (s == SoundEvents.GHAST_AMBIENT && pitch in 1.39f..1.8f || s.location().path == "mob.ghast.affectionate_scream")) {
                event.cancel()
                return@onReceive
            }
            if (!muteF7 || !inF7Boss()) return@onReceive
            if (capF7 && volume > 1f) {
                event.cancel()
                val snd = sound
                val src = source
                val px = x
                val py = y
                val pz = z
                val pit = pitch
                mc.execute { mc.level?.playLocalSound(px, py, pz, snd.value(), src, 1f, pit, false) }
                return@onReceive
            }
            if (volume >= 2f || muteAboveCap && volume > 1f ||
                volume == 0.5f && (s == SoundEvents.WITHER_HURT || s == SoundEvents.WITHER_DEATH && pitch in 0.87f..1.16f)) event.cancel()
        }
        on<MessageEvent.Chat> {
            if (!bonzo || !message.startsWith("Your") || !message.endsWith("Bonzo's Mask saved your life!")) return@on
            mc.player?.playSound(SoundEvents.GHAST_AMBIENT, bonzoVolume, bonzoPitch)
        }
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
        onReceive<ClientboundSoundPacket> { event ->
            if (!bonzo || !bonzoMute || mc.player?.getItemBySlot(EquipmentSlot.HEAD)?.hoverName?.string?.contains("Bonzo's Mask") != true) return@onReceive
            val s = sound.value()
            if ((s == SoundEvents.GENERIC_EAT.value() && abs(pitch - 0.59f) < 0.02f) ||
                (s == SoundEvents.ZOMBIE_VILLAGER_CURE && abs(pitch - 2f) < 0.01f)) event.cancel()
        }
    }

    @JvmStatic
    fun monoActive(): Boolean = enabled && monoAudio

    @JvmStatic
    fun distanceToListener(pos: Vec3): Double = pos.distanceTo(mc.soundManager.listenerTransform.position())

    @JvmStatic
    fun applyCenteredPosition(source: Int, distance: Double) {
        AL10.alSourcefv(source, AL10.AL_POSITION, floatArrayOf(0f, 0f, -distance.toFloat()))
    }

    @JvmStatic
    fun getMultiplier(id: String): Float = if (enabled) volumes[id] ?: 1f else 1f

    @JvmStatic
    fun recordPlayedSound(sound: SoundInstance) {
        if (!enabled || mc.gui.screen() is SoundManagerScreen) return
        synchronized(recentSounds) {
            recentSounds.remove(sound.identifier)
            recentSounds.add(sound.identifier)
            if (recentSounds.size > 100) recentSounds.remove(recentSounds.first())
        }
        recentSoundsVersion++
    }

    fun getVolumePercent(id: String): Int = ((volumes[id] ?: 1f) * 100f).roundToInt()

    fun setVolumePercent(id: String, percent: Int) {
        val normalized = ((percent / 5f).roundToInt() * 5).coerceIn(0, 200)
        if (normalized == 100) volumes.remove(id) else volumes[id] = normalized / 100f
    }

    fun playPreview(sound: SoundEvent) = mc.soundManager.play(SimpleSoundInstance.forUI(sound, 1f, 0.25f))

    fun getRecentSoundIds(): List<String> = synchronized(recentSounds) { recentSounds.map(Identifier::toString).asReversed() }
}
