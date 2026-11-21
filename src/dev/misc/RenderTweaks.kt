package com.devgineerclient.misc

import com.adm.clickgui.settings.impl.BooleanSetting
import com.adm.features.Category
import com.adm.features.Module

/**
 * Rendering experiments. Persistent buffers are read once, when the game's graphics device is created, so a change
 * needs a restart (Super+Delete).
 */
object RenderTweaks : Module(
    name = "Render Tweaks",
    key = null,
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Rendering experiments.",
) {
    private val persistentBuffersSetting by BooleanSetting("NVIDIA Persistent Buffers", true, desc = "Minecraft turns off persistent (ARB_buffer_storage) buffers on NVIDIA, so every frame maps, waits on the GPU and unmaps instead (a third of the render thread in a profile). This turns them back on. Restart to apply; turn off if you see flicker or corruption.")
    private val fastParticlesSetting by BooleanSetting("Fast Particle Movement", true, desc = "Particles skip block collisions: they pass through blocks and drips never splash.")

    /** GlDevice's buffer storage choice (RenderTweaksBufferStorageMixin). */
    val persistentBuffers get() = enabled && persistentBuffersSetting

    /** Particle.move only shifts the box (MixinParticleFastMove). */
    val fastParticles get() = enabled && fastParticlesSetting
}
