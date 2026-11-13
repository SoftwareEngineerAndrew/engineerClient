package com.engineerclient.skyblock

import com.odtheking.odin.clickgui.settings.impl.BooleanSetting
import com.odtheking.odin.features.Category
import com.odtheking.odin.features.Module
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity

object NametagTweaks : Module(
    name = "Nametag Tweaks",
    category = Category.RENDER,
    description = "Own nametag, no nametag background, shadowed nametags.",
    key = null,
) {
    private val showOwn by BooleanSetting("Show Own Nametag", false, desc = "Draws your own nametag above your head.")
    private val noBackground by BooleanSetting("Hide Nametag Background", false, desc = "Removes the black box behind nametags.")
    private val shadow by BooleanSetting("Shadowed Nametag", false, desc = "Draws nametag text with a shadow.")
    private val hideDinnerbone by BooleanSetting("Hide Dinnerbone Nametag", false, desc = "Hides the nametag of anything named Dinnerbone.")

    fun showsOwn(entity: Entity): Boolean = enabled && showOwn && entity === Minecraft.getInstance().player

    fun hidesDinnerbone(entity: Entity): Boolean = enabled && hideDinnerbone && entity.customName?.string.equals("Dinnerbone", ignoreCase = true)

    fun background(color: Int): Int = if (enabled && noBackground) 0 else color

    fun shadow(original: Boolean): Boolean = original || (enabled && shadow)
}
