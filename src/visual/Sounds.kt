package com.engineerclient.visual

import com.odtheking.odin.clickgui.settings.impl.ActionSetting
import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.clickgui.settings.impl.MapSetting
import com.odtheking.odin.events.LevelEvent
import com.odtheking.odin.events.core.on
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.phys.Vec3
import org.lwjgl.openal.AL10
import java.util.Collections
import kotlin.math.roundToInt

object Sounds : Module(
    name = "Sounds",
    category = Category.SKYBLOCK,
    description = "Per-sound volumes and mono audio.",
    key = null,
) {
    private val monoAudio by BooleanSetting("Mono Audio", false, desc = "Plays all game audio through a single centred channel.")
    private val volumes by MapSetting("Volumes", mutableMapOf<String, Float>())
    private val open by ActionSetting("Open Sound Manager", desc = "Opens the per-sound volume list.") {
        val previous = mc.gui.screen()
        mc.schedule { mc.gui.setScreen(SoundManagerScreen(previous)) }
    }

    interface MonoChannel {
        fun ecRefreshMonoPosition()
    }

    private val recentSounds = Collections.synchronizedSet(LinkedHashSet<Identifier>())
    @Volatile var recentSoundsVersion = 0L
        private set

    init {
        on<LevelEvent.Load> { recentSounds.clear() }
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
