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
import net.minecraft.world.item.ItemStack
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

    private val howl = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("engineerclient", "wolf-howl"))

    private fun holdingAotv() = holding("Aspect of the End") || holding("Aspect of the Void")
    fun mutesAotv() = enabled && muteAotv && holdingAotv()
    private fun holding(name: String) = mc.player?.mainHandItem?.hoverName?.string?.contains(name) == true

    interface MonoSource {
        fun ecRecentre()
    }

    private val recentSounds = Collections.synchronizedSet(LinkedHashSet<Identifier>())
    @Volatile var recentSoundsVersion = 0L
        private set

    private fun interface Drop {
        fun test(p: ClientboundSoundPacket): Boolean
    }

    // first may drop before the boss rules, after always runs; spam is a pitch range muted at volume 0.5 in the boss.
    private class Route {
        var first: Drop? = null
        var after: Drop? = null
        var spamLow = Float.NaN
        var spamHigh = Float.NaN
    }

    private val routes: Map<Identifier, Route> by lazy {
        val m = HashMap<Identifier, Route>()
        fun at(id: Identifier): Route = m.getOrPut(id) { Route() }
        fun of(e: SoundEvent): Route = at(e.location())

        of(SoundEvents.NOTE_BLOCK_HARP.value()).first = Drop { muteBlessing && it.volume == 0.7f && DungeonUtils.inDungeons }
        of(SoundEvents.GENERIC_EXPLODE.value()).first = Drop {
            // Hyperion's Implosion is the only explosion played at volume 1, pitch 1.
            muteExplosions && !(keepHype && it.volume == 1f && it.pitch == 1f)
        }
        of(SoundEvents.ENDERMAN_TELEPORT).apply {
            first = Drop { muteAotv && it.volume == 1f && it.pitch == 1f && holdingAotv() }
            after = Drop { muteCooldown && it.pitch == 0f && it.volume == 8f }
        }
        of(SoundEvents.WITHER_DEATH).apply {
            first = Drop { muteWitherborn && it.volume == 1f && abs(it.pitch - 1.1904762f) < 0.0001f }
            spamLow = 0.87f
            spamHigh = 1.16f
        }
        of(SoundEvents.WITHER_HURT).apply {
            spamLow = Float.NEGATIVE_INFINITY
            spamHigh = Float.POSITIVE_INFINITY
        }
        of(SoundEvents.GHAST_AMBIENT).first = Drop { muteBonzoStaff && it.volume == 1f && it.pitch in 1.39f..1.8f }
        at(Identifier.withDefaultNamespace("mob.ghast.affectionate_scream")).first = Drop { muteBonzoStaff && it.volume == 1f }
        of(SoundEvents.WOLF_SOUNDS[WolfSoundVariants.SoundSet.CLASSIC]!!.adultSounds().deathSound().value()).after = Drop { p ->
            val swap = oldTuba && p.volume == 0.5f && holding("Tuba") ||
                oldRagnarock && (p.volume == 1f || abs(p.pitch - 1.4920635f) < 0.001f) && holding("Ragnarock")
            if (swap) {
                val volume = p.volume
                val pitch = p.pitch
                mc.execute { mc.player?.playSound(howl, volume, pitch) }
            }
            swap
        }
        of(SoundEvents.GENERIC_EAT.value()).after = Drop { bonzoNewSound() && abs(it.pitch - 0.59f) < 0.02f }
        of(SoundEvents.ZOMBIE_VILLAGER_CURE).after = Drop { bonzoNewSound() && abs(it.pitch - 2f) < 0.01f }
        m
    }

    private fun bonzoNewSound() = bonzo && bonzoMute &&
        mc.player?.getItemBySlot(EquipmentSlot.HEAD)?.hoverName?.string?.contains("Bonzo's Mask") == true

    private fun dropInBoss(p: ClientboundSoundPacket, route: Route?): Boolean {
        val volume = p.volume
        if (capF7 && volume > 1f) {
            val sound = p.sound.value()
            val source = p.source
            val x = p.x
            val y = p.y
            val z = p.z
            val pitch = p.pitch
            mc.execute { mc.level?.playLocalSound(x, y, z, sound, source, 1f, pitch, false) }
            return true
        }
        if (volume >= 2f || muteAboveCap && volume > 1f) return true
        if (route == null || volume != 0.5f) return false
        val pitch = p.pitch
        return pitch >= route.spamLow && pitch <= route.spamHigh
    }

    private var drawTicks = 0
    private val cooldownLine = Regex("^This ability is on cooldown for \\d+s\\.$")
    private const val GOLEM_RISE = "The ground begins to shake as an End Stone Protector rises from below!"

    private fun isLastBreath(stack: ItemStack): Boolean {
        val id = stack.itemId
        return id == "LAST_BREATH" || id == "STARRED_LAST_BREATH"
    }

    init {
        on<LevelEvent.Load> { recentSounds.clear(); drawTicks = 0 }

        onReceive<ClientboundSoundPacket> { event ->
            val route = routes[sound.value().location()]
            var drop = route?.first?.test(this) == true
            if (!drop && muteF7 && DungeonUtils.inBoss && DungeonUtils.isFloor(7)) drop = dropInBoss(this, route)
            if (route?.after?.test(this) == true) drop = true
            if (drop) event.cancel()
        }

        on<MessageEvent.Chat> {
            if (hideCooldownMessage && cooldownLine.matches(message)) cancel()
            if (golemSound && message == GOLEM_RISE) mc.player?.playSound(SoundEvents.ANVIL_PLACE, 1f, 1f)
            if (bonzo && message.startsWith("Your") && message.endsWith("Bonzo's Mask saved your life!"))
                mc.player?.playSound(SoundEvents.GHAST_AMBIENT, bonzoVolume, bonzoPitch)
        }

        on<TickEvent.End> {
            if (!lastBreath) { drawTicks = 0; return@on }
            val player = mc.player
            val using = player?.useItem
            if (player == null || !player.isUsingItem || using == null || using !== player.mainHandItem || using.isEmpty || !isLastBreath(using)) {
                drawTicks = 0
                return@on
            }
            val n = ++drawTicks
            val pitch = 0.5f + minOf(n, 20) * 0.075f
            val tick = if (n >= lastBreathThreshold) SoundEvents.ARROW_HIT_PLAYER else SoundEvents.NOTE_BLOCK_PLING.value()
            player.playSound(tick, lastBreathVolume, pitch)
        }
    }

    @JvmStatic
    fun monoActive(): Boolean = enabled && monoAudio

    // Straight ahead at the sound's real distance: it keeps its falloff but loses its direction.
    @JvmStatic
    fun centre(source: Int, pos: Vec3, relative: Boolean) {
        val distance = if (relative) pos.length() else pos.distanceTo(mc.soundManager.listenerTransform.position())
        AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, -distance.toFloat())
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
