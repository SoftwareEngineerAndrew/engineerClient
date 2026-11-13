package com.engineerclient.skyblock

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.resources.Identifier

object TabTweaks : Module(
    name = "Tab Tweaks",
    category = Category.SKYBLOCK,
    description = "Cleans up the tab list.",
    key = null,
) {
    private val removePing by BooleanSetting("Remove Tab Ping", false, desc = "Hides the ping bars next to each name.")
    private val removeHeads by BooleanSetting("Remove Useless Tab Heads", false, desc = "Hides the gray heads Hypixel puts on its info lines.")

    // The gray placeholder skin Hypixel gives every non-player tab entry.
    private const val GRAY_SKIN = "skins/f712b3a0a04da402d569cc02f3fcbc9228b2303a"

    fun hidesPing(): Boolean = enabled && removePing

    fun hidesHead(texture: Identifier): Boolean = enabled && removeHeads && texture.path == GRAY_SKIN
}
