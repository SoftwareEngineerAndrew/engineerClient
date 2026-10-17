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
    category = Category.custom("Devgineer Client", 860, 10),
    description = "Rendering experiments; take effect after a restart.",
) {
    private val persistentBuffersSetting by BooleanSetting("NVIDIA Persistent Buffers", true, desc = "Does nothing on 26.2: this version already uses persistent (ARB_buffer_storage) buffers on NVIDIA. On 26.3 Minecraft turns them off there and this turns them back on.")

    /**
     * Read by nothing on 26.2: its GlDevice picks persistent buffers whenever ARB_buffer_storage is there (no NVIDIA
     * exception), so there is nothing to undo and no RenderTweaksBufferStorageMixin. The setting stays so configs match.
     */
    val persistentBuffers get() = enabled && persistentBuffersSetting
}
