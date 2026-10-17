package com.devgineerclient.misc

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module

/**
 * Rendering experiments. Each one is read once, when the game's graphics device is created, so a change needs a
 * restart (Super+Delete).
 */
object RenderTweaks : Module(
    name = "Render Tweaks",
    key = null,
    category = Category.custom("Devgineer Client"),
    description = "Rendering experiments; take effect after a restart.",
) {
    private val persistentBuffersSetting by BooleanSetting("NVIDIA Persistent Buffers", true, desc = "Minecraft turns off persistent (ARB_buffer_storage) buffers on NVIDIA, so every frame maps, waits on the GPU and unmaps instead (a third of the render thread in a profile). This turns them back on. Restart to apply; turn off if you see flicker or corruption.")

    /**
     * GlDevice's buffer storage choice. Minecraft 26.1.2 has no NVIDIA exception: it already uses persistent
     * (ARB_buffer_storage) buffers on every driver that has them, so there is nothing to turn back on and no mixin
     * reads this here.
     */
    val persistentBuffers get() = enabled && persistentBuffersSetting
}
